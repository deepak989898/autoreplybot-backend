package com.autoreplybot;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

final class ReplyHistoryAdapter extends RecyclerView.Adapter<ReplyHistoryAdapter.Holder> {
    private final List<ReplyEvent> items = new ArrayList<>();

    void submit(@NonNull List<ReplyEvent> values) {
        items.clear();
        items.addAll(values);
        notifyDataSetChanged();
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_reply_history, parent, false));
    }

    @Override public void onBindViewHolder(@NonNull Holder h, int position) {
        ReplyEvent event = items.get(position);
        h.incoming.setText(event.incomingMessage);
        h.reply.setText(event.replyText.isEmpty()
                ? h.itemView.getContext().getString(R.string.history_no_reply)
                : event.replyText);
        h.meta.setText(h.itemView.getContext().getString(R.string.history_meta,
                event.action.name().replace('_', ' '),
                event.intent.name().replace('_', ' '),
                event.approvalStatus.isEmpty() ? "not required" : event.approvalStatus,
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(new Date(event.timestamp))));
    }

    @Override public int getItemCount() { return items.size(); }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView incoming;
        final TextView reply;
        final TextView meta;
        Holder(@NonNull View view) {
            super(view);
            incoming = view.findViewById(R.id.text_history_incoming);
            reply = view.findViewById(R.id.text_history_reply);
            meta = view.findViewById(R.id.text_history_meta);
        }
    }
}
