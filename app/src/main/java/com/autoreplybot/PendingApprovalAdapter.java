package com.autoreplybot;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

final class PendingApprovalAdapter
        extends RecyclerView.Adapter<PendingApprovalAdapter.Holder> {
    interface Listener {
        void onEdit(@NonNull PendingApproval item);
        void onSend(@NonNull PendingApproval item);
        void onIgnore(@NonNull PendingApproval item);
    }

    private final List<PendingApproval> items = new ArrayList<>();
    private final Listener listener;

    PendingApprovalAdapter(@NonNull Listener listener) {
        this.listener = listener;
    }

    void submit(@NonNull List<PendingApproval> values) {
        items.clear();
        items.addAll(values);
        notifyDataSetChanged();
    }

    void remove(@NonNull String id) {
        for (int i = 0; i < items.size(); i++) {
            if (id.equals(items.get(i).approvalId)) {
                items.remove(i);
                notifyItemRemoved(i);
                return;
            }
        }
    }

    void replace(@NonNull PendingApproval value) {
        for (int i = 0; i < items.size(); i++) {
            if (value.approvalId.equals(items.get(i).approvalId)) {
                items.set(i, value);
                notifyItemChanged(i);
                return;
            }
        }
    }

    @NonNull @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_pending_approval, parent, false));
    }

    @Override public void onBindViewHolder(@NonNull Holder h, int position) {
        PendingApproval item = items.get(position);
        h.incoming.setText(item.incomingMessage);
        h.reply.setText(item.suggestedReply.isEmpty()
                ? h.itemView.getContext().getString(R.string.approval_no_suggestion)
                : item.suggestedReply);
        h.meta.setText(h.itemView.getContext().getString(R.string.approval_meta,
                item.intent.name().replace('_', ' '),
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(new Date(item.createdAt))));
        h.edit.setOnClickListener(v -> listener.onEdit(item));
        h.send.setOnClickListener(v -> listener.onSend(item));
        h.ignore.setOnClickListener(v -> listener.onIgnore(item));
    }

    @Override public int getItemCount() { return items.size(); }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView incoming;
        final TextView reply;
        final TextView meta;
        final MaterialButton edit;
        final MaterialButton send;
        final MaterialButton ignore;

        Holder(@NonNull View view) {
            super(view);
            incoming = view.findViewById(R.id.text_approval_incoming);
            reply = view.findViewById(R.id.text_approval_reply);
            meta = view.findViewById(R.id.text_approval_meta);
            edit = view.findViewById(R.id.button_approval_edit);
            send = view.findViewById(R.id.button_approval_send);
            ignore = view.findViewById(R.id.button_approval_ignore);
        }
    }
}
