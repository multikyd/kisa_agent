package com.kisapilot.kisaagent;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.*;
import androidx.core.app.NotificationCompat;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.*;
import java.util.*;

/** One persisted download job, independent of the activity lifecycle. */
public final class DriveLogDownloadService extends Service {
    private static final String CHANNEL = "drive_log_download";
    private static final int ID = 5005;
    static volatile boolean running;
    static volatile String status = "";
    static volatile int percent;
    private volatile boolean paused;
    private Thread worker;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private long lastNotification;

    static File directory(Context c) { return new File(c.getFilesDir(), "drive-log-transfer"); }
    static File job(Context c) { return new File(directory(c), "job.json"); }
    static void clear(Context c) throws IOException {
        File dir = directory(c);
        File[] children = dir.listFiles();
        if (children != null) for (File file : children)
            if (!file.delete()) throw new IOException("임시 파일을 삭제하지 못했습니다: " + file.getName());
    }
    static void start(Context c) {
        c.startForegroundService(new Intent(c, DriveLogDownloadService.class));
    }
    static void pause(Context c) {
        c.startService(new Intent(c, DriveLogDownloadService.class).setAction("pause"));
    }
    @Override public IBinder onBind(Intent i) { return null; }
    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "주행 로그 다운로드", NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent != null && "pause".equals(intent.getAction())) {
            paused = true;
            ResumableLogFile.interrupt();
            if (worker != null) worker.interrupt();
            if (!running) stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;
        paused = false; running = true; percent = 0; status = "다운로드 준비 중…";
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification(true), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(ID, notification(true));
            wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KisaAgent:DriveLogs");
            wake.acquire(6 * 60 * 60 * 1000L);
            WifiManager manager = (WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
            if (manager != null) {
                wifi = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "KisaAgent:DriveLogs");
                wifi.acquire();
            }
            worker = new Thread(this::download, "persistent-drive-log-download");
            worker.start();
        } catch (Exception e) {
            running = false; status = "시작 실패: " + e.getMessage(); releaseLocks(); stopSelf();
        }
        return START_NOT_STICKY;
    }
    private Notification notification(boolean active) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, VideoListActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("주행 로그 다운로드")
                .setContentText(status).setStyle(new NotificationCompat.BigTextStyle().bigText(status))
                .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(active);
        if (active) {
            PendingIntent pause = PendingIntent.getService(this, 1,
                    new Intent(this, DriveLogDownloadService.class).setAction("pause"),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.setProgress(100, percent, false).addAction(0, "일시정지", pause);
        }
        return b.build();
    }
    private void update(String text, int value) {
        status = text; percent = value;
        long now = SystemClock.elapsedRealtime();
        if (now - lastNotification >= 1000) {
            lastNotification = now;
            try { getSystemService(NotificationManager.class).notify(ID, notification(true)); }
            catch (SecurityException ignored) { }
        }
    }
    private void checkPaused() throws IOException {
        if (paused || Thread.currentThread().isInterrupted()) throw new IOException("일시정지됨");
    }
    private Set<String> refreshFiles(String server) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL("http://" + server + ":5005/realdata_files").openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(30000);
        try {
            if (c.getResponseCode() != 200) throw new IOException("주행 로그 목록 확인 실패: HTTP " + c.getResponseCode());
            StringBuilder text = new StringBuilder();
            try (Reader reader = new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8)) {
                char[] buffer = new char[8192]; int n;
                while ((n = reader.read(buffer)) != -1) {
                    checkPaused(); text.append(buffer, 0, n);
                    if (text.length() > 16 * 1024 * 1024) throw new IOException("주행 로그 목록이 너무 큽니다.");
                }
            }
            JSONArray items = new JSONObject(text.toString()).getJSONArray("items");
            Set<String> result = new HashSet<>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject file = items.getJSONObject(i);
                if (!file.optBoolean("is_dir", true)) result.add(file.getString("path"));
            }
            return result;
        } finally { c.disconnect(); }
    }
    private void download() {
        try {
            JSONObject plan = new JSONObject(new String(Files.readAllBytes(job(this).toPath()), StandardCharsets.UTF_8));
            JSONArray files = plan.getJSONArray("files");
            update("현재 로그 목록 확인 중 · 받은 파일 유지", 0);
            Set<String> existing = refreshFiles(plan.getString("server"));
            Set<String> locked = DriveLogFiles.lockedSegments(existing);
            Map<Integer, String> omitted = new LinkedHashMap<>();
            List<String> report = new ArrayList<>();
            JSONArray excluded = plan.optJSONArray("excluded");
            if (excluded != null) for (int i = 0; i < excluded.length(); i++) report.add(excluded.getString(i));
            long total = plan.getLong("bytes"), needed = 0;
            for (int i = 0; i < files.length(); i++) {
                JSONObject file = files.getJSONObject(i);
                String path = file.getString("path");
                File part = new File(directory(this), i + ".part");
                long size = file.getLong("size");
                // Complete local files survive removal of their source on C4.
                String reason = DriveLogFiles.exclusion(path, existing, locked, part.isFile() && part.length() == size);
                if (reason != null) { omitted.put(i, reason); report.add(path + " · " + reason); }
                else needed += Math.max(0, size - part.length());
            }
            if (new StatFs(directory(this).getPath()).getAvailableBytes() < needed + 64 * 1024 * 1024L)
                throw new IOException("휴대폰 내부 공간이 부족합니다. 원본 로그 임시 저장 공간이 필요합니다.");
            long completed = 0;
            for (int i = 0; i < files.length(); i++) {
                checkPaused();
                JSONObject file = files.getJSONObject(i);
                String path = file.getString("path"), name = DriveLogFiles.relative(path);
                if (name == null) throw new IOException("잘못된 주행 로그 경로");
                File part = new File(directory(this), i + ".part");
                long size = file.getLong("size"), before = completed;
                if (omitted.containsKey(i)) { completed += size; continue; }
                URL url = new URL("http://" + plan.getString("server") + ":5005/file/explorer_download?path=" + URLEncoder.encode(path, "UTF-8"));
                for (int attempt = 0; ; attempt++) {
                    checkPaused();
                    try {
                        ResumableLogFile.fetch(url, part, size, () -> paused, n -> update(
                                name + " · " + android.text.format.Formatter.formatFileSize(this, before + n)
                                        + " / " + android.text.format.Formatter.formatFileSize(this, total),
                                total == 0 ? 0 : (int)Math.min(89, (before + n) * 90 / total)));
                        break;
                    } catch (ResumableLogFile.SourceChanged e) {
                        checkPaused();
                        omitted.put(i, e.getMessage()); report.add(path + " · " + e.getMessage());
                        update("누락/변경 파일 제외 · " + name, percent);
                        break;
                    }
                    catch (IOException e) {
                        checkPaused();
                        if (attempt >= 5) throw new IOException("연결 복구 실패: " + e.getMessage() + "\n받은 파일은 보관했습니다. 다시 열어 이어받으세요.", e);
                        update("연결이 끊겨 재접속 중 (" + (attempt + 1) + "/5) · 받은 파일 보관", percent);
                        Thread.sleep(Math.min(30000, 5000L * (attempt + 1)));
                    }
                }
                completed += size;
            }
            checkPaused();
            update("다운로드 완료 · ZIP 저장 중…", 90);
            Uri target = Uri.parse(plan.getString("target"));
            OutputStream destination = getContentResolver().openOutputStream(target, "wt");
            if (destination == null) throw new IOException("ZIP 저장 위치를 열 수 없습니다.");
            long zipped = 0;
            try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(destination, 65536))) {
                zip.setLevel(0);
                for (int i = 0; i < files.length(); i++) {
                    checkPaused();
                    JSONObject file = files.getJSONObject(i);
                    if (omitted.containsKey(i)) { zipped += file.getLong("size"); continue; }
                    String name = DriveLogFiles.relative(file.getString("path"));
                    if (name == null) throw new IOException("잘못된 로그 경로");
                    long before = zipped;
                    final long[] copied = {0};
                    zip.putNextEntry(new ZipEntry(name));
                    try (InputStream input = new FileInputStream(new File(directory(this), i + ".part"))) {
                        DriveLogTransfer.copy(input, zip, file.getLong("size"), () -> paused, n -> {
                            copied[0] += n;
                            update("ZIP 저장 중 · " + name, total == 0 ? 90 : 90 + (int)Math.min(9, (before + copied[0]) * 10 / total));
                        });
                    }
                    zip.closeEntry(); zipped += file.getLong("size");
                }
                if (!report.isEmpty()) {
                    zip.putNextEntry(new ZipEntry("KisaAgent_download_report.txt"));
                    String text = "선택한 로그 중 일부 파일이 제외되었습니다.\n기록 중인 세그먼트/임시 파일, 삭제 또는 변경된 파일 목록:\n\n"
                            + String.join("\n", report) + "\n";
                    zip.write(text.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
                }
                if (plan.has("bookmark_metadata")) {
                    zip.putNextEntry(new ZipEntry("BOOKMARK_REPLAY_README.txt"));
                    String help = "북마크 원본 로그·영상 내보내기\n\n"
                            + "ZIP을 PC의 한 폴더에 압축 해제하세요. route--세그먼트 폴더와 rlog/qlog/HEVC 원본 이름을 유지하세요.\n"
                            + "북마크와 관련된 원본 세그먼트 묶음이며, 정확한 시각으로 자른 영상이 아닙니다.\n"
                            + "정확한 북마크 시각과 횟수는 원본 로그의 userBookmark/bookmarkButton 이벤트를 확인하세요.\n"
                            + "home-replay에서 압축 해제한 로그 경로/해당 route와 세그먼트 범위를 선택하세요.\n"
                            + "home-replay 버전에 따른 가져오기 방식은 별도 확인이 필요합니다.\n";
                    zip.write(help.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
                }
                checkPaused();
            }
            status = "주행 로그 ZIP 저장 완료 · " + (files.length() - omitted.size()) + "개 파일"
                    + (report.isEmpty() ? "" : "\n제외 " + report.size() + "개 · ZIP의 KisaAgent_download_report.txt 확인"); percent = 100;
            clear(this);
            try { getContentResolver().releasePersistableUriPermission(target, Intent.FLAG_GRANT_WRITE_URI_PERMISSION); }
            catch (SecurityException ignored) { }
        } catch (Exception e) {
            status = paused ? "일시정지됨 · 받은 파일 보관. 앱에서 이어받을 수 있습니다." : "다운로드 중단: " + e.getMessage();
        } finally {
            running = false;
            releaseLocks();
            stopForeground(STOP_FOREGROUND_REMOVE);
            try { getSystemService(NotificationManager.class).notify(ID, notification(false)); }
            catch (SecurityException ignored) { }
            final String message = status;
            new Handler(Looper.getMainLooper()).post(() -> android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show());
            stopSelf();
        }
    }
    private synchronized void releaseLocks() {
        if (wifi != null && wifi.isHeld()) wifi.release();
        if (wake != null && wake.isHeld()) wake.release();
    }
    @Override public void onTimeout(int startId, int fgsType) {
        paused = true;
        ResumableLogFile.interrupt();
        if (worker != null) worker.interrupt();
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }
    @Override public void onDestroy() {
        paused = true;
        ResumableLogFile.interrupt();
        if (worker != null) worker.interrupt();
        releaseLocks(); super.onDestroy();
    }
}
