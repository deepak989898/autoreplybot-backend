package com.autoreplybot.remote;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** SAF DocumentFile helpers scoped to user-authorized folder trees only. */
public final class RemoteFileManagerHelper {
    private static final String TAG = "RemoteFileMgr";

    private final Context app;
    private final RemoteModulePrefs prefs;

    public RemoteFileManagerHelper(@NonNull Context context) {
        this.app = context.getApplicationContext();
        this.prefs = new RemoteModulePrefs(app);
    }

    @Nullable
    public DocumentFile rootForGrant(@NonNull String grantId) {
        String uriStr = prefs.getFolderUri(grantId);
        if (uriStr.isEmpty()) return null;
        DocumentFile root = DocumentFile.fromTreeUri(app, Uri.parse(uriStr));
        if (root == null || !root.exists() || !root.canRead()) return null;
        return root;
    }

    @NonNull
    public List<Map<String, Object>> listChildren(@NonNull String grantId, @Nullable String relativePath) {
        List<Map<String, Object>> out = new ArrayList<>();
        DocumentFile dir = resolveDir(grantId, relativePath);
        if (dir == null) return out;
        DocumentFile[] children = dir.listFiles();
        if (children == null) return out;
        int n = 0;
        for (DocumentFile child : children) {
            if (n++ >= 200) break;
            Map<String, Object> row = new HashMap<>();
            String name = child.getName() != null ? child.getName() : "";
            if (isUnsafeName(name)) continue;
            row.put("name", name);
            row.put("isDirectory", child.isDirectory());
            row.put("sizeBytes", child.length());
            row.put("mimeType", child.getType() != null ? child.getType() : "");
            row.put("lastModified", child.lastModified());
            String childPath = joinPath(relativePath, name);
            String parent = normalizeRelative(relativePath);
            row.put("documentId", hash(grantId + ":" + childPath));
            row.put("relativePath", childPath);
            row.put("parentRelativePath", parent);
            row.put("folderGrantId", grantId);
            out.add(row);
        }
        return out;
    }

    @Nullable
    public DocumentFile resolveDir(@NonNull String grantId, @Nullable String relativePath) {
        DocumentFile root = rootForGrant(grantId);
        if (root == null) return null;
        if (relativePath == null || relativePath.trim().isEmpty() || ".".equals(relativePath)) {
            return root;
        }
        return walk(root, relativePath, true);
    }

    @Nullable
    public DocumentFile resolveFile(@NonNull String grantId, @NonNull String relativePath) {
        DocumentFile root = rootForGrant(grantId);
        if (root == null) return null;
        return walk(root, relativePath, false);
    }

    @Nullable
    private DocumentFile walk(@NonNull DocumentFile root, @NonNull String relativePath, boolean preferDir) {
        String cleaned = normalizeRelative(relativePath);
        if (cleaned == null) return null;
        if (cleaned.isEmpty()) return preferDir ? root : null;
        String[] parts = cleaned.split("/");
        DocumentFile cur = root;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (isUnsafeName(part)) return null;
            DocumentFile next = cur.findFile(part);
            if (next == null) return null;
            boolean last = i == parts.length - 1;
            if (!last && !next.isDirectory()) return null;
            if (last && preferDir && !next.isDirectory()) return null;
            if (last && !preferDir && next.isDirectory()) return null;
            cur = next;
        }
        return cur;
    }

    @Nullable
    public static String normalizeRelative(@Nullable String relativePath) {
        if (relativePath == null) return "";
        String p = relativePath.replace('\\', '/').trim();
        while (p.startsWith("/")) p = p.substring(1);
        if (p.contains("..") || p.contains("//")) return null;
        return p;
    }

    public static boolean isUnsafeName(@Nullable String name) {
        if (name == null || name.isEmpty()) return true;
        if (".".equals(name) || "..".equals(name)) return true;
        if (name.contains("/") || name.contains("\\") || name.contains("\0")) return true;
        return false;
    }

    @NonNull
    public static String joinPath(@Nullable String base, @NonNull String name) {
        String b = base == null ? "" : base.trim();
        if (b.isEmpty() || ".".equals(b)) return name;
        return b.endsWith("/") ? b + name : b + "/" + name;
    }

    public boolean createFolder(@NonNull String grantId, @Nullable String parentPath, @NonNull String name) {
        if (isUnsafeName(name)) return false;
        DocumentFile parent = resolveDir(grantId, parentPath);
        if (parent == null || !parent.canWrite()) return false;
        DocumentFile created = parent.createDirectory(name);
        return created != null && created.exists();
    }

    public boolean rename(@NonNull String grantId, @NonNull String relativePath, @NonNull String newName) {
        if (isUnsafeName(newName)) return false;
        DocumentFile file = resolveFile(grantId, relativePath);
        if (file == null) {
            file = resolveDir(grantId, relativePath);
        }
        if (file == null || !file.canWrite()) return false;
        return file.renameTo(newName);
    }

    public boolean delete(@NonNull String grantId, @NonNull String relativePath) {
        DocumentFile file = resolveFile(grantId, relativePath);
        if (file == null) file = resolveDir(grantId, relativePath);
        if (file == null || !file.canWrite()) return false;
        return file.delete();
    }

    public boolean copyWithinTree(@NonNull String grantId,
                                  @NonNull String fromPath,
                                  @NonNull String toDirPath,
                                  @NonNull String newName) {
        if (isUnsafeName(newName)) return false;
        DocumentFile src = resolveFile(grantId, fromPath);
        DocumentFile destDir = resolveDir(grantId, toDirPath);
        if (src == null || destDir == null || !destDir.canWrite()) return false;
        String mime = src.getType() != null ? src.getType() : "application/octet-stream";
        DocumentFile dest = destDir.createFile(mime, newName);
        if (dest == null) return false;
        try (InputStream in = app.getContentResolver().openInputStream(src.getUri());
             OutputStream out = app.getContentResolver().openOutputStream(dest.getUri())) {
            if (in == null || out == null) return false;
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) >= 0) out.write(buf, 0, r);
            out.flush();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "copy failed", e);
            return false;
        }
    }

    @NonNull
    public static String hash(@NonNull String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : dig) sb.append(String.format(Locale.US, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    @NonNull
    public static String uriHash(@NonNull String uri) {
        return hash(uri);
    }
}
