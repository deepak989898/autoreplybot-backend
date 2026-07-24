package com.autoreplybot;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Installed apps list with per-app auto-reply switch.
 */
public class InstalledAppToggleAdapter extends RecyclerView.Adapter<InstalledAppToggleAdapter.Holder> {

    public interface Listener {
        void onPackageToggled(@NonNull String packageName, boolean enabled);
    }

    private final List<InstalledAppInfo> allApps = new ArrayList<>();
    private final List<InstalledAppInfo> visibleApps = new ArrayList<>();
    @NonNull
    private final UserSettings settings;
    @NonNull
    private final Listener listener;
    @NonNull
    private Predicate<InstalledAppInfo> filter = app -> true;

    public InstalledAppToggleAdapter(@NonNull UserSettings settings, @NonNull Listener listener) {
        this.settings = settings;
        this.listener = listener;
    }

    public void setApps(@NonNull List<InstalledAppInfo> apps) {
        allApps.clear();
        allApps.addAll(apps);
        applyFilter();
    }

    public void setFilter(@NonNull Predicate<InstalledAppInfo> filter) {
        this.filter = filter;
        applyFilter();
    }

    private void applyFilter() {
        visibleApps.clear();
        for (InstalledAppInfo app : allApps) {
            if (filter.test(app)) {
                visibleApps.add(app);
            }
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_installed_app_toggle, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        InstalledAppInfo app = visibleApps.get(position);
        holder.icon.setImageDrawable(app.icon);
        holder.label.setText(app.label);
        holder.pkg.setText(app.packageName);
        holder.switchEnabled.setOnCheckedChangeListener(null);
        holder.switchEnabled.setChecked(settings.isPackageEnabled(app.packageName));
        holder.switchEnabled.setContentDescription(app.label);
        holder.switchEnabled.setOnCheckedChangeListener((buttonView, isChecked) ->
                listener.onPackageToggled(app.packageName, isChecked));
    }

    @Override
    public int getItemCount() {
        return visibleApps.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView label;
        final TextView pkg;
        final SwitchMaterial switchEnabled;

        Holder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.image_app_icon);
            label = itemView.findViewById(R.id.text_app_label);
            pkg = itemView.findViewById(R.id.text_app_package);
            switchEnabled = itemView.findViewById(R.id.switch_app_enabled);
        }
    }
}
