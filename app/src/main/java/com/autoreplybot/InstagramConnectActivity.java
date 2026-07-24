package com.autoreplybot;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.facebook.AccessToken;
import com.facebook.CallbackManager;
import com.facebook.FacebookCallback;
import com.facebook.FacebookException;
import com.facebook.login.LoginBehavior;
import com.facebook.login.LoginManager;
import com.facebook.login.LoginResult;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dedicated Instagram Business connection flow (independent from FB destination toggle).
 */
public class InstagramConnectActivity extends AppCompatActivity {

    private static final String TAG = "IG_CONNECT";
    private static final List<String> INSTAGRAM_PERMISSIONS = Arrays.asList(
            "public_profile",
            "pages_show_list",
            "pages_read_engagement",
            "instagram_basic",
            "instagram_content_publish"
    );

    private CallbackManager callbackManager;
    private ActivityResultLauncher<Collection<? extends String>> loginLauncher;
    private MaterialButton buttonLogin;
    private RecyclerView recyclerView;
    private ProgressBar progress;
    private TextView emptyState;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<FacebookManagedPage> pagesWithIg = new ArrayList<>();
    private boolean loginInFlight = false;
    private boolean hasRetriedPermissionRequest = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);

        if (TextUtils.isEmpty(BuildConfig.FACEBOOK_APP_ID)) {
            Toast.makeText(this, R.string.fb_missing_app_id, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        setContentView(R.layout.activity_instagram_connect);
        callbackManager = CallbackManager.Factory.create();

        MaterialToolbar toolbar = findViewById(R.id.toolbar_ig_connect);
        toolbar.setNavigationOnClickListener(v -> finish());

        buttonLogin = findViewById(R.id.button_ig_login);
        recyclerView = findViewById(R.id.recycler_ig_accounts);
        progress = findViewById(R.id.progress_ig_connect);
        emptyState = findViewById(R.id.text_ig_accounts_empty);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        LoginManager.getInstance().setLoginBehavior(LoginBehavior.WEB_ONLY);
        loginLauncher = registerForActivityResult(
                LoginManager.getInstance().createLogInActivityResultContract(callbackManager),
                unused -> { }
        );
        LoginManager.getInstance().registerCallback(callbackManager, new FacebookCallback<LoginResult>() {
            @Override
            public void onSuccess(@NonNull LoginResult loginResult) {
                Log.i(TAG, "Instagram callback success");
                loginInFlight = false;
                AccessToken token = loginResult.getAccessToken();
                if (token == null) token = AccessToken.getCurrentAccessToken();
                if (token == null) {
                    setBusy(false);
                    Toast.makeText(InstagramConnectActivity.this, R.string.fb_login_failed, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (isMissingAnyRequiredPermission(token) && !hasRetriedPermissionRequest) {
                    hasRetriedPermissionRequest = true;
                    Toast.makeText(InstagramConnectActivity.this, R.string.fb_pages_missing_scope, Toast.LENGTH_LONG).show();
                    launchInstagramLogin("rerequest");
                    return;
                }
                loadInstagramAccounts(token);
            }

            @Override
            public void onCancel() {
                Log.w(TAG, "Instagram callback cancelled");
                loginInFlight = false;
                setBusy(false);
                Toast.makeText(InstagramConnectActivity.this, R.string.fb_login_cancelled, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(@NonNull FacebookException error) {
                Log.e(TAG, "Instagram callback failed");
                loginInFlight = false;
                setBusy(false);
                Toast.makeText(InstagramConnectActivity.this,
                        error.getMessage() != null ? error.getMessage() : getString(R.string.fb_login_failed),
                        Toast.LENGTH_LONG).show();
            }
        });

        buttonLogin.setOnClickListener(v -> {
            hasRetriedPermissionRequest = false;
            launchInstagramLogin(null);
        });

        AccessToken existing = AccessToken.getCurrentAccessToken();
        if (existing != null && !existing.isExpired()) {
            loadInstagramAccounts(existing);
        }
    }

    private void loadInstagramAccounts(@Nullable AccessToken token) {
        if (token == null) {
            setBusy(false);
            return;
        }
        setBusy(true);
        executor.execute(() -> {
            try {
                List<FacebookManagedPage> allPages = FacebookGraphPagesFetcher.fetchManagedPages(token.getToken());
                List<FacebookManagedPage> filtered = new ArrayList<>();
                for (FacebookManagedPage p : allPages) {
                    if (!p.instagramUserId.isEmpty()) filtered.add(p);
                }
                mainHandler.post(() -> {
                    setBusy(false);
                    pagesWithIg.clear();
                    pagesWithIg.addAll(filtered);
                    recyclerView.setAdapter(new FacebookPageAdapter(pagesWithIg, page -> {
                        if (TextUtils.isEmpty(page.pageAccessToken)) {
                            Toast.makeText(this,
                                    "Instagram is linked, but Page token is missing. Grant Pages posting permission and reconnect.",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        setBusy(true);
                        FacebookPostingSecureStore store = new FacebookPostingSecureStore(this);
                        store.saveInstagramConnection(page.pageAccessToken, page.instagramUserId, page.instagramUsername);
                        FacebookScheduleFirestoreRepository.pushSchedule(getApplicationContext())
                                .addOnCompleteListener(task -> {
                                    setBusy(false);
                                    Toast.makeText(this, R.string.ig_account_selected, Toast.LENGTH_SHORT).show();
                                    setResult(RESULT_OK);
                                    finish();
                                });
                    }));

                    boolean empty = filtered.isEmpty();
                    recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
                    emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    setBusy(false);
                    Toast.makeText(this,
                            e.getMessage() != null ? e.getMessage() : getString(R.string.fb_pages_fetch_failed),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void launchInstagramLogin(@Nullable String authType) {
        setBusy(true);
        loginInFlight = true;
        LoginManager.getInstance().setLoginBehavior(LoginBehavior.WEB_ONLY);
        if (!TextUtils.isEmpty(authType)) {
            LoginManager.getInstance().setAuthType(authType);
        }
        loginLauncher.launch(INSTAGRAM_PERMISSIONS);
    }

    private static boolean isMissingAnyRequiredPermission(@NonNull AccessToken token) {
        if (token.getPermissions() == null || token.getPermissions().isEmpty()) {
            return true;
        }
        return !token.getPermissions().contains("pages_show_list")
                || !token.getPermissions().contains("pages_read_engagement");
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        buttonLogin.setEnabled(!busy);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (callbackManager != null) {
            callbackManager.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!loginInFlight) {
            return;
        }
        AccessToken token = AccessToken.getCurrentAccessToken();
        if (token != null && !token.isExpired()) {
            Log.i(TAG, "Recovered token in onResume after login flow");
            loginInFlight = false;
            loadInstagramAccounts(token);
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }
}
