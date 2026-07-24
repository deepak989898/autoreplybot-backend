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
 * Facebook Login → list Pages from {@code /me/accounts} → save encrypted Page token + name locally.
 * Uses {@link ActivityResultLauncher} + {@link LoginManager#createLogInActivityResultContract(CallbackManager)}
 * so login completes on Android 13+ (deprecated {@code onActivityResult} alone often breaks Custom Tab return).
 */
public class FacebookConnectActivity extends AppCompatActivity {

    private static final String TAG = "FB_CONNECT";
    private static final List<String> FACEBOOK_PERMISSIONS = Arrays.asList(
            "public_profile",
            "pages_show_list",
            "pages_read_engagement"
    );

    private CallbackManager callbackManager;
    private ActivityResultLauncher<Collection<? extends String>> facebookLoginLauncher;
    private MaterialButton buttonLogin;
    private RecyclerView recyclerView;
    private ProgressBar progress;
    private TextView emptyState;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean loginInFlight = false;
    private boolean hasRetriedPermissionRequest = false;

    private final List<FacebookManagedPage> pages = new ArrayList<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);

        if (TextUtils.isEmpty(BuildConfig.FACEBOOK_APP_ID)) {
            Toast.makeText(this, R.string.fb_missing_app_id, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        FacebookInstallKeyHash.logIfDebug(this);

        setContentView(R.layout.activity_facebook_connect);

        callbackManager = CallbackManager.Factory.create();

        MaterialToolbar toolbar = findViewById(R.id.toolbar_fb_connect);
        toolbar.setNavigationOnClickListener(v -> finish());

        buttonLogin = findViewById(R.id.button_fb_login);
        recyclerView = findViewById(R.id.recycler_fb_pages);
        progress = findViewById(R.id.progress_fb_connect);
        emptyState = findViewById(R.id.text_fb_pages_empty);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        LoginManager.getInstance().setLoginBehavior(LoginBehavior.WEB_ONLY);

        facebookLoginLauncher = registerForActivityResult(
                LoginManager.getInstance().createLogInActivityResultContract(callbackManager),
                unused -> {
                    /* Result is delivered via LoginManager.registerCallback */
                });

        LoginManager.getInstance().registerCallback(callbackManager, new FacebookCallback<LoginResult>() {
            @Override
            public void onSuccess(@NonNull LoginResult loginResult) {
                Log.i(TAG, "Facebook callback success");
                loginInFlight = false;
                AccessToken token = loginResult.getAccessToken();
                if (token == null) {
                    token = AccessToken.getCurrentAccessToken();
                }
                if (token == null) {
                    setBusy(false);
                    Toast.makeText(FacebookConnectActivity.this, R.string.fb_login_failed, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (isMissingAnyRequiredPermission(token) && !hasRetriedPermissionRequest) {
                    hasRetriedPermissionRequest = true;
                    Toast.makeText(FacebookConnectActivity.this, R.string.fb_pages_missing_scope, Toast.LENGTH_LONG).show();
                    launchFacebookLogin("rerequest");
                    return;
                }
                loadPages(token);
            }

            @Override
            public void onCancel() {
                Log.w(TAG, "Facebook callback cancelled");
                loginInFlight = false;
                setBusy(false);
                Toast.makeText(FacebookConnectActivity.this, R.string.fb_login_cancelled, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(@NonNull FacebookException error) {
                Log.e(TAG, "Facebook callback failed");
                loginInFlight = false;
                setBusy(false);
                Toast.makeText(FacebookConnectActivity.this,
                        error.getMessage() != null ? error.getMessage() : getString(R.string.fb_login_failed),
                        Toast.LENGTH_LONG).show();
            }
        });

        buttonLogin.setOnClickListener(v -> {
            hasRetriedPermissionRequest = false;
            launchFacebookLogin(null);
        });

        AccessToken existing = AccessToken.getCurrentAccessToken();
        if (existing != null && !existing.isExpired()) {
            loadPages(existing);
        }
    }

    private void loadPages(@Nullable AccessToken token) {
        if (token == null) {
            setBusy(false);
            return;
        }
        setBusy(true);
        executor.execute(() -> {
            try {
                List<FacebookManagedPage> list =
                        FacebookGraphPagesFetcher.fetchManagedPages(token.getToken());
                mainHandler.post(() -> {
                    setBusy(false);
                    pages.clear();
                    pages.addAll(list);
                    Log.i(TAG, "Managed pages shown in connect list: " + pages.size());
                    recyclerView.setAdapter(new FacebookPageAdapter(pages, page -> {
                        if (TextUtils.isEmpty(page.pageAccessToken)) {
                            Toast.makeText(this,
                                    "This Page is visible but no Page token was returned. Grant Pages posting permission and try again.",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        setBusy(true);
                        FacebookPostingSecureStore store = new FacebookPostingSecureStore(this);
                        store.saveConnection(
                                page.id,
                                page.pageAccessToken,
                                page.name,
                                page.instagramUserId,
                                page.instagramUsername
                        );
                        FacebookScheduleFirestoreRepository.pushSchedule(getApplicationContext())
                                .addOnCompleteListener(task -> {
                                    setBusy(false);
                                    Toast.makeText(this, R.string.fb_page_selected, Toast.LENGTH_SHORT).show();
                                    setResult(RESULT_OK);
                                    finish();
                                });
                    }));
                    boolean empty = list.isEmpty();
                    emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
                    recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
                    if (empty) {
                        AccessToken at = AccessToken.getCurrentAccessToken();
                        boolean hasPagesScope = at != null && at.getPermissions() != null
                                && at.getPermissions().contains("pages_show_list");
                        emptyState.setText(hasPagesScope
                                ? getString(R.string.fb_pages_empty)
                                : getString(R.string.fb_pages_missing_scope));
                    }
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

    private void launchFacebookLogin(@Nullable String authType) {
        setBusy(true);
        loginInFlight = true;
        LoginManager.getInstance().setLoginBehavior(LoginBehavior.WEB_ONLY);
        if (!TextUtils.isEmpty(authType)) {
            LoginManager.getInstance().setAuthType(authType);
        }
        facebookLoginLauncher.launch(FACEBOOK_PERMISSIONS);
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
            loadPages(token);
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }
}
