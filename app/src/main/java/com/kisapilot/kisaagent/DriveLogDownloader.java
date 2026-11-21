package com.kisapilot.kisaagent;

import android.app.ProgressDialog;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.Intent;
import android.provider.DocumentsContract;
import android.text.format.Formatter;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.zip.*;

/** Selects a persisted download job handled by DriveLogDownloadService. */
final class DriveLogDownloader {
    private final AppCompatActivity activity;
    private final ActivityResultLauncher<String> picker;
    private final ActivityResultLauncher<String> notifications;
    private Runnable afterNotificationPermission;
    private String pending;
    private boolean destroyed;
    private ProgressDialog progress;
    private final Handler handler = new Handler(Looper.getMainLooper());

    DriveLogDownloader(AppCompatActivity activity, Bundle state) {
        this.activity = activity;
        notifications = activity.registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            Runnable action = afterNotificationPermission;
            afterNotificationPermission = null;
            if (action != null && !destroyed) action.run();
        });
        pending = state == null ? null : state.getString("driveLogDownload");
        picker = activity.registerForActivityResult(new ActivityResultContracts.CreateDocument("application/zip"), uri -> {
            String plan = pending;
            pending = null;
            if (uri != null && plan != null) download(uri, plan);
            else if (plan != null) new File(plan).delete();
        });
        handler.post(() -> {
            if (DriveLogDownloadService.running) showProgress();
            else if (DriveLogDownloadService.job(activity).isFile()) offerResume();
        });
    }
    void save(Bundle out) { out.putString("driveLogDownload", pending); }
    void destroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        if (progress != null) progress.dismiss();
    }
    private void offerResume() {
        if (destroyed || pending != null) return;
        new AlertDialog.Builder(activity).setTitle("중단된 주행 로그 · 받은 파일 보관 중")
            .setItems(new String[]{"이어받기", "ZIP 저장 위치를 바꿔 이어받기", "임시 파일 삭제"}, (d, w) -> {
                try {
                    if (w == 0) startServiceWithNotification();
                    else if (w == 1) {
                        File plan = File.createTempFile("drive-log-resume-", ".json", activity.getCacheDir());
                        java.nio.file.Files.copy(DriveLogDownloadService.job(activity).toPath(), plan.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        pending = plan.getAbsolutePath();
                        JSONObject saved=new JSONObject(new String(java.nio.file.Files.readAllBytes(plan.toPath()),java.nio.charset.StandardCharsets.UTF_8));
                        picker.launch(saved.has("bookmark_metadata")
                            ? BookmarkFileName.resumeName(saved.optString("bookmark_zip_name",null),System.currentTimeMillis())
                            : "realdata_" + System.currentTimeMillis() + ".zip");
                    } else DriveLogDownloadService.clear(activity);
                } catch (Exception e) { toast(e.getMessage()); }
            }).setNegativeButton("나중에", null).show();
    }
    void choose(List<VideoItem> selected, JSONArray manifest) {
        if (DriveLogDownloadService.running) { showProgress(); return; }
        if (DriveLogDownloadService.job(activity).isFile()) { offerResume(); return; }
        if (pending != null) { toast("다운로드가 이미 진행 중입니다."); return; }
        if (selected.isEmpty()) return;
        Set<String> segments = new HashSet<>();
        for (VideoItem item : selected) for (String path : item.getSegmentPaths())
            segments.add(path + "/");
        JSONArray logs = new JSONArray(), all = new JSONArray(), excluded = new JSONArray();
        Set<String> available = new HashSet<>();
        for (int i = 0; i < manifest.length(); i++) {
            JSONObject item = manifest.optJSONObject(i);
            if (item != null && !item.optBoolean("is_dir", true)) available.add(item.optString("path"));
        }
        Set<String> locked = DriveLogFiles.lockedSegments(available);
        long logBytes = 0, allBytes = 0;
        for (int i = 0; i < manifest.length(); i++) {
            JSONObject file = manifest.optJSONObject(i);
            if (file == null || file.optBoolean("is_dir", true)) continue;
            String path = file.optString("path");
            String relative = DriveLogFiles.relative(path);
            if (relative == null) continue;
            boolean matches = false;
            for (String segment : segments) if (path.startsWith(segment)) { matches = true; break; }
            if (!matches) continue;
            String reason = DriveLogFiles.exclusion(path, available, locked);
            if (reason != null) { excluded.put(path + " · " + reason); continue; }
            all.put(file); allBytes += file.optLong("size");
            if (!DriveLogFiles.isVideo(relative)) { logs.put(file); logBytes += file.optLong("size"); }
        }
        if (all.length() == 0) { toast("완료된 로그 파일이 없습니다. 기록 중인 세그먼트와 .lock 파일은 제외합니다."); return; }
        if (excluded.length() > 0) toast("기록 중인 세그먼트·임시 파일 " + excluded.length() + "개 제외");
        final long smallSize = logBytes, fullSize = allBytes;
        final boolean[] videos = {false};
        new AlertDialog.Builder(activity).setTitle("주행 로그 ZIP · " + selected.size() + "개 · " + Formatter.formatFileSize(activity, smallSize))
            .setMultiChoiceItems(new String[]{"영상 포함 (기본 제외, 내부 임시 공간 필요) · " + Formatter.formatFileSize(activity, fullSize - smallSize)}, videos,
                (dialog, which, checked) -> videos[0] = checked)
            .setPositiveButton("저장 위치 선택", (dialog, which) -> {
                JSONArray files = videos[0] ? all : logs;
                if (files.length() == 0) { toast("영상 외의 로그 파일이 없습니다."); return; }
                try {
                    String json = new JSONObject().put("server", HomeActivity.serverIp).put("files", files)
                        .put("bytes", videos[0] ? fullSize : smallSize).put("excluded", excluded).toString();
                    File planFile = File.createTempFile("drive-log-plan-", ".json", activity.getCacheDir());
                    try (Writer writer = new OutputStreamWriter(new FileOutputStream(planFile), java.nio.charset.StandardCharsets.UTF_8)) {
                        writer.write(json);
                    }
                    pending = planFile.getAbsolutePath();
                    picker.launch("realdata_" + System.currentTimeMillis() + (videos[0] ? "_with_videos" : "_logs") + ".zip");
                } catch (Exception e) { if (pending != null) new File(pending).delete(); pending = null; toast("저장 위치를 열 수 없습니다."); }
            }).setNegativeButton("취소", null).show();
    }
    void chooseBookmarks(JSONObject export) {
        if (DriveLogDownloadService.running) { showProgress(); return; }
        if (DriveLogDownloadService.job(activity).isFile()) { offerResume(); return; }
        if (pending != null) { toast("저장 위치 선택이 진행 중입니다."); return; }
        new AlertDialog.Builder(activity).setTitle("북마크 로그·영상 가져오기")
            .setMessage("원본 로그와 영상을 함께 저장합니다.\n예상 용량: " + Formatter.formatFileSize(activity, export.optLong("bytes"))
                + "\n휴대폰 내부 임시 공간과 ZIP 저장 공간이 필요합니다.\n\n기록 중이거나 없어진 주변 구간은 제외되며, 누락 목록을 ZIP에 기록합니다.")
            .setPositiveButton("ZIP 저장 위치 선택", (d, w) -> {
                try {
                    String name=BookmarkFileName.zipName(System.currentTimeMillis());
                    export.put("bookmark_zip_name",name);
                    File planFile = File.createTempFile("bookmark-plan-", ".json", activity.getCacheDir());
                    java.nio.file.Files.write(planFile.toPath(), export.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    pending = planFile.getAbsolutePath();
                    picker.launch(name);
                } catch (Exception e) { toast("저장 위치 선택 실패: " + e.getMessage()); }
            }).setNegativeButton("취소", null).show();
    }
    private void download(Uri target, String plan) {
        try {
            File dir = DriveLogDownloadService.directory(activity);
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("임시 저장 폴더를 만들 수 없습니다.");
            try {
                activity.getContentResolver().takePersistableUriPermission(target,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (SecurityException ignored) { }
            String text = new String(java.nio.file.Files.readAllBytes(new File(plan).toPath()), java.nio.charset.StandardCharsets.UTF_8);
            JSONObject job = new JSONObject(text).put("target", target.toString());
            File draft = new File(dir, "job.tmp");
            java.nio.file.Files.write(draft.toPath(), job.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            java.nio.file.Files.move(draft.toPath(), DriveLogDownloadService.job(activity).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            new File(plan).delete();
            startServiceWithNotification();
        } catch (Exception e) { toast("다운로드 시작 실패: " + e.getMessage()); }
    }
    private void startServiceWithNotification() {
        Runnable action = () -> {
            try { DriveLogDownloadService.start(activity); showProgress(); }
            catch (Exception e) { toast("다운로드 시작 실패: " + e.getMessage()); }
        };
        android.content.SharedPreferences prefs = activity.getSharedPreferences("drive_log_download", 0);
        if (android.os.Build.VERSION.SDK_INT >= 33
                && activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                && !prefs.getBoolean("notificationAsked", false)) {
            prefs.edit().putBoolean("notificationAsked", true).apply();
            afterNotificationPermission = action;
            notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS);
        } else action.run();
    }
    private void showProgress() {
        if (destroyed || (progress != null && progress.isShowing())) return;
        progress = new ProgressDialog(activity);
        progress.setTitle("주행 로그 다운로드");
        progress.setMessage("준비 중… 화면을 닫아도 다운로드가 계속됩니다.");
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setMax(100); progress.setCancelable(false);
        progress.setButton(ProgressDialog.BUTTON_NEGATIVE, "일시정지", (d, w) -> DriveLogDownloadService.pause(activity));
        progress.setButton(ProgressDialog.BUTTON_NEUTRAL, "백그라운드로", (d, w) -> {});
        progress.show();
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (destroyed || !progress.isShowing()) return;
                progress.setProgress(DriveLogDownloadService.percent);
                progress.setMessage(DriveLogDownloadService.status);
                if (DriveLogDownloadService.running) handler.postDelayed(this, 500);
                else { progress.dismiss(); toast(DriveLogDownloadService.status); }
            }
        }, 500);
    }
    private void toast(String message) { Toast.makeText(activity, message, Toast.LENGTH_LONG).show(); }
}
