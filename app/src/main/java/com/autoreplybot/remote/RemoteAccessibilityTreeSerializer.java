package com.autoreplybot.remote;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Builds a bounded, redacted accessibility tree snapshot for remote clients. */
public final class RemoteAccessibilityTreeSerializer {
    public static final int MAX_NODES = 120;

    private static final AtomicReference<Map<String, int[]>> LAST_BOUNDS =
            new AtomicReference<>(new ConcurrentHashMap<>());

    private final long snapshotVersion;
    private final AtomicInteger idSeq = new AtomicInteger(1);
    private int nodeCount;
    private final Map<String, int[]> boundsIndex = new ConcurrentHashMap<>();

    public RemoteAccessibilityTreeSerializer(long snapshotVersion) {
        this.snapshotVersion = snapshotVersion;
    }

    @NonNull
    public Map<String, Object> serialize(@Nullable AccessibilityNodeInfo root,
                                         @Nullable String packageName,
                                         int screenWidthPx,
                                         int screenHeightPx) {
        nodeCount = 0;
        Map<String, Object> doc = new HashMap<>();
        doc.put("snapshotVersion", snapshotVersion);
        doc.put("packageName", packageName != null ? packageName : "");
        doc.put("screenWidthPx", screenWidthPx);
        doc.put("screenHeightPx", screenHeightPx);
        doc.put("capturedAt", System.currentTimeMillis());
        List<Map<String, Object>> nodes = new ArrayList<>();
        if (root != null && !RemoteAccessibilitySafetyPolicy.isScreenBlocked(root, packageName)) {
            collectNode(root, null, nodes);
        }
        doc.put("nodes", nodes);
        doc.put("nodeCount", nodes.size());
        doc.put("truncated", nodeCount >= MAX_NODES);
        LAST_BOUNDS.set(new ConcurrentHashMap<>(boundsIndex));
        return doc;
    }

    /**
     * Resolve a short-lived node id from the last published snapshot by bounds match.
     * Caller must recycle the returned node.
     */
    @Nullable
    public static AccessibilityNodeInfo findByEphemeralId(@NonNull AccessibilityNodeInfo root,
                                                          @NonNull String nodeId) {
        int[] b = LAST_BOUNDS.get().get(nodeId);
        if (b == null || b.length < 4) return null;
        return findByBounds(root, b[0], b[1], b[2], b[3]);
    }

    @Nullable
    private static AccessibilityNodeInfo findByBounds(@NonNull AccessibilityNodeInfo node,
                                                      int l, int t, int r, int bottom) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.left == l && bounds.top == t && bounds.right == r && bounds.bottom == bottom) {
            return AccessibilityNodeInfo.obtain(node);
        }
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                AccessibilityNodeInfo hit = findByBounds(child, l, t, r, bottom);
                if (hit != null) return hit;
            } finally {
                child.recycle();
            }
        }
        return null;
    }

    private void collectNode(@NonNull AccessibilityNodeInfo node,
                             @Nullable String parentId,
                             @NonNull List<Map<String, Object>> out) {
        if (nodeCount >= MAX_NODES) return;
        nodeCount++;

        String nodeId = ephemeralId();
        Map<String, Object> row = new HashMap<>();
        row.put("id", nodeId);
        row.put("snapshotVersion", snapshotVersion);
        if (parentId != null) row.put("parentId", parentId);

        CharSequence className = node.getClassName();
        row.put("className", className != null ? className.toString() : "");

        boolean sensitive = RemoteAccessibilitySafetyPolicy.shouldRedactNodeText(node);
        row.put("sensitive", sensitive);
        if (sensitive) {
            row.put("text", RemoteAccessibilitySafetyPolicy.redact(node.getText()));
            row.put("contentDescription", RemoteAccessibilitySafetyPolicy.redact(node.getContentDescription()));
            row.put("hint", RemoteAccessibilitySafetyPolicy.redact(node.getHintText()));
        } else {
            row.put("text", safeString(node.getText()));
            row.put("contentDescription", safeString(node.getContentDescription()));
            row.put("hint", safeString(node.getHintText()));
        }

        row.put("clickable", node.isClickable());
        row.put("longClickable", node.isLongClickable());
        row.put("scrollable", node.isScrollable());
        row.put("editable", node.isEditable());
        row.put("enabled", node.isEnabled());
        row.put("focused", node.isFocused());
        row.put("selected", node.isSelected());
        row.put("checkable", node.isCheckable());
        row.put("checked", node.isChecked());
        row.put("password", node.isPassword());

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        row.put("boundsLeft", bounds.left);
        row.put("boundsTop", bounds.top);
        row.put("boundsRight", bounds.right);
        row.put("boundsBottom", bounds.bottom);
        boundsIndex.put(nodeId, new int[]{bounds.left, bounds.top, bounds.right, bounds.bottom});

        out.add(row);

        int childCount = node.getChildCount();
        for (int i = 0; i < childCount && nodeCount < MAX_NODES; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                collectNode(child, nodeId, out);
            } finally {
                child.recycle();
            }
        }
    }

    @NonNull
    private String ephemeralId() {
        return "n" + snapshotVersion + "_" + idSeq.getAndIncrement();
    }

    @NonNull
    private static String safeString(@Nullable CharSequence value) {
        if (value == null) return "";
        String s = value.toString();
        return s.length() > 256 ? s.substring(0, 256) : s;
    }
}
