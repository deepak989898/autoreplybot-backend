package com.autoreplybot;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.ArrayList;
import java.util.List;

public class LoanHistoryFragment extends Fragment {
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_loan_history, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        RecyclerView list = view.findViewById(R.id.history_list);
        TextView empty = view.findViewById(R.id.history_empty);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        String uid = user != null ? user.getUid() : "";
        bindHistory(list, empty, uid);
    }

    @Override
    public void onResume() {
        super.onResume();
        View view = getView();
        if (view == null) return;
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        String uid = user != null ? user.getUid() : "";
        bindHistory(view.findViewById(R.id.history_list), view.findViewById(R.id.history_empty), uid);
    }

    private void bindHistory(RecyclerView list, TextView empty, String uid) {
        List<Row> rows = new ArrayList<>();
        if (OnboardingStore.isLoanSaved(requireContext(), uid)
                && OnboardingStore.applicationStatus(requireContext(), uid) != OnboardingStore.APP_NONE) {
            rows.add(new Row(
                    OnboardingStore.amount(requireContext(), uid),
                    OnboardingStore.tenure(requireContext(), uid),
                    OnboardingStore.emi(requireContext(), uid),
                    OnboardingStore.approvedAt(requireContext(), uid),
                    OnboardingStore.applicationStatus(requireContext(), uid)));
        }
        empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        list.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
        list.setAdapter(new Adapter(rows));
    }

    static final class Row {
        final int amount;
        final int tenure;
        final int emi;
        final long approvedAt;
        final int status;

        Row(int amount, int tenure, int emi, long approvedAt, int status) {
            this.amount = amount;
            this.tenure = tenure;
            this.emi = emi;
            this.approvedAt = approvedAt;
            this.status = status;
        }
    }

    private class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        private final List<Row> rows;

        Adapter(List<Row> rows) {
            this.rows = rows;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_loan_history, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Row row = rows.get(position);
            holder.amount.setText(KycDocuments.rupees(row.amount));
            long when = row.approvedAt > 0L ? row.approvedAt : System.currentTimeMillis();
            holder.meta.setText(getString(R.string.loan_history_meta, row.tenure,
                    KycDocuments.rupees(row.emi), LoanDates.medium(when)));
            int statusRes;
            int colorRes;
            if (row.status == OnboardingStore.APP_REVIEW) {
                statusRes = R.string.loan_status_review;
                colorRes = R.color.loan_status_review;
            } else if (row.status == OnboardingStore.APP_REJECTED) {
                statusRes = R.string.loan_status_rejected;
                colorRes = R.color.loan_status_rejected;
            } else if (row.status == OnboardingStore.APP_CLOSED) {
                statusRes = R.string.loan_status_closed;
                colorRes = R.color.loan_status_closed;
            } else if (row.status == OnboardingStore.APP_APPROVED) {
                statusRes = R.string.loan_status_approved;
                colorRes = R.color.loan_status_approved;
            } else if (row.status == OnboardingStore.APP_AWAITING_DISBURSE) {
                statusRes = R.string.loan_status_awaiting;
                colorRes = R.color.loan_status_awaiting;
            } else if (row.status == OnboardingStore.APP_DISBURSED) {
                statusRes = R.string.loan_status_disbursed;
                colorRes = R.color.loan_status_disbursed;
            } else {
                statusRes = R.string.loan_status_active;
                colorRes = R.color.loan_status_active;
            }
            holder.status.setText(statusRes);
            holder.status.setTextColor(ContextCompat.getColor(requireContext(), colorRes));
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final TextView amount;
            final TextView meta;
            final TextView status;

            Holder(@NonNull View itemView) {
                super(itemView);
                amount = itemView.findViewById(R.id.history_amount);
                meta = itemView.findViewById(R.id.history_meta);
                status = itemView.findViewById(R.id.history_status);
            }
        }
    }
}
