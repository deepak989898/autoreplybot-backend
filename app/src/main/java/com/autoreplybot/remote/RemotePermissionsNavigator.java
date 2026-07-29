package com.autoreplybot.remote;

import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.autoreplybot.R;

/** Opens Remote Control home focused on Permissions or Management. */
public final class RemotePermissionsNavigator {
    private RemotePermissionsNavigator() {}

    public static void openPermissionsCard(@NonNull Context context) {
        Intent intent = new Intent(context, RemoteControlHomeActivity.class);
        intent.putExtra(RemoteControlHomeActivity.EXTRA_FOCUS_PERMISSIONS, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(intent);
        Toast.makeText(context, R.string.remote_perm_grant_on_home, Toast.LENGTH_LONG).show();
    }

    /** Opens Remote Control home Management card and the shared-folder picker. */
    public static void openFileManagerAccess(@NonNull Context context) {
        Intent intent = new Intent(context, RemoteControlHomeActivity.class);
        intent.putExtra(RemoteControlHomeActivity.EXTRA_FOCUS_MANAGEMENT, true);
        intent.putExtra(RemoteControlHomeActivity.EXTRA_OPEN_FOLDER_PICKER, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(intent);
        Toast.makeText(context, R.string.remote_files_choose_hint, Toast.LENGTH_LONG).show();
    }
}
