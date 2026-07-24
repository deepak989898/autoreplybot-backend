package com.autoreplybot.remote;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.autoreplybot.R;
import com.google.android.material.button.MaterialButton;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

final class RemoteTrustedClientsAdapter
        extends RecyclerView.Adapter<RemoteTrustedClientsAdapter.Holder> {

    interface Listener {
        void onRevoke(@NonNull RemoteTrustedClient client);
    }

    private final List<RemoteTrustedClient> items = new ArrayList<>();
    private final Listener listener;
    private final DateFormat dateFormat =
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);

    RemoteTrustedClientsAdapter(@NonNull Listener listener) {
        this.listener = listener;
    }

    void submit(@NonNull List<RemoteTrustedClient> values) {
        items.clear();
        items.addAll(values);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_remote_trusted_client, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        RemoteTrustedClient client = items.get(position);
        holder.name.setText(client.clientName.isEmpty() ? client.clientId : client.clientName);
        String platform = client.browser.isEmpty()
                ? client.platform
                : client.browser + (client.platform.isEmpty() ? "" : " · " + client.platform);
        holder.meta.setText(holder.itemView.getContext().getString(
                R.string.remote_trusted_meta,
                platform.isEmpty() ? client.clientId : platform,
                dateFormat.format(new Date(client.createdAt > 0 ? client.createdAt : 0L))));
        if (client.revoked) {
            holder.status.setVisibility(View.VISIBLE);
            holder.status.setText(R.string.remote_trusted_revoked);
            holder.revoke.setVisibility(View.GONE);
        } else {
            holder.status.setVisibility(View.GONE);
            holder.revoke.setVisibility(View.VISIBLE);
            holder.revoke.setOnClickListener(v -> listener.onRevoke(client));
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView meta;
        final TextView status;
        final MaterialButton revoke;

        Holder(@NonNull View view) {
            super(view);
            name = view.findViewById(R.id.text_client_name);
            meta = view.findViewById(R.id.text_client_meta);
            status = view.findViewById(R.id.text_client_status);
            revoke = view.findViewById(R.id.button_revoke);
        }
    }
}
