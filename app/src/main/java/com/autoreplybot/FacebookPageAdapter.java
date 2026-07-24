package com.autoreplybot;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.List;

public final class FacebookPageAdapter extends RecyclerView.Adapter<FacebookPageAdapter.VH> {

    public interface Listener {
        void onSelect(@NonNull FacebookManagedPage page);
    }

    private final List<FacebookManagedPage> pages;
    private final Listener listener;

    public FacebookPageAdapter(@NonNull List<FacebookManagedPage> pages, @NonNull Listener listener) {
        this.pages = pages;
        this.listener = listener;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_facebook_page_row, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        FacebookManagedPage p = pages.get(position);
        holder.title.setText(p.name);
        if (!p.instagramUserId.isEmpty()) {
            String igLine = p.instagramUsername.isEmpty()
                    ? holder.itemView.getContext().getString(R.string.ig_connected_status, p.instagramUserId)
                    : holder.itemView.getContext().getString(R.string.ig_connected_status_named, p.instagramUsername, p.instagramUserId);
            holder.sub.setText(holder.itemView.getContext().getString(R.string.fb_page_row_with_ig_template, p.id, igLine));
        } else {
            holder.sub.setText(holder.itemView.getContext().getString(R.string.fb_page_row_id_template, p.id));
        }
        holder.use.setOnClickListener(v -> listener.onSelect(p));
    }

    @Override
    public int getItemCount() {
        return pages.size();
    }

    static final class VH extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView sub;
        final MaterialButton use;

        VH(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.text_fb_page_name);
            sub = itemView.findViewById(R.id.text_fb_page_id);
            use = itemView.findViewById(R.id.button_fb_page_use);
        }
    }
}
