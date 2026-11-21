package com.kisapilot.kisaagent;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import androidx.core.content.ContextCompat;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;

/** Downloads into app storage and hands the verified APK to Android's installer. */
final class AppUpdater {
    private static final String LATEST_URL =
            "https://raw.githubusercontent.com/multikyd/kisa_agent/main/kisa_agent_latest.json";
    private final HomeActivity activity;
    private final DownloadManager manager;
    private final SharedPreferences state;
    private final ActivityResultLauncher<Intent> permissionLauncher;
    private final ActivityResultLauncher<Intent> installLauncher;
    private boolean started;
    private boolean checkingDownload;
    private boolean prompting;
    private boolean installing;
    private boolean waitingPermission;
    private JSONObject availableUpdate;
    private AlertDialog dialog;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())
                    && intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                    == state.getLong("downloadId", -2)) inspectDownload();
        }
    };

    AppUpdater(HomeActivity activity) {
        this.activity = activity;
        manager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        state = activity.getSharedPreferences("app_update", Context.MODE_PRIVATE);
        permissionLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    waitingPermission = false;
                    if (activity.getPackageManager().canRequestPackageInstalls()) {
                        beginPendingDownload();
                        inspectDownload();
                    }
                    else Toast.makeText(activity, R.string.install_permission_title, Toast.LENGTH_LONG).show();
                });
        installLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    installing = false;
                    clearDownload();
                });
    }

    void onStart() {
        started = true;
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        ContextCompat.registerReceiver(activity, receiver, filter, ContextCompat.RECEIVER_EXPORTED);
        if (activity.getPackageManager().canRequestPackageInstalls()) beginPendingDownload();
        inspectDownload();
        if (availableUpdate != null && state.getLong("downloadId", -1) == -1) showUpdate();
    }

    void onStop() {
        started = false;
        activity.unregisterReceiver(receiver);
    }

    void onDestroy() {
        if (dialog != null) dialog.dismiss();
    }

    void check() {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(LATEST_URL + "?t=" + System.currentTimeMillis()).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setUseCaches(false);
                if (conn.getResponseCode() != 200) return;
                String json;
                try (java.io.InputStream input = conn.getInputStream()) {
                    java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                        if (output.size() > 262144) throw new java.io.IOException("Update metadata too large");
                    }
                    json = output.toString(StandardCharsets.UTF_8.name());
                }
                JSONObject update = new JSONObject(json);
                if (update.getLong("versionCode") <= version(activity.getPackageManager()
                        .getPackageInfo(activity.getPackageName(), 0))) return;
                Uri uri = Uri.parse(update.getString("apkUrl"));
                if (!"https".equals(uri.getScheme()) || !"raw.githubusercontent.com".equals(uri.getHost())) {
                    throw new java.io.IOException("Invalid update URL");
                }
                activity.runOnUiThread(() -> {
                    availableUpdate = update;
                    if (started && state.getLong("downloadId", -1) == -1) showUpdate();
                });
            } catch (Exception e) {
                Log.w("AppUpdater", "Update check failed", e);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    private void showUpdate() {
        if (prompting || activity.isFinishing() || activity.isDestroyed()) return;
        prompting = true;
        JSONObject notes = availableUpdate.optJSONObject("releaseNotes");
        String releaseNotes = notes == null ? "" : notes.optString(Locale.getDefault().getLanguage(),
                notes.optString("ko", notes.optString("en", "")));
        dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.app_update_title)
                .setMessage(activity.getString(R.string.app_update_message, releaseNotes))
                .setPositiveButton(R.string.action_update_now, (d, w) -> requestUpdate(availableUpdate))
                .setNegativeButton(R.string.action_later, null).create();
        // Prompt once per launch; completing downloads are handled independently.
        dialog.setOnDismissListener(d -> { availableUpdate = null; });
        dialog.show();
    }

    private File apkFile() {
        File directory = activity.getExternalFilesDir("updates");
        if (directory == null) throw new IllegalStateException("Update storage unavailable");
        return new File(directory, "kisa_agent.apk");
    }

    private void requestUpdate(JSONObject update) {
        state.edit().putString("pendingMetadata", update.toString()).commit();
        if (activity.getPackageManager().canRequestPackageInstalls()) beginPendingDownload();
        else requestInstallPermission();
    }

    private void beginPendingDownload() {
        String pending = state.getString("pendingMetadata", "");
        if (pending.isEmpty() || !activity.getPackageManager().canRequestPackageInstalls()) return;
        state.edit().remove("pendingMetadata").commit();
        try { download(new JSONObject(pending)); }
        catch (Exception e) { fail(e); }
    }

    private void download(JSONObject update) {
        try {
            File apk = apkFile();
            if (apk.exists() && !apk.delete()) throw new java.io.IOException("Cannot remove old update");
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(update.getString("apkUrl")))
                    .setMimeType("application/vnd.android.package-archive")
                    .setTitle(activity.getString(R.string.app_update_download_title))
                    .setDescription(activity.getString(R.string.app_update_download_description))
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setDestinationInExternalFilesDir(activity, "updates", "kisa_agent.apk");
            long id = manager.enqueue(request);
            state.edit().putLong("downloadId", id)
                    .putLong("versionCode", update.getLong("versionCode"))
                    .putString("sha256", update.optString("sha256", "")).commit();
            Toast.makeText(activity, R.string.app_update_download_start, Toast.LENGTH_LONG).show();
            inspectDownload();
        } catch (Exception e) { fail(e); }
    }

    private void inspectDownload() {
        long id = state.getLong("downloadId", -1);
        if (!started || id == -1 || checkingDownload || installing || waitingPermission) return;
        try {
            if (state.getLong("versionCode", -1) <= version(activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0))) {
                clearDownload();
                return;
            }
        } catch (PackageManager.NameNotFoundException e) { fail(e); return; }
        checkingDownload = true;
        new Thread(() -> {
            try {
                int status;
                try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
                    if (cursor == null || !cursor.moveToFirst()) throw new java.io.IOException("Download missing");
                    status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                }
                if (status == DownloadManager.STATUS_FAILED) throw new java.io.IOException("Download failed");
                if (status != DownloadManager.STATUS_SUCCESSFUL) return;
                verifyApk(apkFile());
                activity.runOnUiThread(() -> {
                    checkingDownload = false;
                    if (started && !activity.isFinishing() && !activity.isDestroyed()) install();
                });
            } catch (Exception e) {
                activity.runOnUiThread(() -> { clearDownload(); fail(e); });
            } finally {
                activity.runOnUiThread(() -> checkingDownload = false);
            }
        }).start();
    }

    @SuppressWarnings("deprecation")
    private void verifyApk(File apk) throws Exception {
        String expectedHash = state.getString("sha256", "");
        if (!expectedHash.isEmpty()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream input = new FileInputStream(apk)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            if (!expectedHash.equalsIgnoreCase(hex.toString())) throw new java.io.IOException("APK checksum mismatch");
        }
        PackageManager pm = activity.getPackageManager();
        PackageInfo current = pm.getPackageInfo(activity.getPackageName(), PackageManager.GET_SIGNATURES);
        PackageInfo update = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNATURES);
        if (update == null || !activity.getPackageName().equals(update.packageName)
                || version(update) != state.getLong("versionCode", -1) || version(update) <= version(current)
                || current.signatures == null || update.signatures == null
                || !Arrays.equals(current.signatures, update.signatures)) {
            throw new java.io.IOException("APK package, version or signing key does not match");
        }
    }

    private static long version(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    private void requestInstallPermission() {
            if (waitingPermission) return;
            waitingPermission = true;
            dialog = new AlertDialog.Builder(activity).setTitle(R.string.install_permission_title)
                    .setMessage(R.string.install_permission_message)
                    .setPositiveButton(R.string.action_settings, (d, w) -> {
                        try {
                            permissionLauncher.launch(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + activity.getPackageName())));
                        } catch (Exception e) { waitingPermission = false; fail(e); }
                    }).setNegativeButton(R.string.action_later, (d, w) -> waitingPermission = false).create();
            dialog.setOnCancelListener(d -> waitingPermission = false);
            dialog.show();
    }

    private void install() {
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            requestInstallPermission();
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".provider", apkFile());
            // Leave Android's normal completion UI (Done/Open) enabled rather than
            // requesting an immediate install-result return to this replacing process.
            Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(android.content.ClipData.newRawUri("Kisa Agent update", uri));
            installing = true;
            installLauncher.launch(intent);
        } catch (Exception e) { installing = false; fail(e); }
    }

    private void clearDownload() {
        long id = state.getLong("downloadId", -1);
        state.edit().clear().apply();
        if (id != -1) manager.remove(id);
    }

    private void fail(Exception e) {
        Log.e("AppUpdater", "App update failed", e);
        if (!activity.isDestroyed()) Toast.makeText(activity, R.string.app_update_failed, Toast.LENGTH_LONG).show();
    }
}
