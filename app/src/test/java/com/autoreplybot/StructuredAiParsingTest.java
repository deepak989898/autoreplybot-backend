package com.autoreplybot;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Queue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class StructuredAiParsingTest {
    @Test public void classifierParsesValidJson() {
        MessageClassificationResult parsed = MessageIntentClassifier.parse(
                "{\"intent\":\"general_question\",\"confidence\":0.91,\"language\":\"en\","
                        + "\"tone\":\"neutral\",\"isSensitive\":false,\"needsHumanReview\":false,"
                        + "\"businessContextRequired\":false,\"personalContextRequired\":false,"
                        + "\"canAutoReply\":true,\"reasonCode\":\"SAFE\"}");
        assertEquals(MessageIntent.GENERAL_QUESTION, parsed.intent);
        assertEquals(0.91d, parsed.confidence, 0.0001d);
        assertTrue(parsed.canAutoReply);
    }

    @Test public void classifierRejectsInvalidIntent() {
        assertNull(MessageIntentClassifier.parse(
                "{\"intent\":\"invented\",\"confidence\":0.9}"));
    }

    @Test public void companyClassificationMapRoundTripIsSafe() {
        MessageClassificationResult original = new MessageClassificationResult(
                MessageIntent.OTP_MESSAGE, 0.99, "hi", "neutral", true, true,
                false, false, false, "COMPANY_OR_AUTOMATED_MESSAGE",
                true, true, false, false, true, SenderCategory.OTP_SENDER,
                ReplyAction.NO_REPLY);
        MessageClassificationResult restored =
                MessageClassificationResult.fromMap(original.toMap());
        assertTrue(restored.isCompanyMessage);
        assertTrue(restored.shouldNeverReply);
        assertEquals(SenderCategory.OTP_SENDER, restored.senderCategory);
        assertEquals(ReplyAction.NO_REPLY, restored.modelAction);
    }

    @Test public void legacyClassificationMapUsesHumanSafeDefaults() {
        MessageClassificationResult restored = MessageClassificationResult.fromMap(
                Collections.<String, Object>singletonMap("intent", "GREETING"));
        assertFalse(restored.isCompanyMessage);
        assertEquals(SenderCategory.PERSONAL_HUMAN, restored.senderCategory);
        assertEquals(ReplyAction.NO_REPLY, restored.modelAction);
    }

    @Test public void classifierRejectsStringConfidence() {
        assertNull(MessageIntentClassifier.parse(
                "{\"intent\":\"GREETING\",\"confidence\":\"0.9\"}"));
    }

    @Test public void classifierRetriesInvalidJsonThenSucceeds() {
        SequenceGateway gateway = new SequenceGateway("not-json",
                "{\"intent\":\"GREETING\",\"confidence\":0.95,\"needsHumanReview\":false,"
                        + "\"canAutoReply\":true}");
        MessageClassificationResult result =
                new MessageIntentClassifier(gateway).classify("unused", "context");
        assertEquals(MessageIntent.GREETING, result.intent);
        assertEquals(2, gateway.calls);
    }

    @Test public void classifierInvalidTwiceFailsClosed() {
        SequenceGateway gateway = new SequenceGateway("{}", "[]");
        MessageClassificationResult result =
                new MessageIntentClassifier(gateway).classify("unused", "context");
        assertEquals(MessageIntent.UNKNOWN, result.intent);
        assertTrue(result.needsHumanReview);
        assertFalse(result.canAutoReply);
        assertEquals("CLASSIFIER_INVALID_TWICE", result.reasonCode);
    }

    @Test public void classifierTimeoutFailsClosedAfterRetry() {
        FailingGateway gateway = new FailingGateway();
        MessageClassificationResult result =
                new MessageIntentClassifier(gateway).classify("unused", "context");
        assertEquals(MessageIntent.UNKNOWN, result.intent);
        assertEquals(2, gateway.calls);
    }

    @Test public void generatorParsesValidJson() {
        GeneratedReply parsed = ReplyGenerator.parse(
                "{\"action\":\"SEND_REPLY\",\"reply\":\"Hello\",\"intent\":\"GREETING\","
                        + "\"confidence\":0.94,\"reasonCode\":\"SAFE\",\"safetyFlags\":[],"
                        + "\"private\":false,\"unverified\":false,\"repeated\":false}");
        assertEquals(ReplyAction.SEND_REPLY, parsed.action);
        assertEquals("Hello", parsed.reply);
        assertTrue(parsed.valid);
    }

    @Test public void generatorRejectsMissingSafetyFields() {
        assertNull(ReplyGenerator.parse(
                "{\"action\":\"SEND_REPLY\",\"reply\":\"Hello\",\"intent\":\"GREETING\","
                        + "\"confidence\":0.94}"));
    }

    @Test public void generatorRejectsEmptySendReply() {
        assertNull(ReplyGenerator.parse(
                "{\"action\":\"SEND_REPLY\",\"reply\":\"\",\"intent\":\"GREETING\","
                        + "\"confidence\":0.94,\"safetyFlags\":[],\"private\":false,"
                        + "\"unverified\":false,\"repeated\":false}"));
    }

    @Test public void generatorFailureReturnsApprovalResult() {
        FailingGateway gateway = new FailingGateway();
        GeneratedReply result = new ReplyGenerator(gateway).generate("unused", "context");
        assertEquals(ReplyAction.REQUIRE_APPROVAL, result.action);
        assertFalse(result.valid);
        assertTrue(result.containsUnverifiedClaim);
        assertEquals(2, gateway.calls);
    }

    @Test public void unavailableRepositoryFallbackCannotAutoSend() {
        MessageClassificationResult fallback =
                MessageClassificationResult.safeFallback("FIREBASE_UNAVAILABLE");
        GeneratedReply generated = GeneratedReply.failClosed("NO_CONTEXT");
        SafetyClassificationResult safety = new SafetyClassificationResult(
                ReplyAction.REQUIRE_APPROVAL, InformationClassification.PRIVATE_PERSONAL,
                Collections.singletonList("CONTEXT_UNAVAILABLE"), false, true, false,
                "FIREBASE_UNAVAILABLE");
        ContactProfile profile = ContactProfile.unknown("id", "Sender");

        assertEquals(ReplyAction.REQUIRE_APPROVAL,
                new AutoReplyDecisionEngine().decide(
                        fallback, generated, safety, profile, new UserSettings()));
    }

    private static final class SequenceGateway implements AiGateway {
        final Queue<String> responses = new ArrayDeque<>();
        int calls;

        SequenceGateway(String... values) {
            Collections.addAll(responses, values);
        }

        @Override public String completeJson(String apiKey, String systemPrompt,
                                             String userContent, int maxTokens,
                                             double temperature) {
            calls++;
            return responses.remove();
        }
    }

    private static final class FailingGateway implements AiGateway {
        int calls;

        @Override public String completeJson(String apiKey, String systemPrompt,
                                             String userContent, int maxTokens,
                                             double temperature) throws IOException {
            calls++;
            throw new IOException("simulated timeout");
        }
    }
}
