package com.autoreplybot;

import android.Manifest;
import android.app.NotificationManager;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.TextInputEditText;

public class PendingApprovalsActivity extends AppCompatActivity {
    public static final String EXTRA_APPROVAL_ID = "approval_id";
    private static final int REQUEST_NOTIFICATIONS = 4201;

    private PendingApprovalRepository approvals;
    private PendingApprovalAdapter adapter;
    private TextView empty;
    private View progress;

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_pending_approvals);
        approvals = new PendingApprovalRepository(this);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        empty = findViewById(R.id.text_empty);
        progress = findViewById(R.id.progress);
        RecyclerView recycler = findViewById(R.id.recycler);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new PendingApprovalAdapter(new PendingApprovalAdapter.Listener() {
            @Override public void onEdit(@NonNull PendingApproval item) { showEditor(item); }
            @Override public void onSend(@NonNull PendingApproval item) { send(item); }
            @Override public void onIgnore(@NonNull PendingApproval item) { ignore(item); }
        });
        recycler.setAdapter(adapter);
    }

    @Override protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        progress.setVisibility(View.VISIBLE);
        approvals.loadPending(100).addOnCompleteListener(task -> {
            progress.setVisibility(View.GONE);
            if (!task.isSuccessful() || task.getResult() == null) {
                Toast.makeText(this, R.string.approval_load_failed, Toast.LENGTH_LONG).show();
                return;
            }
            adapter.submit(task.getResult());
            empty.setVisibility(task.getResult().isEmpty() ? View.VISIBLE : View.GONE);
        });
    }

    private void showEditor(@NonNull PendingApproval item) {
        View content = getLayoutInflater().inflate(R.layout.dialog_edit_reply, null);
        TextInputEditText input = content.findViewById(R.id.input_reply);
        input.setText(item.suggestedReply);
        new AlertDialog.Builder(this)
                .setTitle(R.string.approval_edit_title)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    String value = input.getText() == null ? "" : input.getText().toString().trim();
                    if (TextUtils.isEmpty(value)) {
                        Toast.makeText(this, R.string.approval_reply_required, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    approvals.updateSuggestion(item.approvalId, value)
                            .addOnSuccessListener(changed -> {
                                if (changed) adapter.replace(item.withSuggestedReply(
                                        value, System.currentTimeMillis()));
                            });
                }).show();
    }

    private void ignore(@NonNull PendingApproval item) {
        approvals.ignore(item.approvalId).addOnCompleteListener(task -> {
            if (task.isSuccessful() && Boolean.TRUE.equals(task.getResult())) {
                ApprovalReplyRegistry.remove(item.approvalId);
                adapter.remove(item.approvalId);
                updateEmpty();
                cancelNotification(item.approvalId);
            }
        });
    }

    private void send(@NonNull PendingApproval item) {
        if (TextUtils.isEmpty(item.suggestedReply)) {
            Toast.makeText(this, R.string.approval_reply_required, Toast.LENGTH_SHORT).show();
            showEditor(item);
            return;
        }
        approvals.claimForSend(item.approvalId).addOnCompleteListener(claim -> {
            if (!claim.isSuccessful() || !Boolean.TRUE.equals(claim.getResult())) {
                Toast.makeText(this, R.string.approval_already_handled, Toast.LENGTH_LONG).show();
                load();
                return;
            }
            NotificationReplyHelper.ReplyPayload payload =
                    ApprovalReplyRegistry.take(item.approvalId);
            if (payload == null) {
                approvals.markExpiredAfterClaim(item.approvalId);
                adapter.remove(item.approvalId);
                updateEmpty();
                Toast.makeText(this, R.string.approval_payload_expired, Toast.LENGTH_LONG).show();
                return;
            }
            NotificationReplyHelper.sendReplyWithPayloadMain(this, payload, item.suggestedReply,
                    sent -> finishSend(item, sent));
        });
    }

    private void finishSend(@NonNull PendingApproval item, boolean sent) {
        if (!sent) {
            approvals.markExpiredAfterClaim(item.approvalId);
            adapter.remove(item.approvalId);
            updateEmpty();
            Toast.makeText(this, R.string.approval_send_failed_expired, Toast.LENGTH_LONG).show();
            return;
        }
        long now = System.currentTimeMillis();
        approvals.markSent(item.approvalId).addOnCompleteListener(task -> {
            if (!task.isSuccessful() || !Boolean.TRUE.equals(task.getResult())) return;
            ConversationRepository conversations = new ConversationRepository(this);
            conversations.saveMessage(new ConversationMessage(item.approvalId + "o",
                    item.contactId, ConversationMessage.Direction.OUTGOING,
                    item.suggestedReply, now, item.intent, "", item.suggestedReply,
                    item.approvalId, "sent", false));
            conversations.saveMessage(new ConversationMessage(item.approvalId + "i",
                    item.contactId, ConversationMessage.Direction.INCOMING,
                    item.incomingMessage, item.createdAt, item.intent, "", item.suggestedReply,
                    item.approvalId, "approved_sent", false));
            conversations.loadState(item.contactId).addOnSuccessListener(previous ->
                    conversations.saveState(new ConversationState(item.contactId, item.intent,
                            previous.conversationSummary, item.incomingMessage,
                            item.suggestedReply, now, previous.consecutiveBotReplies + 1,
                            true, now)));
            new ReplyHistoryRepository(this).record(new ReplyEvent(item.approvalId,
                    item.contactId, item.incomingMessage, item.suggestedReply,
                    ReplyAction.SEND_REPLY, item.intent, 1d, "USER_APPROVED", "sent", now));
            new MetricsRepository(this).incrementToday(MetricsRepository.Metric.APPROVED_REPLIES);
            adapter.remove(item.approvalId);
            updateEmpty();
            cancelNotification(item.approvalId);
            Toast.makeText(this, R.string.approval_sent, Toast.LENGTH_SHORT).show();
        });
    }

    private void updateEmpty() {
        empty.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
    }

    private void cancelNotification(@NonNull String id) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(id.hashCode());
    }

}
