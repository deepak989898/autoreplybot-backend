package com.autoreplybot.remote;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.autoreplybot.R;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

final class RemoteTrustedClientsAdapter
        extends RecyclerView.Adapter<RemoteTrustedClientsAdapter.Holder> {

    interface Listener {
        void onRevoke(@NonNull RemoteTrustedClient client);

        void onEditPermissions(@NonNull RemoteTrustedClient client);
    }

    private final List<RemoteTrustedClient> items = new ArrayList<>();
    private final Listener listener;

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
        // Meta detail line and Revoke are hidden in the UI (layout gone) — pairing still works.
        if (holder.meta != null) {
            holder.meta.setVisibility(View.GONE);
        }
        if (holder.revoke != null) {
            holder.revoke.setVisibility(View.GONE);
            holder.revoke.setOnClickListener(null);
        }
        if (client.revoked) {
            holder.status.setVisibility(View.VISIBLE);
            holder.status.setText(R.string.remote_trusted_revoked);
            holder.permissions.setVisibility(View.GONE);
            holder.permissions.setOnClickListener(null);
        } else {
            holder.status.setVisibility(View.GONE);
            holder.permissions.setVisibility(View.VISIBLE);
            holder.permissions.setOnClickListener(v -> listener.onEditPermissions(client));
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
        final MaterialButton permissions;

        Holder(@NonNull View view) {
            super(view);
            name = view.findViewById(R.id.text_client_name);
            meta = view.findViewById(R.id.text_client_meta);
            status = view.findViewById(R.id.text_client_status);
            revoke = view.findViewById(R.id.button_revoke);
            permissions = view.findViewById(R.id.button_permissions);
        }
    }
}
