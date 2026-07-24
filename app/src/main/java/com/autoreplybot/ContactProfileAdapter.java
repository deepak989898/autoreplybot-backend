package com.autoreplybot;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ContactProfileAdapter extends RecyclerView.Adapter<ContactProfileAdapter.Holder> {
    interface Listener { void onEdit(@NonNull ContactProfile profile); }

    private final List<ContactProfile> all = new ArrayList<>();
    private final List<ContactProfile> visible = new ArrayList<>();
    private final Listener listener;

    ContactProfileAdapter(@NonNull Listener listener) { this.listener = listener; }

    void submit(@NonNull List<ContactProfile> values) {
        all.clear();
        all.addAll(values);
        filter("");
    }

    void replace(@NonNull ContactProfile profile) {
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).contactId.equals(profile.contactId)) {
                all.set(i, profile);
                break;
            }
        }
        filter("");
    }

    void filter(@NonNull String query) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        visible.clear();
        for (ContactProfile profile : all) {
            if (q.isEmpty() || profile.displayName.toLowerCase(Locale.ROOT).contains(q)
                    || profile.relationshipType.name().toLowerCase(Locale.ROOT).contains(q)) {
                visible.add(profile);
            }
        }
        notifyDataSetChanged();
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_contact_profile, parent, false));
    }

    @Override public void onBindViewHolder(@NonNull Holder h, int position) {
        ContactProfile profile = visible.get(position);
        h.name.setText(profile.displayName.isEmpty()
                ? h.itemView.getContext().getString(R.string.unknown_contact)
                : profile.displayName);
        h.summary.setText(h.itemView.getContext().getString(R.string.contact_profile_summary,
                profile.relationshipType.name().replace('_', ' '),
                profile.replyMode.name().replace('_', ' ')));
        h.enabled.setOnCheckedChangeListener(null);
        h.enabled.setChecked(profile.autoReplyEnabled);
        h.enabled.setClickable(false);
        h.itemView.setOnClickListener(v -> listener.onEdit(profile));
    }

    @Override public int getItemCount() { return visible.size(); }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView summary;
        final SwitchMaterial enabled;
        Holder(@NonNull View view) {
            super(view);
            name = view.findViewById(R.id.text_contact_name);
            summary = view.findViewById(R.id.text_contact_summary);
            enabled = view.findViewById(R.id.switch_contact_enabled);
        }
    }
}
