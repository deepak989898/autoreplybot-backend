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
import java.util.Map;

final class RemoteTrustedClientsAdapter
        extends RecyclerView.Adapter<RemoteTrustedClientsAdapter.Holder> {

    interface Listener {
        void onRevoke(@NonNull RemoteTrustedClient client);

        void onEditPermissions(@NonNull RemoteTrustedClient client);
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
        String auto = client.autoApproveSessions
                ? holder.itemView.getContext().getString(R.string.remote_trusted_auto_on)
                : holder.itemView.getContext().getString(R.string.remote_trusted_auto_off);
        Map<String, Boolean> caps = RemoteCapabilityKeys.defaultsFromClient(client);
        String mediaCaps = (Boolean.TRUE.equals(caps.get("camera")) ? "cam " : "")
                + (Boolean.TRUE.equals(caps.get("microphone")) ? "mic " : "")
                + (Boolean.TRUE.equals(caps.get("photoCapture")) ? "photo " : "")
                + (Boolean.TRUE.equals(caps.get("videoRecording")) ? "video " : "")
                + (Boolean.TRUE.equals(caps.get("audioRecording")) ? "audio " : "")
                + (Boolean.TRUE.equals(caps.get("torch")) ? "torch " : "");
        if (mediaCaps.isEmpty()) mediaCaps = "media off ";
        String moduleCaps = (Boolean.TRUE.equals(caps.get("locationCurrent")) ? "loc " : "")
                + (Boolean.TRUE.equals(caps.get("galleryList")) ? "gallery " : "")
                + (Boolean.TRUE.equals(caps.get("notificationsList")) ? "notif " : "")
                + (Boolean.TRUE.equals(caps.get("messagesList")) ? "sms " : "")
                + (Boolean.TRUE.equals(caps.get("filesList")) ? "files " : "")
                + (Boolean.TRUE.equals(caps.get("screenMirror")) ? "mirror " : "")
                + (Boolean.TRUE.equals(caps.get("screenRecord")) ? "record " : "")
                + (Boolean.TRUE.equals(caps.get("installedAppsList")) ? "apps " : "")
                + (Boolean.TRUE.equals(caps.get("appControl")) ? "appctl " : "");
        if (moduleCaps.isEmpty()) moduleCaps = "modules off";
        moduleCaps = mediaCaps + "· " + moduleCaps;
        holder.meta.setText(holder.itemView.getContext().getString(
                R.string.remote_trusted_meta,
                (platform.isEmpty() ? client.clientId : platform) + " · " + auto + " · " + moduleCaps.trim(),
                dateFormat.format(new Date(client.pairedAt > 0
                        ? client.pairedAt
                        : (client.createdAt > 0 ? client.createdAt : 0L)))));
        if (client.revoked) {
            holder.status.setVisibility(View.VISIBLE);
            holder.status.setText(R.string.remote_trusted_revoked);
            holder.revoke.setVisibility(View.GONE);
            holder.permissions.setVisibility(View.GONE);
        } else {
            holder.status.setVisibility(View.GONE);
            holder.revoke.setVisibility(View.VISIBLE);
            holder.permissions.setVisibility(View.VISIBLE);
            holder.revoke.setOnClickListener(v -> listener.onRevoke(client));
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
