package com.autoreplybot.remote;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/** Local persistence for active app / camera blocks. */
public final class RemoteAppBlockStore {
    public static final String MODE_APP = "app";
    public static final String MODE_CAMERA_HW = "camera_hw";
    public static final String PACKAGE_CAMERA_HW = "__camera_hardware__";

    private static final String PREFS = "remote_app_blocks";
    private static final String KEY_BLOCKS = "blocks_json";

    private final SharedPreferences prefs;

    public RemoteAppBlockStore(@NonNull Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @NonNull
    public synchronized List<Block> listAll() {
        String raw = prefs.getString(KEY_BLOCKS, "[]");
        List<Block> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw != null ? raw : "[]");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Block b = Block.fromJson(o);
                if (b != null) out.add(b);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    @NonNull
    public synchronized List<Block> listActive(long nowMs) {
        List<Block> out = new ArrayList<>();
        for (Block b : listAll()) {
            if (b.isActive(nowMs)) out.add(b);
        }
        return out;
    }

    @Nullable
    public synchronized Block get(@NonNull String packageName) {
        for (Block b : listAll()) {
            if (packageName.equals(b.packageName)) return b;
        }
        return null;
    }

    public synchronized void upsert(@NonNull Block block) {
        List<Block> all = new ArrayList<>(listAll());
        boolean found = false;
        for (int i = 0; i < all.size(); i++) {
            if (block.packageName.equals(all.get(i).packageName)) {
                all.set(i, block);
                found = true;
                break;
            }
        }
        if (!found) all.add(block);
        save(all);
    }

    public synchronized void remove(@NonNull String packageName) {
        List<Block> all = new ArrayList<>(listAll());
        Iterator<Block> it = all.iterator();
        while (it.hasNext()) {
            if (packageName.equals(it.next().packageName)) it.remove();
        }
        save(all);
    }

    public synchronized boolean isPackageBlocked(@NonNull String packageName, long nowMs) {
        Block b = get(packageName);
        return b != null && MODE_APP.equals(b.mode) && b.isActive(nowMs);
    }

    public synchronized boolean isCameraHardwareLocked(long nowMs) {
        Block b = get(PACKAGE_CAMERA_HW);
        return b != null && MODE_CAMERA_HW.equals(b.mode) && b.isActive(nowMs);
    }

    private void save(@NonNull List<Block> blocks) {
        JSONArray arr = new JSONArray();
        for (Block b : blocks) {
            arr.put(b.toJson());
        }
        prefs.edit().putString(KEY_BLOCKS, arr.toString()).apply();
    }

    public static final class Block {
        @NonNull public final String packageName;
        @NonNull public final String appName;
        @NonNull public final String mode;
        @NonNull public String status;
        public final long durationMs;
        public final long expiresAt;
        public final long createdAt;
        public long updatedAt;

        public Block(@NonNull String packageName,
                     @NonNull String appName,
                     @NonNull String mode,
                     @NonNull String status,
                     long durationMs,
                     long expiresAt,
                     long createdAt,
                     long updatedAt) {
            this.packageName = packageName;
            this.appName = appName;
            this.mode = mode;
            this.status = status;
            this.durationMs = durationMs;
            this.expiresAt = expiresAt;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }

        public boolean isActive(long nowMs) {
            if (!"active".equals(status)) return false;
            return expiresAt <= 0 || expiresAt > nowMs;
        }

        @NonNull
        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("packageName", packageName);
                o.put("appName", appName);
                o.put("mode", mode);
                o.put("status", status);
                o.put("durationMs", durationMs);
                o.put("expiresAt", expiresAt);
                o.put("createdAt", createdAt);
                o.put("updatedAt", updatedAt);
            } catch (Exception ignored) {
            }
            return o;
        }

        @Nullable
        static Block fromJson(@NonNull JSONObject o) {
            String pkg = o.optString("packageName", "");
            if (pkg.isEmpty()) return null;
            return new Block(
                    pkg,
                    o.optString("appName", pkg),
                    o.optString("mode", MODE_APP),
                    o.optString("status", "active"),
                    o.optLong("durationMs", 0L),
                    o.optLong("expiresAt", 0L),
                    o.optLong("createdAt", 0L),
                    o.optLong("updatedAt", 0L));
        }
    }

    @NonNull
    public static List<String> protectedPackages(@NonNull Context context) {
        List<String> list = new ArrayList<>();
        list.add(context.getPackageName());
        list.add("com.android.systemui");
        list.add("android");
        return Collections.unmodifiableList(list);
    }
}
