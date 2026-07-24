package com.autoreplybot;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;

public final class ContactProfile {
    @NonNull public final String contactId;
    @NonNull public final String phoneNumber;
    @NonNull public final String displayName;
    @NonNull public final RelationshipType relationshipType;
    public final boolean autoReplyEnabled;
    @NonNull public final ReplyMode replyMode;
    @NonNull public final String preferredLanguage;
    @NonNull public final String customNotes;
    public final boolean allowBusinessContext;
    public final boolean allowPersonalContext;
    public final boolean detectedAsCompany;
    @NonNull public final String companyName;
    public final boolean allowCompanyReplies;
    @NonNull public final CompanyReplyMode companyReplyMode;
    public final long createdAt;
    public final long updatedAt;

    public ContactProfile(@NonNull String contactId, @NonNull String phoneNumber,
                          @NonNull String displayName, @NonNull RelationshipType relationshipType,
                          boolean autoReplyEnabled, @NonNull ReplyMode replyMode,
                          @NonNull String preferredLanguage, @NonNull String customNotes,
                          boolean allowBusinessContext, boolean allowPersonalContext,
                          long createdAt, long updatedAt) {
        this(contactId, phoneNumber, displayName, relationshipType, autoReplyEnabled, replyMode,
                preferredLanguage, customNotes, allowBusinessContext, allowPersonalContext,
                false, "", false, CompanyReplyMode.NO_REPLY, createdAt, updatedAt);
    }

    public ContactProfile(@NonNull String contactId, @NonNull String phoneNumber,
                          @NonNull String displayName, @NonNull RelationshipType relationshipType,
                          boolean autoReplyEnabled, @NonNull ReplyMode replyMode,
                          @NonNull String preferredLanguage, @NonNull String customNotes,
                          boolean allowBusinessContext, boolean allowPersonalContext,
                          boolean detectedAsCompany, @NonNull String companyName,
                          boolean allowCompanyReplies, @NonNull CompanyReplyMode companyReplyMode,
                          long createdAt, long updatedAt) {
        this.contactId = contactId;
        this.phoneNumber = phoneNumber;
        this.displayName = displayName;
        this.relationshipType = relationshipType;
        this.autoReplyEnabled = autoReplyEnabled;
        this.replyMode = replyMode;
        this.preferredLanguage = preferredLanguage;
        this.customNotes = customNotes;
        this.allowBusinessContext = allowBusinessContext;
        this.allowPersonalContext = allowPersonalContext;
        this.detectedAsCompany = detectedAsCompany;
        this.companyName = companyName;
        this.allowCompanyReplies = allowCompanyReplies;
        this.companyReplyMode = companyReplyMode;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    @NonNull public static ContactProfile unknown(@NonNull String contactId,
                                                   @NonNull String displayName) {
        return unknown(contactId, displayName, ReplyMode.SMART);
    }

    @NonNull public static ContactProfile unknown(@NonNull String contactId,
                                                   @NonNull String displayName,
                                                   @NonNull ReplyMode defaultMode) {
        long now = System.currentTimeMillis();
        return new ContactProfile(contactId, "", displayName, RelationshipType.UNKNOWN,
                true, defaultMode, "auto", "", false, false, now, now);
    }

    @NonNull public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("contactId", contactId);
        map.put("phoneNumber", phoneNumber);
        map.put("displayName", displayName);
        map.put("relationshipType", relationshipType.name());
        map.put("autoReplyEnabled", autoReplyEnabled);
        map.put("replyMode", replyMode.name());
        map.put("preferredLanguage", preferredLanguage);
        map.put("customNotes", customNotes);
        map.put("allowBusinessContext", allowBusinessContext);
        map.put("allowPersonalContext", allowPersonalContext);
        map.put("detectedAsCompany", detectedAsCompany);
        map.put("companyName", companyName);
        map.put("allowCompanyReplies", allowCompanyReplies);
        map.put("companyReplyMode", companyReplyMode.name());
        map.put("createdAt", createdAt);
        map.put("updatedAt", updatedAt);
        return map;
    }

    @NonNull public static ContactProfile fromMap(@NonNull String expectedContactId,
                                                  @NonNull Map<String, Object> map) {
        return new ContactProfile(expectedContactId, ModelValues.string(map, "phoneNumber"),
                ModelValues.string(map, "displayName"),
                RelationshipType.fromValue(map.get("relationshipType")),
                ModelValues.bool(map, "autoReplyEnabled", true),
                ReplyMode.fromValue(map.get("replyMode")),
                ModelValues.string(map, "preferredLanguage"),
                ModelValues.string(map, "customNotes"),
                ModelValues.bool(map, "allowBusinessContext", false),
                ModelValues.bool(map, "allowPersonalContext", false),
                ModelValues.bool(map, "detectedAsCompany", false),
                ModelValues.string(map, "companyName"),
                ModelValues.bool(map, "allowCompanyReplies", false),
                CompanyReplyMode.fromValue(map.get("companyReplyMode")),
                ModelValues.longValue(map, "createdAt", 0L),
                ModelValues.longValue(map, "updatedAt", 0L));
    }
}
