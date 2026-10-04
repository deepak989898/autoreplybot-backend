import { db } from "./firebase.js";
import { COL_USERS, COL_DEVICES } from "./remote-constants.js";

export const COL_LOAN = "loan";
export const DOC_APPLICATION = "application";

export const LOAN_REVIEW = "REVIEW";
export const LOAN_APPROVED = "APPROVED";
export const LOAN_AWAITING_DISBURSE = "AWAITING_DISBURSE";
export const LOAN_DISBURSED = "DISBURSED";
export const LOAN_REJECTED = "REJECTED";
export const LOAN_CLOSED = "CLOSED";

const MIN_AMOUNT = 1000;
const MAX_AMOUNT = 200000;

export function loanApplicationRef(uid) {
  return db().collection(COL_USERS).doc(String(uid)).collection(COL_LOAN).doc(DOC_APPLICATION);
}

export function annualPercent(amount) {
  const principal = clampAmount(amount);
  if (principal <= 50000) return 14;
  if (principal <= 100000) return 12;
  return 10;
}

export function quoteFor(amount, months) {
  const principal = clampAmount(amount);
  const tenure = Math.max(1, Number(months) || 1);
  const pct = annualPercent(principal);
  const monthlyRate = pct / 12 / 100;
  let emiExact;
  if (monthlyRate <= 0) {
    emiExact = principal / tenure;
  } else {
    const factor = Math.pow(1 + monthlyRate, tenure);
    emiExact = (principal * monthlyRate * factor) / (factor - 1);
  }
  const monthlyEmi = Math.max(1, Math.round(emiExact));
  const totalPayable = monthlyEmi * tenure;
  return {
    principal,
    months: tenure,
    annualPercent: pct,
    monthlyEmi,
    totalPayable,
    totalInterest: Math.max(0, totalPayable - principal),
  };
}

export function clampAmount(amount) {
  const n = Math.round(Number(amount) || 0);
  const clamped = Math.max(MIN_AMOUNT, Math.min(MAX_AMOUNT, n));
  return MIN_AMOUNT + Math.floor((clamped - MIN_AMOUNT) / 1000) * 1000;
}

export function sanitizeApplication(data) {
  if (!data || typeof data !== "object") return null;
  const requestedAmount = Number(data.requestedAmount || data.amount || 0);
  const approvedAmount = Number(data.approvedAmount || 0);
  const tenureMonths = Number(data.tenureMonths || data.tenure || 0);
  const quote = quoteFor(approvedAmount > 0 ? approvedAmount : requestedAmount, tenureMonths || 3);
  return {
    status: String(data.status || "").toUpperCase() || LOAN_REVIEW,
    stage: String(data.stage || ""),
    requestedAmount,
    approvedAmount: approvedAmount > 0 ? approvedAmount : 0,
    tenureMonths: quote.months,
    monthlyEmi: Number(data.monthlyEmi || quote.monthlyEmi),
    annualPercent: Number(data.annualPercent || quote.annualPercent),
    totalInterest: Number(data.totalInterest || quote.totalInterest),
    totalPayable: Number(data.totalPayable || quote.totalPayable),
    applicantName: String(data.applicantName || ""),
    phone: String(data.phone || ""),
    email: String(data.email || ""),
    bankHolderName: String(data.bankHolderName || ""),
    bankName: String(data.bankName || ""),
    bankAccount: String(data.bankAccount || ""),
    bankIfsc: String(data.bankIfsc || ""),
    agreementAcceptedAt: Number(data.agreementAcceptedAt || 0),
    disbursementUtr: String(data.disbursementUtr || ""),
    disbursedAt: Number(data.disbursedAt || 0),
    approvedAt: Number(data.approvedAt || 0),
    submittedAt: Number(data.submittedAt || 0),
    updatedAt: Number(data.updatedAt || 0),
    rejectedReason: String(data.rejectedReason || ""),
  };
}

export async function listOwnedDevices(uid) {
  const snap = await db().collection(COL_USERS).doc(uid).collection(COL_DEVICES).get();
  const devices = [];
  snap.forEach((doc) => {
    const d = doc.data() || {};
    if (d.revoked === true) return;
    devices.push({
      deviceId: doc.id,
      deviceName: String(d.deviceName || "Phone"),
      deviceModel: String(d.deviceModel || ""),
      lastSeenAt: Number(d.lastSeenAt || 0),
      online: Boolean(d.online),
    });
  });
  devices.sort((a, b) => (b.lastSeenAt || 0) - (a.lastSeenAt || 0));
  return devices;
}

export async function submitOwnLoan(uid, payload = {}) {
  const existingSnap = await loanApplicationRef(uid).get();
  const existing = existingSnap.exists ? sanitizeApplication(existingSnap.data()) : null;
  if (existing && existing.status && existing.status !== LOAN_REVIEW) {
    return existing;
  }
  const requested = clampAmount(payload.requestedAmount || existing?.requestedAmount || 0);
  const tenure = Number(payload.tenureMonths || existing?.tenureMonths || 3);
  const quote = quoteFor(requested, tenure);
  const now = Date.now();
  await loanApplicationRef(uid).set(
    {
      status: LOAN_REVIEW,
      stage: "review",
      requestedAmount: quote.principal,
      approvedAmount: 0,
      tenureMonths: quote.months,
      monthlyEmi: quote.monthlyEmi,
      annualPercent: quote.annualPercent,
      totalInterest: quote.totalInterest,
      totalPayable: quote.totalPayable,
      applicantName: String(payload.applicantName || existing?.applicantName || "").slice(0, 120),
      phone: String(payload.phone || existing?.phone || "").slice(0, 20),
      email: String(payload.email || existing?.email || "").slice(0, 120),
      submittedAt: existing?.submittedAt || now,
      updatedAt: now,
      ownerUid: uid,
      disbursementUtr: "",
    },
    { merge: true }
  );
  return sanitizeApplication((await loanApplicationRef(uid).get()).data());
}

export async function saveOwnBank(uid, payload = {}) {
  const workspace = await getOwnLoanWorkspace(uid);
  const app = workspace.application;
  if (!app || (app.status !== LOAN_APPROVED && app.status !== LOAN_AWAITING_DISBURSE)) {
    const err = new Error("Enter bank details only after the facility is approved.");
    err.code = "BAD_STATUS";
    throw err;
  }
  const holder = String(payload.bankHolderName || "").trim();
  const bank = String(payload.bankName || "").trim();
  const account = String(payload.bankAccount || "").replace(/\s+/g, "");
  const ifsc = String(payload.bankIfsc || "").trim().toUpperCase();
  if (holder.length < 3 || bank.length < 3 || account.length < 8 || ifsc.length !== 11) {
    const err = new Error("Enter valid bank account details.");
    err.code = "BAD_BANK";
    throw err;
  }
  const now = Date.now();
  await loanApplicationRef(uid).set(
    {
      stage: app.status === LOAN_AWAITING_DISBURSE ? "awaiting_disburse" : "bank",
      bankHolderName: holder.slice(0, 120),
      bankName: bank.slice(0, 120),
      bankAccount: account.slice(0, 24),
      bankIfsc: ifsc,
      updatedAt: now,
      ownerUid: uid,
    },
    { merge: true }
  );
  return sanitizeApplication((await loanApplicationRef(uid).get()).data());
}

export async function acceptOwnAgreement(uid) {
  const workspace = await getOwnLoanWorkspace(uid);
  const app = workspace.application;
  if (!app || (app.status !== LOAN_APPROVED && app.status !== LOAN_AWAITING_DISBURSE)) {
    const err = new Error("The facility agreement can be accepted only after approval.");
    err.code = "BAD_STATUS";
    throw err;
  }
  if (!app.bankAccount || !app.bankIfsc) {
    const err = new Error("Save bank account details before accepting the agreement.");
    err.code = "NO_BANK";
    throw err;
  }
  const now = Date.now();
  await loanApplicationRef(uid).set(
    {
      status: LOAN_AWAITING_DISBURSE,
      stage: "awaiting_disburse",
      agreementAcceptedAt: app.agreementAcceptedAt || now,
      bankHolderName: app.bankHolderName,
      bankName: app.bankName,
      bankAccount: app.bankAccount,
      bankIfsc: app.bankIfsc,
      updatedAt: now,
      ownerUid: uid,
    },
    { merge: true }
  );
  return sanitizeApplication((await loanApplicationRef(uid).get()).data());
}

export async function getOwnLoanWorkspace(uid) {
  const [devices, appSnap] = await Promise.all([
    listOwnedDevices(uid),
    loanApplicationRef(uid).get(),
  ]);
  return {
    devices,
    hasConnectedDevice: devices.length > 0,
    application: appSnap.exists ? sanitizeApplication(appSnap.data()) : null,
  };
}

export async function approveOwnLoan(uid, approvedAmountRaw) {
  const workspace = await getOwnLoanWorkspace(uid);
  if (!workspace.hasConnectedDevice) {
    const err = new Error("Connect your own phone to this account before approving a loan.");
    err.code = "NO_OWN_DEVICE";
    throw err;
  }
  if (!workspace.application) {
    const err = new Error("No loan application from your Kalyani Loan app.");
    err.code = "NO_APPLICATION";
    throw err;
  }
  if (workspace.application.status !== LOAN_REVIEW) {
    const err = new Error("This application is not awaiting approval.");
    err.code = "BAD_STATUS";
    throw err;
  }
  const requested = workspace.application.requestedAmount;
  let approved = clampAmount(approvedAmountRaw || requested);
  if (approved > requested) approved = requested;
  const quote = quoteFor(approved, workspace.application.tenureMonths);
  const now = Date.now();
  await loanApplicationRef(uid).set(
    {
      status: LOAN_APPROVED,
      stage: "approved",
      approvedAmount: quote.principal,
      monthlyEmi: quote.monthlyEmi,
      annualPercent: quote.annualPercent,
      totalInterest: quote.totalInterest,
      totalPayable: quote.totalPayable,
      approvedAt: now,
      updatedAt: now,
      ownerUid: uid,
    },
    { merge: true }
  );
  return sanitizeApplication((await loanApplicationRef(uid).get()).data());
}

export async function rejectOwnLoan(uid, reason) {
  const workspace = await getOwnLoanWorkspace(uid);
  if (!workspace.hasConnectedDevice) {
    const err = new Error("Connect your own phone to this account before declining a loan.");
    err.code = "NO_OWN_DEVICE";
    throw err;
  }
  if (!workspace.application || workspace.application.status !== LOAN_REVIEW) {
    const err = new Error("This application is not awaiting a decision.");
    err.code = "BAD_STATUS";
    throw err;
  }
  const now = Date.now();
  await loanApplicationRef(uid).set(
    {
      status: LOAN_REJECTED,
      stage: "rejected",
      rejectedReason: String(reason || "Application declined.").slice(0, 400),
      updatedAt: now,
      ownerUid: uid,
    },
    { merge: true }
  );
  return sanitizeApplication((await loanApplicationRef(uid).get()).data());
}

export async function disburseOwnLoan(uid, utrRaw) {
  const workspace = await getOwnLoanWorkspace(uid);
  if (!workspace.hasConnectedDevice) {
    const err = new Error("Connect your own phone to this account before disbursing.");
    err.code = "NO_OWN_DEVICE";
    throw err;
  }
  const app = workspace.application;
  if (!app || app.status !== LOAN_AWAITING_DISBURSE) {
    const err = new Error(
      "Disbursement is available only after the borrower submits bank details and signs the facility agreement."
    );
    err.code = "BAD_STATUS";
    throw err;
  }
  const utr = String(utrRaw || "").replace(/\s+/g, "").toUpperCase();
  if (!/^[A-Z0-9]{12,22}$/.test(utr)) {
    const err = new Error("Enter a valid UTR / UPI reference (12–22 letters or digits).");
    err.code = "BAD_UTR";
    throw err;
  }
  const now = Date.now();
  await loanApplicationRef(uid).set(
    {
      status: LOAN_DISBURSED,
      stage: "disbursed",
      disbursementUtr: utr,
      disbursedAt: now,
      updatedAt: now,
      ownerUid: uid,
    },
    { merge: true }
  );
  return sanitizeApplication((await loanApplicationRef(uid).get()).data());
}
