package com.autoreplybot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.autoreplybot.remote.RemoteNotificationListenerBridge;
import com.autoreplybot.remote.RemoteNotificationMirror;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Notification ingestion, same-conversation batching, AI drafting, local validation, and dispatch.
 * All network/repository waits run on the service's worker; only RemoteInput dispatch touches main.
 */
public class NotificationRelayService extends NotificationListenerService
        implements RemoteNotificationListenerBridge.ActiveProvider {
    private static final String TAG = "NotificationRelay";
    private static final long MESSAGE_BATCH_WINDOW_MS = 1200L;
    private static final long DEFER_WHILE_IN_FLIGHT_MS = 1800L;
    private static final long REPOSITORY_TIMEOUT_SECONDS = 10L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AiGateway aiGateway = new OpenAiClient();
    private final MessageIntentClassifier classifier = new MessageIntentClassifier(aiGateway);
    private final ReplyGenerator generator = new ReplyGenerator(aiGateway);
    private final PromptContextBuilder contextBuilder = new PromptContextBuilder();
    private final ReplyRepetitionChecker repetitionChecker = new ReplyRepetitionChecker();
    private final PrivacySafetyChecker safetyChecker = new PrivacySafetyChecker();
    private final AutoReplyDecisionEngine decisionEngine = new AutoReplyDecisionEngine();
    private final Map<String, PendingConversation> pendingByConversation =
            new ConcurrentHashMap<>();
    private final Map<String, Boolean> inFlightByConversation = new ConcurrentHashMap<>();
    private volatile boolean destroyed;
    private NotificationDeduplicator deduplicator;

    private static final class PendingConversation {
        final List<String> messages = new ArrayList<>();
        StatusBarNotification latestSbn;
        ParsedNotification latestParsed;
        Runnable debounceRunnable;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        deduplicator = new NotificationDeduplicator(this);
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
    RemoteNotificationListenerBridge.bind(this);
    try {
        com.autoreplybot.remote.RemoteNotificationMirror.ensureSharingEnabledIfListenerReady(this);
    } catch (Exception ignored) {
        // best-effort
    }
    Log.i(TAG, "Notification listener connected");
}

    @Override
    public void onDestroy() {
        destroyed = true;
        RemoteNotificationListenerBridge.unbind(this);
        mainHandler.removeCallbacksAndMessages(null);
        pendingByConversation.clear();
        inFlightByConversation.clear();
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    @Nullable
    public StatusBarNotification[] getActiveNotificationsSafe() {
        try {
            return getActiveNotifications();
        } catch (Exception e) {
            Log.w(TAG, "getActiveNotifications failed", e);
            return null;
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (destroyed || sbn == null || sbn.getNotification() == null) return;
        // Remote dashboard mirror (independent of Auto Reply filters).
        RemoteNotificationMirror.onPosted(this, sbn);
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            Log.w(TAG, "Skip: Firebase user not signed in");
            return;
        }

        SettingsRepository settingsRepository = new SettingsRepository(this);
        UserSettings settings = settingsRepository.readCached();
        ParsedNotification parsed = NotificationParser.parse(sbn);
        if (!isStillAllowed(parsed, settings)) return;
        long duplicateWindowMs = secondsToMs(settings.getDuplicateWindowSeconds());
        if (deduplicator.isKnownReply(parsed, duplicateWindowMs)) {
            Log.d(TAG, "Skip notification matching a persisted reply hash");
            return;
        }
        if (deduplicator.isDuplicateAndRecord(parsed, duplicateWindowMs)) {
            new MetricsRepository(this).incrementToday(MetricsRepository.Metric.DUPLICATES_BLOCKED);
            Log.d(TAG, "Skip duplicate notification refresh");
            return;
        }
        final String conversationKey = conversationKey(parsed);
        pendingByConversation.compute(conversationKey, (key, existing) -> {
            PendingConversation pending = existing != null ? existing : new PendingConversation();
            String current = parsed.getMessageText().trim();
            if (pending.messages.isEmpty() || !NotificationContentExtractor.normalizeText(
                    pending.messages.get(pending.messages.size() - 1)).equals(
                    NotificationContentExtractor.normalizeText(current))) {
                pending.messages.add(current);
            }
            pending.latestSbn = sbn;
            pending.latestParsed = parsed;
            rescheduleDebounce(key, pending);
            return pending;
        });
    }

    private void rescheduleDebounce(@NonNull String conversationKey,
                                    @NonNull PendingConversation pending) {
        if (destroyed) return;
        if (pending.debounceRunnable != null) {
            mainHandler.removeCallbacks(pending.debounceRunnable);
        }
        pending.debounceRunnable = () -> onBatchQuietPeriodEnd(conversationKey);
        mainHandler.postDelayed(pending.debounceRunnable, MESSAGE_BATCH_WINDOW_MS);
    }

    private void onBatchQuietPeriodEnd(@NonNull String conversationKey) {
        if (destroyed) return;
        if (inFlightByConversation.containsKey(conversationKey)) {
            postFlush(conversationKey, DEFER_WHILE_IN_FLIGHT_MS);
            return;
        }
        PendingConversation current = pendingByConversation.get(conversationKey);
        if (current == null || current.latestParsed == null) return;

        SettingsRepository settingsRepository = new SettingsRepository(this);
        UserSettings settings = settingsRepository.readCached();
        if (!isStillAllowed(current.latestParsed, settings)) {
            pendingByConversation.remove(conversationKey, current);
            return;
        }
        long cooldownWait = deduplicator.remainingContactCooldownMs(current.latestParsed,
                secondsToMs(settings.getContactCooldownSeconds()));
        if (cooldownWait > 0L) {
            postFlush(conversationKey, cooldownWait);
            return;
        }

        AtomicReference<PendingConversation> detached = new AtomicReference<>();
        pendingByConversation.computeIfPresent(conversationKey, (key, pending) -> {
            if (!pending.messages.isEmpty() && pending.latestSbn != null
                    && pending.latestParsed != null) detached.set(pending);
            return null;
        });
        PendingConversation batch = detached.get();
        if (batch == null) return;
        if (batch.debounceRunnable != null) {
            mainHandler.removeCallbacks(batch.debounceRunnable);
        }

        NotificationReplyHelper.ReplyPayload payload =
                NotificationReplyHelper.extractReplyPayload(batch.latestSbn.getNotification());
        String configuredApiKey = settingsRepository.getOpenAiApiKey();
        final String apiKey = configuredApiKey != null ? configuredApiKey : "";
        if (payload == null || !isStillAllowed(batch.latestParsed, settings)) return;

        long duplicateWindowMs = secondsToMs(settings.getDuplicateWindowSeconds());
        String transitionId = deduplicator.claimIncomingTransition(
                batch.latestParsed, duplicateWindowMs);
        if (transitionId == null) {
            new MetricsRepository(this).incrementToday(MetricsRepository.Metric.DUPLICATES_BLOCKED);
            return;
        }

        List<String> snapshot = new ArrayList<>(batch.messages);
        String incoming = TextUtils.join("\n", snapshot);
        inFlightByConversation.put(conversationKey, true);
        try {
            executor.execute(() -> processBatch(settings, apiKey, incoming, payload,
                    batch.latestParsed, conversationKey, transitionId, snapshot.size()));
        } catch (RejectedExecutionException rejected) {
            inFlightByConversation.remove(conversationKey);
            if (!destroyed) Log.w(TAG, "Reply worker rejected claimed transition");
        }
    }

    private void processBatch(@NonNull UserSettings initialSettings,
                              @NonNull String apiKey,
                              @NonNull String incoming,
                              @NonNull NotificationReplyHelper.ReplyPayload payload,
                              @NonNull ParsedNotification parsed,
                              @NonNull String conversationKey,
                              @NonNull String transitionId,
                              int batchCount) {
        if (destroyed || FirebaseAuth.getInstance().getCurrentUser() == null) {
            inFlightByConversation.remove(conversationKey);
            return;
        }

        ContactProfileRepository contactRepository = new ContactProfileRepository(this);
        ConversationRepository conversationRepository = new ConversationRepository(this);
        ReplyHistoryRepository historyRepository = new ReplyHistoryRepository(this);
        PendingApprovalRepository approvalRepository = new PendingApprovalRepository(this);
        MetricsRepository metricsRepository = new MetricsRepository(this);
        String contactId = null;
        try {
            contactId = contactRepository.createContactId(parsed.getPackageName(),
                    parsed.getStableContactIdentity());
            ContactProfile profile = await(contactRepository.getOrCreateUnknown(
                    contactId, contactLabel(parsed), initialSettings.getDefaultReplyMode()));

            boolean savedContact = isSavedContact(contactLabel(parsed));
            CompanyMessageDetector.DetectionResult companyDetection =
                    new CompanyMessageDetector().detect(new CompanyMessageDetector.Evidence(
                            parsed.getSenderName(), incoming, parsed.getConversationTitle(),
                            savedContact, parsed.isGroupConversation(),
                            hasBroadcastMetadata(parsed), parsed.isVerifiedBusiness(), profile));
            if (companyDetection.companyMessage) {
                metricsRepository.incrementToday(MetricsRepository.Metric.PROCESSED_MESSAGES);
                // Never queue company/automated messages for approval — skip silently.
                persistCompanyBlock(companyDetection, contactId, transitionId,
                        historyRepository, metricsRepository);
                inFlightByConversation.remove(conversationKey);
                return;
            }

            if (TextUtils.isEmpty(apiKey)) {
                inFlightByConversation.remove(conversationKey);
                return;
            }

            Task<ConversationState> stateTask = conversationRepository.loadState(contactId);
            Task<List<ConversationMessage>> messagesTask =
                    conversationRepository.loadRecentMessages(contactId,
                            initialSettings.getMaxRecentMessages());
            Task<List<ReplyEvent>> historyTask = historyRepository.loadForContact(contactId,
                    initialSettings.getMaxRecentRepliesForSimilarity());
            ConversationState state = await(stateTask);
            List<ConversationMessage> messages = await(messagesTask);
            List<ReplyEvent> history = await(historyTask);
            metricsRepository.incrementToday(MetricsRepository.Metric.PROCESSED_MESSAGES);

            MessageClassificationResult classification = classifier.classify(apiKey,
                    contextBuilder.buildClassifierContext(incoming,
                            parsed.isGroupConversation()));
            ReplyAction preAction = safetyChecker.preGenerationAction(
                    incoming, classification, profile);
            if (preAction != ReplyAction.SEND_REPLY) {
                persistNonSend(preAction, "", classification,
                        preAction == ReplyAction.NO_REPLY ? "LOCAL_PRECHECK_NO_REPLY"
                                : classification.reasonCode,
                        incoming, contactId, transitionId, conversationRepository,
                        historyRepository, approvalRepository, metricsRepository, payload);
                inFlightByConversation.remove(conversationKey);
                return;
            }

            String promptContext = contextBuilder.buildGeneratorContext(profile, state, messages,
                    history, classification, initialSettings, parsed.getPackageName(), incoming);
            GeneratedReply generated = generator.generate(apiKey, promptContext);
            List<String> recentReplies = contextBuilder.recentAssistantReplies(
                    contactId, messages, history);
            boolean repeated = !generated.reply.isEmpty()
                    && repetitionChecker.isRepeated(generated.reply, recentReplies);
            if (repeated && generated.valid) {
                generated = generator.generateAlternative(apiKey, promptContext, generated.reply);
                repeated = !generated.reply.isEmpty()
                        && repetitionChecker.isRepeated(generated.reply, recentReplies);
            }

            UserSettings latestSettings = new SettingsRepository(this).readCached();
            SafetyClassificationResult safety = safetyChecker.validate(incoming, generated,
                    classification, profile, latestSettings, repeated);
            ReplyAction action = decisionEngine.decide(classification, generated, safety,
                    profile, latestSettings);

            if (action != ReplyAction.SEND_REPLY
                    || destroyed || !isStillAllowed(parsed, latestSettings)) {
                ReplyAction persistedAction = action == ReplyAction.SEND_REPLY
                        ? ReplyAction.NO_REPLY : action;
                persistNonSend(persistedAction, generated.reply, classification,
                        safety.reasonCode, incoming, contactId, transitionId,
                        conversationRepository, historyRepository, approvalRepository,
                        metricsRepository, payload);
                inFlightByConversation.remove(conversationKey);
                return;
            }

            saveIncoming(conversationRepository, contactId, transitionId, incoming,
                    classification, "dispatching", false, generated.reply);
            awaitQuiet(historyRepository.record(new ReplyEvent(transitionId, contactId, incoming,
                    generated.reply, ReplyAction.NO_REPLY, classification.intent,
                    classification.confidence, "DISPATCH_PENDING", "not_required",
                    System.currentTimeMillis())));
            dispatchOnMain(payload, generated.reply, parsed, conversationKey, transitionId,
                    contactId, incoming, classification, state, batchCount, latestSettings);
        } catch (Exception error) {
            if (contactId != null) {
                MessageClassificationResult failed =
                        MessageClassificationResult.safeFallback("PIPELINE_FAILURE");
                persistNonSend(ReplyAction.NO_REPLY, "", failed, "PIPELINE_FAILURE",
                        incoming, contactId, transitionId, conversationRepository,
                        historyRepository, approvalRepository, metricsRepository, payload);
            }
            inFlightByConversation.remove(conversationKey);
            if (!destroyed) Log.e(TAG, "Decision pipeline failed closed");
        }
    }

    private void dispatchOnMain(@NonNull NotificationReplyHelper.ReplyPayload payload,
                                @NonNull String reply,
                                @NonNull ParsedNotification parsed,
                                @NonNull String conversationKey,
                                @NonNull String transitionId,
                                @NonNull String contactId,
                                @NonNull String incoming,
                                @NonNull MessageClassificationResult classification,
                                @NonNull ConversationState priorState,
                                int batchCount,
                                @NonNull UserSettings settings) {
        String reservation = deduplicator.recordReply(parsed.getPackageName(),
                parsed.getStableContactIdentity(), reply,
                secondsToMs(settings.getDuplicateWindowSeconds()));
        boolean posted = mainHandler.post(() -> {
            if (destroyed) {
                deduplicator.removeReplyReservation(reservation);
                inFlightByConversation.remove(conversationKey);
                return;
            }
            boolean sent = NotificationReplyHelper.sendReplyWithPayload(
                    getApplicationContext(), payload, reply);
            if (sent) deduplicator.recordSuccessfulReply(parsed);
            try {
                executor.execute(() -> finishDispatch(sent, reply, parsed, conversationKey,
                        transitionId, contactId, incoming, classification, priorState,
                        batchCount, reservation));
            } catch (RejectedExecutionException rejected) {
                if (!sent) deduplicator.removeReplyReservation(reservation);
                inFlightByConversation.remove(conversationKey);
            }
        });
        if (!posted) {
            finishDispatch(false, reply, parsed, conversationKey, transitionId, contactId,
                    incoming, classification, priorState, batchCount, reservation);
        }
    }

    private void finishDispatch(boolean sent, @NonNull String reply,
                                @NonNull ParsedNotification parsed,
                                @NonNull String conversationKey,
                                @NonNull String transitionId,
                                @NonNull String contactId,
                                @NonNull String incoming,
                                @NonNull MessageClassificationResult classification,
                                @NonNull ConversationState priorState,
                                int batchCount, @NonNull String reservation) {
        ConversationRepository conversations = new ConversationRepository(this);
        ReplyHistoryRepository history = new ReplyHistoryRepository(this);
        MetricsRepository metrics = new MetricsRepository(this);
        long now = System.currentTimeMillis();
        if (!sent) {
            deduplicator.removeReplyReservation(reservation);
            persistNonSend(ReplyAction.NO_REPLY, "", classification, "DISPATCH_FAILED",
                    incoming, contactId, transitionId, conversations, history,
                    new PendingApprovalRepository(this), metrics, null);
            inFlightByConversation.remove(conversationKey);
            return;
        }

        ConversationMessage outgoing = new ConversationMessage(transitionId + "o", contactId,
                ConversationMessage.Direction.OUTGOING, reply, now, classification.intent,
                classification.language, reply, transitionId, "sent", false);
        awaitQuiet(conversations.saveMessage(outgoing));
        ConversationState updated = new ConversationState(contactId, classification.intent,
                priorState.conversationSummary, incoming, reply, now,
                priorState.consecutiveBotReplies + 1, true, now);
        awaitQuiet(conversations.saveState(updated));
        ReplyEvent event = new ReplyEvent(transitionId, contactId, incoming, reply,
                ReplyAction.SEND_REPLY, classification.intent, classification.confidence,
                "AUTO_SENT", "not_required", now);
        awaitQuiet(history.record(event));
        metrics.incrementToday(MetricsRepository.Metric.AUTO_SENT_REPLIES);
        inFlightByConversation.remove(conversationKey);
        Log.d(TAG, "Validated auto-reply sent (batched n=" + batchCount + ")");
    }

    private void persistNonSend(@NonNull ReplyAction action, @NonNull String suggestion,
                                @NonNull MessageClassificationResult classification,
                                @NonNull String reasonCode, @NonNull String incoming,
                                @NonNull String contactId, @NonNull String transitionId,
                                @NonNull ConversationRepository conversations,
                                @NonNull ReplyHistoryRepository history,
                                @NonNull PendingApprovalRepository approvals,
                                @NonNull MetricsRepository metrics,
                                @Nullable NotificationReplyHelper.ReplyPayload payload) {
        // Approval queue disabled — never create pendingApprovals or approval notifications.
        ReplyAction recorded = action == ReplyAction.REQUIRE_APPROVAL
                ? ReplyAction.NO_REPLY : action;
        saveIncoming(conversations, contactId, transitionId, incoming, classification,
                "no_reply", false, suggestion);
        long now = System.currentTimeMillis();
        if (classification.sensitive) {
            metrics.incrementToday(MetricsRepository.Metric.SENSITIVE_BLOCKED);
        }
        ReplyEvent event = new ReplyEvent(transitionId, contactId, incoming, suggestion,
                recorded, classification.intent, classification.confidence, reasonCode,
                "not_required", now);
        awaitQuiet(history.record(event));
    }

    private void persistCompanyBlock(
            @NonNull CompanyMessageDetector.DetectionResult detection,
            @NonNull String contactId,
            @NonNull String transitionId, @NonNull ReplyHistoryRepository history,
            @NonNull MetricsRepository metrics) {
        long now = System.currentTimeMillis();
        // Never persist blocked payload text; category/intent are the auditable metadata.
        String safePreview = detection.sensitiveRedaction
                ? "[SENSITIVE CONTENT REDACTED]" : "[COMPANY MESSAGE CONTENT OMITTED]";
        ReplyEvent event = new ReplyEvent(transitionId, contactId, safePreview, "",
                ReplyAction.NO_REPLY, detection.inferredIntent, detection.confidence,
                "COMPANY_OR_AUTOMATED_MESSAGE", "not_required", now,
                detection.category, detection.sensitiveRedaction, now);
        awaitQuiet(history.record(event));
        if (detection.sensitiveRedaction) {
            metrics.incrementToday(MetricsRepository.Metric.SENSITIVE_BLOCKED);
        }
    }

    private boolean isSavedContact(@NonNull String label) {
        String digits = ContactMatcher.extractDigits(label);
        return digits != null ? ContactMatcher.isInContactsByPhone(this, digits)
                : ContactMatcher.isInContactsByName(this, label);
    }

    private static boolean hasBroadcastMetadata(@NonNull ParsedNotification parsed) {
        if (parsed.isBroadcastOrChannel()) return true;
        String metadata = (parsed.getConversationTitle() + " "
                + (parsed.getGroupName() == null ? "" : parsed.getGroupName()) + " "
                + parsed.getChannelId() + " " + parsed.getNotificationCategory())
                .toLowerCase(java.util.Locale.ROOT);
        return metadata.contains("broadcast") || metadata.contains("channel")
                || metadata.contains("community") || metadata.contains("catalog")
                || metadata.contains("marketing") || metadata.contains("newsletter");
    }

    private void saveIncoming(@NonNull ConversationRepository conversations,
                              @NonNull String contactId, @NonNull String transitionId,
                              @NonNull String incoming,
                              @NonNull MessageClassificationResult classification,
                              @NonNull String status, boolean requiresReview,
                              @NonNull String suggestedReply) {
        ConversationMessage message = new ConversationMessage(transitionId + "i", contactId,
                ConversationMessage.Direction.INCOMING, incoming, System.currentTimeMillis(),
                classification.intent, classification.language, suggestedReply, transitionId,
                status, requiresReview);
        awaitQuiet(conversations.saveMessage(message));
    }

    private void postFlush(@NonNull String conversationKey, long delayMs) {
        if (!destroyed) {
            mainHandler.postDelayed(() -> onBatchQuietPeriodEnd(conversationKey),
                    Math.max(1L, delayMs));
        }
    }

    private boolean isStillAllowed(@NonNull ParsedNotification parsed,
                                   @NonNull UserSettings settings) {
        return FirebaseAuth.getInstance().getCurrentUser() != null
                && settings.isMasterEnabled()
                && settings.isAutoReplyEnabledForPackage(parsed.getPackageName())
                && isEligible(parsed, settings)
                && ContactMatcher.shouldReply(this, settings.getContactFilter(),
                settings.getWhitelistNumbers(), contactLabel(parsed), parsed.getMessageText())
                == ContactMatcher.MatchResult.ALLOW;
    }

    private static boolean isEligible(@NonNull ParsedNotification parsed,
                                      @NonNull UserSettings settings) {
        return parsed.eligibilityAction(settings) == ReplyAction.SEND_REPLY;
    }

    @NonNull
    private static String contactLabel(@NonNull ParsedNotification parsed) {
        if (parsed.isGroupConversation() && !TextUtils.isEmpty(parsed.getGroupName())) {
            return parsed.getGroupName();
        }
        if (!parsed.getSenderName().isEmpty()) return parsed.getSenderName();
        return parsed.getConversationTitle();
    }

    @NonNull
    private static String conversationKey(@NonNull ParsedNotification parsed) {
        return parsed.getPackageName() + "|" + parsed.getStableContactIdentity();
    }

    private static long secondsToMs(int seconds) {
        return Math.max(0L, seconds) * 1000L;
    }

    @NonNull
    private static <T> T await(@NonNull Task<T> task) throws Exception {
        return Tasks.await(task, REPOSITORY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static void awaitQuiet(@NonNull Task<?> task) {
        try {
            Tasks.await(task, REPOSITORY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Repositories synchronously cache writes before attempting cloud persistence.
        }
    }
}
