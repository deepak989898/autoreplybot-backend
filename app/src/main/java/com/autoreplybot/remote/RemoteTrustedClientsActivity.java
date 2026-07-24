package com.autoreplybot.remote;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.autoreplybot.R;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GetTokenResult;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RemoteTrustedClientsActivity extends AppCompatActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final RemotePairApi pairApi = new RemotePairApi();

    private View progress;
    private TextView empty;
    private RemoteTrustedClientsAdapter adapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            finish();
            return;
        }
        setContentView(R.layout.activity_remote_trusted_clients);

        ((MaterialToolbar) findViewById(R.id.toolbar))
                .setNavigationOnClickListener(v -> finish());
        progress = findViewById(R.id.progress);
        empty = findViewById(R.id.text_empty);
        RecyclerView recycler = findViewById(R.id.recycler);
        adapter = new RemoteTrustedClientsAdapter(this::confirmRevoke);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void reload() {
        if (!RemotePairApi.isBackendConfigured()) {
            Toast.makeText(this, R.string.remote_backend_url_missing, Toast.LENGTH_LONG).show();
            empty.setVisibility(View.VISIBLE);
            return;
        }
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            finish();
            return;
        }
        setBusy(true);
        user.getIdToken(false)
                .addOnSuccessListener(tokenResult ->
                        executor.execute(() -> runList(tokenResult)))
                .addOnFailureListener(error -> {
                    setBusy(false);
                    Toast.makeText(this,
                            getString(R.string.remote_error, error.getMessage()),
                            Toast.LENGTH_LONG).show();
                });
    }

    private void runList(@NonNull GetTokenResult tokenResult) {
        try {
            String idToken = tokenResult.getToken();
            if (TextUtils.isEmpty(idToken)) {
                throw new IllegalStateException("Empty Firebase ID token");
            }
            List<RemoteTrustedClient> clients = pairApi.listClients(idToken);
            mainHandler.post(() -> {
                setBusy(false);
                adapter.submit(clients);
                empty.setVisibility(clients.isEmpty() ? View.VISIBLE : View.GONE);
            });
        } catch (Exception error) {
            mainHandler.post(() -> {
                setBusy(false);
                empty.setVisibility(View.VISIBLE);
                Toast.makeText(this,
                        getString(R.string.remote_error,
                                error.getMessage() != null ? error.getMessage() : "unknown"),
                        Toast.LENGTH_LONG).show();
            });
        }
    }

    private void confirmRevoke(@NonNull RemoteTrustedClient client) {
        String label = client.clientName.isEmpty() ? client.clientId : client.clientName;
        new AlertDialog.Builder(this)
                .setTitle(R.string.remote_trusted_revoke_confirm_title)
                .setMessage(getString(R.string.remote_trusted_revoke_confirm_message, label))
                .setNegativeButton(R.string.remote_pair_cancel, null)
                .setPositiveButton(R.string.remote_trusted_revoke,
                        (d, which) -> revoke(client))
                .show();
    }

    private void revoke(@NonNull RemoteTrustedClient client) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            finish();
            return;
        }
        setBusy(true);
        user.getIdToken(false)
                .addOnSuccessListener(tokenResult ->
                        executor.execute(() -> runRevoke(tokenResult, client.clientId)))
                .addOnFailureListener(error -> {
                    setBusy(false);
                    Toast.makeText(this,
                            getString(R.string.remote_error, error.getMessage()),
                            Toast.LENGTH_LONG).show();
                });
    }

    private void runRevoke(@NonNull GetTokenResult tokenResult, @NonNull String clientId) {
        try {
            String idToken = tokenResult.getToken();
            if (TextUtils.isEmpty(idToken)) {
                throw new IllegalStateException("Empty Firebase ID token");
            }
            pairApi.revokeClient(idToken, clientId);
            mainHandler.post(() -> {
                Toast.makeText(this, R.string.remote_trusted_revoked, Toast.LENGTH_SHORT).show();
                reload();
            });
        } catch (Exception error) {
            mainHandler.post(() -> {
                setBusy(false);
                Toast.makeText(this,
                        getString(R.string.remote_error,
                                error.getMessage() != null ? error.getMessage() : "unknown"),
                        Toast.LENGTH_LONG).show();
            });
        }
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
    }
}
