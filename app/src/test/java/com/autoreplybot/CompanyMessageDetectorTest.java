package com.autoreplybot;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CompanyMessageDetectorTest {
    private final CompanyMessageDetector detector = new CompanyMessageDetector();

    @Test public void otpEnglishNeverReplies() { assertStrong("VK-LOGIN", "Your OTP is 482913. Do not share it", MessageIntent.OTP_MESSAGE); }
    @Test public void otpHindiNeverReplies() { assertStrong("LOGIN", "आपका ओटीपी 482913 है, किसी से साझा न करें", MessageIntent.OTP_MESSAGE); }
    @Test public void bankDebitNeverReplies() { assertStrong("HDFC Bank", "Your bank account was debited by INR 500", MessageIntent.BANK_ALERT); }
    @Test public void bankHindiNeverReplies() { assertStrong("बैंक", "बैंक खाता में 500 रुपये जमा हुए", MessageIntent.BANK_ALERT); }
    @Test public void transactionAlertNeverReplies() { assertStrong("Alerts", "Transaction credited INR 1200 to account", MessageIntent.TRANSACTION_NOTIFICATION); }
    @Test public void paymentAlertNeverReplies() { assertStrong("PhonePe", "UPI payment successful, paid Rs. 200", MessageIntent.PAYMENT_ALERT); }
    @Test public void deliveryUpdateBlocked() { assertCompany("Courier", "Your parcel is out for delivery", MessageIntent.DELIVERY_UPDATE); }
    @Test public void orderConfirmationBlocked() { assertCompany("Store", "Your order is confirmed and shipped", MessageIntent.DELIVERY_UPDATE); }
    @Test public void promotionEnglishBlocked() { assertCompany("Shop", "Limited time sale: get 40% discount", MessageIntent.COMPANY_PROMOTION); }
    @Test public void promotionHindiBlocked() { assertCompany("दुकान", "आज ही ऑफर और भारी छूट पाएं", MessageIntent.COMPANY_PROMOTION); }
    @Test public void noReplySystemNeverReplies() { assertStrong("Service", "This is an automated message. Do not reply.", MessageIntent.SYSTEM_GENERATED_MESSAGE); }
    @Test public void broadcastMetadataBlocked() {
        CompanyMessageDetector.DetectionResult r = detect("Updates", "New announcement", false, true, false, unknown());
        assertTrue(r.companyMessage); assertTrue(r.shouldNeverReply);
    }
    @Test public void channelTextBlocked() { assertCompany("Updates", "Join our official channel for announcements", MessageIntent.AUTOMATED_NOTIFICATION); }
    @Test public void communityTextBlocked() { assertCompany("Updates", "Community announcement for all members", MessageIntent.AUTOMATED_NOTIFICATION); }
    @Test public void telecomServiceBlocked() { assertCompany("Telecom", "Your prepaid recharge validity ends today", MessageIntent.SERVICE_NOTIFICATION); }
    @Test public void verifiedBusinessBlocked() {
        CompanyMessageDetector.DetectionResult r = detect("Acme", "Hello customer", false, false, true, unknown());
        assertTrue(r.companyMessage); assertEquals(SenderCategory.VERIFIED_BUSINESS, r.category);
    }
    @Test public void savedAmazonKumarIsHuman() {
        CompanyMessageDetector.DetectionResult r = detect("Amazon Kumar", "Hello, kal milte hain?", true, false, false, friend());
        assertFalse(r.companyMessage);
    }
    @Test public void humanBrandMentionIsHuman() {
        CompanyMessageDetector.DetectionResult r = detect("Ravi", "I ordered from Amazon yesterday", false, false, false, unknown());
        assertFalse(r.companyMessage);
    }
    @Test public void unknownScubaLeadContinuesNormalFlow() {
        CompanyMessageDetector.DetectionResult r = detect("Unknown", "Hello, I want scuba diving details", false, false, false, unknown());
        assertFalse(r.companyMessage);
    }
    @Test public void directCompanySupportQuestionIsManualCandidate() {
        CompanyMessageDetector.DetectionResult r = detect("Acme Support", "Can you share your preferred appointment time?", false, false, false, unknown());
        assertTrue(r.companyMessage); assertTrue(r.genuineDirectHumanSupport);
        assertFalse(r.shouldNeverReply);
    }

    @Test public void sensitivePreviewRedactsSecrets() {
        String redacted = SensitiveRedactor.redact(
                "OTP 482913 card 1234567890123456 paid ₹500 phone +91 9876543210 https://x.test/a");
        assertFalse(redacted.contains("482913"));
        assertFalse(redacted.contains("1234567890123456"));
        assertFalse(redacted.contains("9876543210"));
        assertFalse(redacted.contains("x.test"));
    }

    @Test public void companyContactFieldsRoundTrip() {
        long now = 10L;
        ContactProfile profile = new ContactProfile("id", "", "Acme",
                RelationshipType.PROFESSIONAL, true, ReplyMode.SMART, "auto", "",
                false, false, true, "Acme", true, CompanyReplyMode.SMART_APPROVAL,
                now, now);
        ContactProfile restored = ContactProfile.fromMap("id", profile.toMap());
        assertTrue(restored.detectedAsCompany);
        assertTrue(restored.allowCompanyReplies);
        assertEquals(CompanyReplyMode.SMART_APPROVAL, restored.companyReplyMode);
    }

    private void assertStrong(String sender, String message, MessageIntent intent) {
        CompanyMessageDetector.DetectionResult r = detect(sender, message, false, false, false, unknown());
        assertTrue(r.companyMessage); assertTrue(r.shouldNeverReply);
        assertEquals(intent, r.inferredIntent);
    }

    private void assertCompany(String sender, String message, MessageIntent intent) {
        CompanyMessageDetector.DetectionResult r = detect(sender, message, false, false, false, unknown());
        assertTrue(r.companyMessage); assertEquals(intent, r.inferredIntent);
    }

    private CompanyMessageDetector.DetectionResult detect(
            String sender, String message, boolean saved, boolean broadcast,
            boolean verified, ContactProfile profile) {
        return detector.detect(new CompanyMessageDetector.Evidence(sender, message, sender,
                saved, false, broadcast, verified, profile));
    }

    private static ContactProfile unknown() {
        return ContactProfile.unknown("id", "Sender");
    }

    private static ContactProfile friend() {
        long now = System.currentTimeMillis();
        return new ContactProfile("id", "", "Amazon Kumar", RelationshipType.FRIEND,
                true, ReplyMode.SMART, "auto", "", false, true, now, now);
    }
}
