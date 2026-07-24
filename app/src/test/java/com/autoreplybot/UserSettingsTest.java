package com.autoreplybot;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UserSettingsTest {
    @Test public void defaultsAutoReplyToNormalDirectMessages() {
        UserSettings settings = new UserSettings();
        assertTrue(settings.isMasterEnabled());
        assertTrue(settings.isBusinessAutoReplyEnabled());
        assertTrue(settings.isFriendAutoReplyEnabled());
        assertTrue(settings.isFamilyAutoReplyEnabled());
        assertFalse(settings.isGroupAutoReplyEnabled());
        assertTrue(settings.isNeverShareLiveLocation());
        assertTrue(settings.isNeverShareHomeAddress());
        assertTrue(settings.isNeverMakeFinancialCommitments());
        assertTrue(settings.isNeverConfirmMeetingsAutomatically());
        assertTrue(settings.isNeverRevealContactConversations());
        assertTrue(settings.isIgnoreCompanyMessages());
        assertTrue(settings.isIgnorePromotionalMessages());
        assertTrue(settings.isIgnoreBankMessages());
        assertTrue(settings.isIgnoreOtpMessages());
        assertTrue(settings.isIgnoreTransactionAlerts());
        assertTrue(settings.isIgnoreDeliveryUpdates());
        assertTrue(settings.isIgnoreAutomatedMessages());
        assertTrue(settings.isIgnoreVerifiedBusinessBroadcasts());
        assertEquals(0.80d, settings.getMinimumAutoReplyConfidence(), 0d);
        assertEquals(0.60d, settings.getMinimumClarificationConfidence(), 0d);
    }

    @Test public void firestoreRoundTripPreservesSettings() {
        UserSettings original = new UserSettings();
        original.setPackageEnabled("com.example.chat", true);
        original.setGroupAutoReplyEnabled(true);
        original.setFriendAutoReplyEnabled(true);
        original.setMinimumAutoReplyConfidence(0.92d);
        original.setMinimumClarificationConfidence(0.71d);
        original.setInstructionsForPackage("com.example.chat", "Only verified facts");
        original.setIgnorePromotionalMessages(false);

        UserSettings restored = UserSettings.fromFirestoreMap(original.toFirestoreMap());

        assertTrue(restored.isPackageEnabled("com.example.chat"));
        assertTrue(restored.isGroupAutoReplyEnabled());
        assertTrue(restored.isFriendAutoReplyEnabled());
        assertEquals(0.92d, restored.getMinimumAutoReplyConfidence(), 0d);
        assertEquals(0.71d, restored.getMinimumClarificationConfidence(), 0d);
        assertEquals("Only verified facts",
                restored.getInstructionsForPackage("com.example.chat"));
        assertFalse(restored.isIgnorePromotionalMessages());
    }

    @Test public void legacyWhatsappToggleMigratesBothPackages() {
        Map<String, Object> map = new HashMap<>();
        map.put(AppConstants.KEY_WHATSAPP_ENABLED, true);
        UserSettings restored = UserSettings.fromFirestoreMap(map);
        assertTrue(restored.isPackageEnabled(AppConstants.PKG_WHATSAPP));
        assertTrue(restored.isPackageEnabled(AppConstants.PKG_WHATSAPP_BUSINESS));
    }

    @Test public void versionOneCategoryDefaultsMigrateToAutoReply() {
        Map<String, Object> map = new HashMap<>();
        map.put(AppConstants.KEY_FRIEND_AUTO_REPLY_ENABLED, false);
        map.put(AppConstants.KEY_FAMILY_AUTO_REPLY_ENABLED, false);
        UserSettings restored = UserSettings.fromFirestoreMap(map);
        assertTrue(restored.isFriendAutoReplyEnabled());
        assertTrue(restored.isFamilyAutoReplyEnabled());
    }

    @Test public void versionTwoExplicitCategoryChoicesArePreserved() {
        Map<String, Object> map = new HashMap<>();
        map.put(AppConstants.KEY_REPLY_POLICY_VERSION, 2);
        map.put(AppConstants.KEY_FRIEND_AUTO_REPLY_ENABLED, false);
        map.put(AppConstants.KEY_FAMILY_AUTO_REPLY_ENABLED, false);
        UserSettings restored = UserSettings.fromFirestoreMap(map);
        assertFalse(restored.isFriendAutoReplyEnabled());
        assertFalse(restored.isFamilyAutoReplyEnabled());
    }

    @Test public void legacyDualPackagesMigrateWhenEnabled() {
        Map<String, Object> map = new HashMap<>();
        map.put(AppConstants.KEY_DUAL_EXTRA_ENABLED, true);
        map.put(AppConstants.KEY_DUAL_EXTRA_PACKAGES, "com.clone.one, com.clone.two");
        UserSettings restored = UserSettings.fromFirestoreMap(map);
        assertTrue(restored.isPackageEnabled("com.clone.one"));
        assertTrue(restored.isPackageEnabled("com.clone.two"));
    }

    @Test public void modernPackageMapWinsOverLegacyToggles() {
        Map<String, Object> map = new HashMap<>();
        Map<String, Boolean> packages = new HashMap<>();
        packages.put("com.modern.chat", true);
        map.put(AppConstants.KEY_ENABLED_PACKAGES, packages);
        map.put(AppConstants.KEY_WHATSAPP_ENABLED, true);
        UserSettings restored = UserSettings.fromFirestoreMap(map);
        assertTrue(restored.isPackageEnabled("com.modern.chat"));
        assertFalse(restored.isPackageEnabled(AppConstants.PKG_WHATSAPP));
    }

    @Test public void legacyInstructionsMigrateToScopedPackages() {
        Map<String, Object> map = new HashMap<>();
        map.put(AppConstants.KEY_BUSINESS_INSTRUCTIONS, "Use published prices");
        UserSettings restored = UserSettings.fromFirestoreMap(map);
        assertEquals("Use published prices",
                restored.getInstructionsForPackage(AppConstants.PKG_WHATSAPP));
        assertEquals("Use published prices",
                restored.getInstructionsForPackage(AppConstants.PKG_WHATSAPP_BUSINESS));
    }

    @Test public void invalidThresholdsFallBackToDefaults() {
        UserSettings settings = new UserSettings();
        settings.setMinimumAutoReplyConfidence(1.1d);
        settings.setMinimumClarificationConfidence(Double.NaN);
        settings.setMaxRecentMessages(0);
        settings.setMaxRecentRepliesForSimilarity(21);
        settings.setDuplicateWindowSeconds(-1);
        settings.setContactCooldownSeconds(-1);

        assertEquals(0.80d, settings.getMinimumAutoReplyConfidence(), 0d);
        assertEquals(0.60d, settings.getMinimumClarificationConfidence(), 0d);
        assertEquals(12, settings.getMaxRecentMessages());
        assertEquals(5, settings.getMaxRecentRepliesForSimilarity());
        assertEquals(300, settings.getDuplicateWindowSeconds());
        assertEquals(8, settings.getContactCooldownSeconds());
    }

    @Test public void boundaryThresholdsAreAccepted() {
        UserSettings settings = new UserSettings();
        settings.setMinimumAutoReplyConfidence(1d);
        settings.setMinimumClarificationConfidence(0d);
        settings.setMaxRecentMessages(50);
        settings.setMaxRecentRepliesForSimilarity(20);
        assertEquals(1d, settings.getMinimumAutoReplyConfidence(), 0d);
        assertEquals(0d, settings.getMinimumClarificationConfidence(), 0d);
        assertEquals(50, settings.getMaxRecentMessages());
        assertEquals(20, settings.getMaxRecentRepliesForSimilarity());
    }
}
