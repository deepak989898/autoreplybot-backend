package com.autoreplybot;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NotificationPureLogicTest {
    @Test public void exactRepetitionIgnoresGreetingCaseAndPunctuation() {
        assertTrue(new ReplyRepetitionChecker().isRepeated(
                "Hello, your booking is ready!", Collections.singletonList("YOUR BOOKING IS READY")));
    }

    @Test public void semanticRepetitionDetectsSmallRewrite() {
        assertTrue(new ReplyRepetitionChecker().isRepeated(
                "Your order will be ready tomorrow",
                Collections.singletonList("Your order should be ready tomorrow")));
    }

    @Test public void distinctReplyIsNotRepeated() {
        assertFalse(new ReplyRepetitionChecker().isRepeated(
                "We open at nine", Collections.singletonList("Thanks for contacting us")));
    }

    @Test public void emptyCandidateFailsClosedAsRepeated() {
        assertTrue(new ReplyRepetitionChecker().isRepeated("", Collections.emptyList()));
    }

    @Test public void normalizationCanonicalizesUnicodeAndGreeting() {
        assertEquals("thanks friend", ReplyRepetitionChecker.normalize("  Hii！ Thanks, friend. "));
    }

    @Test public void incomingDedupRemovesMarkupWhitespaceAndTrailingNoise() {
        assertEquals("Hello there", NotificationContentExtractor.normalizeForIncomingDedup(
                " *Hello*   _there_!!! "));
    }

    @Test public void duplicateHashMatchesCanonicalNotificationRefresh() {
        ParsedNotification first = parsed("Hello *there*!!!", ParsedNotification.Direction.INCOMING,
                false, false, false);
        ParsedNotification refreshed = parsed("Hello there", ParsedNotification.Direction.INCOMING,
                false, false, false);
        assertEquals(NotificationDeduplicator.notificationHash(first, 42L),
                NotificationDeduplicator.notificationHash(refreshed, 42L));
    }

    @Test public void groupDisabledProducesNoReply() {
        UserSettings settings = new UserSettings();
        assertEquals(ReplyAction.NO_REPLY,
                parsed("Hello", ParsedNotification.Direction.INCOMING, true, false, false)
                        .eligibilityAction(settings));
    }

    @Test public void groupEnabledCanSend() {
        UserSettings settings = new UserSettings();
        settings.setGroupAutoReplyEnabled(true);
        assertEquals(ReplyAction.SEND_REPLY,
                parsed("Hello", ParsedNotification.Direction.INCOMING, true, false, false)
                        .eligibilityAction(settings));
    }

    @Test public void emojiOnlyProducesNoReply() {
        assertEquals(ReplyAction.NO_REPLY,
                parsed("👍🏽 ❤️", ParsedNotification.Direction.INCOMING, false, false, false)
                        .eligibilityAction(new UserSettings()));
    }

    @Test public void mediaOnlyProducesNoReply() {
        assertEquals(ReplyAction.NO_REPLY,
                parsed("Photo", ParsedNotification.Direction.INCOMING, false, true, false)
                        .eligibilityAction(new UserSettings()));
    }

    @Test public void deletedMessageProducesNoReply() {
        assertEquals(ReplyAction.NO_REPLY,
                parsed("This message was deleted", ParsedNotification.Direction.INCOMING,
                        false, false, true).eligibilityAction(new UserSettings()));
    }

    @Test public void ownOutgoingReplyProducesNoReply() {
        assertEquals(ReplyAction.NO_REPLY,
                parsed("Our own reply", ParsedNotification.Direction.OUTGOING, false, false, false)
                        .eligibilityAction(new UserSettings()));
    }

    @Test public void absentReplyActionProducesNoReply() {
        ParsedNotification noAction = new ParsedNotification("pkg", "contact", "Sender", "Chat",
                "Hello", null, 1L, ParsedNotification.Direction.INCOMING, false, false,
                false, false, false, false, false, false);
        assertEquals(ReplyAction.NO_REPLY, noAction.eligibilityAction(new UserSettings()));
    }

    @Test public void meaningfulTextSupportsNonLatinScripts() {
        assertTrue(ParsedNotification.hasMeaningfulText("नमस्ते"));
        assertTrue(ParsedNotification.hasMeaningfulText("你好"));
        assertFalse(ParsedNotification.hasMeaningfulText(Arrays.asList("!", "❤️").toString()));
    }

    private static ParsedNotification parsed(String text, ParsedNotification.Direction direction,
                                             boolean group, boolean media, boolean deleted) {
        return new ParsedNotification("pkg", "contact", "Sender", "Chat", text,
                group ? "Group" : null, 1L, direction, false, group, false, media, deleted,
                false, false, true);
    }
}
