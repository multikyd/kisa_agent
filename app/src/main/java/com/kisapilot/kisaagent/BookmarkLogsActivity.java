package com.kisapilot.kisaagent;

import android.os.Bundle;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** Segment-based bookmarks; original rlogs and HEVC streams remain replayable. */
public class BookmarkLogsActivity extends AppCompatActivity {
    private DriveLogDownloader downloader;
    private JSONArray bookmarks = new JSONArray();
    private ListView list;
    private TextView info;
    private Spinner range;
    private Button refresh, download;
    private String server;
    private boolean busy;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        server = HomeActivity.serverIp;
        downloader = new DriveLogDownloader(this, state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int)(12 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        info = new TextView(this);
        info.setText("북마크가 있는 1분 구간과 주변 원본 로그·영상을 저장합니다.\n정확한 북마크 시각은 원본 로그에 남아 있습니다.");
        layout.addView(info);
        AutoBookmarkSettings.attach(this, layout);
        range = new Spinner(this);
        range.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"북마크 구간만 (약 1분)", "앞뒤 1구간씩 (기본 · 최대 약 3분)", "앞뒤 2구간씩 (최대 약 5분)"}));
        range.setSelection(1); layout.addView(range);
        refresh = new Button(this); refresh.setText("북마크 목록 새로고침"); layout.addView(refresh);
        refresh.setOnClickListener(v -> load());
        list = new ListView(this); list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);

        download = new Button(this); download.setText("선택한 북마크 로그·영상 가져오기"); layout.addView(download);
        download.setOnClickListener(v -> prepare());
        layout.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        ScreenInsets.setContentWithToolbar(this, layout, "북마크 로그 가져오기");
        load();
    }
    private void setBusy(boolean value) {
        busy = value; refresh.setEnabled(!value); download.setEnabled(!value); range.setEnabled(!value);
    }
    private JSONObject request(String path, JSONObject body) throws Exception {
        if (server == null || server.equals("Not connected")) throw new IOException("C4에 먼저 연결해주세요.");
        HttpURLConnection c = (HttpURLConnection)new URL("http://" + server + ":5005" + path).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(30000);
        try {
            if (body != null) {
                c.setRequestMethod("POST"); c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                try (OutputStream out = c.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            }
            int code = c.getResponseCode();
            if (code == 404) throw new IOException("C4 에이전트에 북마크 API가 없습니다. kisa_agent.py와 bookmark_export.py를 적용하고 에이전트를 재시작해주세요.");
            InputStream stream = code < 400 ? c.getInputStream() : c.getErrorStream();
            if (stream == null) throw new IOException("서버 응답 없음 (HTTP " + code + ")");
            StringBuilder text = new StringBuilder();
            try (Reader in = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                char[] buf = new char[8192]; int n;
                while ((n = in.read(buf)) != -1) {
                    text.append(buf, 0, n);
                    if (text.length() > 16 * 1024 * 1024) throw new IOException("서버 응답이 너무 큽니다.");
                }
            }
            JSONObject result = new JSONObject(text.toString());
            if (code >= 400 || result.has("error")) throw new IOException(result.optString("error", "HTTP " + code));
            return result;
        } finally { c.disconnect(); }
    }
    private void load() {
        if (busy) return;
        setBusy(true); info.setText("C4 북마크 목록 확인 중…");
        new Thread(() -> {
            try {
                JSONArray items = request("/bookmarks", null).getJSONArray("bookmarks");
                String[] titles = new String[items.length()];
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    titles[i] = item.getString("route") + "\n북마크 세그먼트 " + item.getInt("segment")
                            + (item.optBoolean("active") ? " · 기록 중 (종료 후 받기)" : "");
                }
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    bookmarks = items;
                    list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_multiple_choice, titles));
                    list.clearChoices();
                    info.setText(items.length() == 0 ? "보관된 북마크가 없습니다. C4 왼쪽 스와이프로 북마크를 저장해주세요."
                            : "북마크 " + items.length() + "개 · 같은 1분 구간의 북마크는 한 항목으로 표시합니다.\n주변 구간의 로그와 모든 카메라 원본 영상을 포함합니다.");
                    setBusy(false);
                });
            } catch (Exception e) { error(e); }
        }, "bookmark-list").start();
    }
    private void prepare() {
        if (busy) return;
        JSONArray ids = new JSONArray();
        for (int i = 0; i < bookmarks.length(); i++) if (list.isItemChecked(i)) ids.put(bookmarks.optJSONObject(i).optString("id"));
        if (ids.length() == 0) { Toast.makeText(this, "북마크를 선택해주세요.", Toast.LENGTH_SHORT).show(); return; }
        int adjacent = range.getSelectedItemPosition();
        setBusy(true);
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject().put("ids", ids).put("adjacent", adjacent).put("archive", false);
                JSONObject plan = request("/bookmarks/export", body).put("server", server);
                runOnUiThread(() -> { if (!isDestroyed()) { setBusy(false); downloader.chooseBookmarks(plan); } });
            } catch (Exception e) { error(e); }
        }, "bookmark-export").start();
    }
    private void error(Exception e) {
        runOnUiThread(() -> { if (!isDestroyed()) { info.setText("북마크 확인 실패: " + e.getMessage()); setBusy(false); } });
    }
    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); downloader.save(out); }
    @Override protected void onDestroy() { downloader.destroy(); super.onDestroy(); }
}
