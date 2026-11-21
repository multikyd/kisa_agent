package com.kisapilot.kisaagent;

import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.graphics.Color;
import android.widget.CheckBox;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LogActivity extends AppCompatActivity {

    private TextView tvFullLog;
    private ScrollView scrollViewLog;
    private CheckBox cbAutoScroll;
    private boolean isPolling = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);

        // ★ [수정] 기본 액션바 숨기기 (레이아웃에 타이틀을 직접 넣었으므로)
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        tvFullLog = findViewById(R.id.tv_full_log);
        scrollViewLog = findViewById(R.id.scroll_view_log);
        cbAutoScroll = findViewById(R.id.cb_auto_scroll);

        // 기 상태 설정 (자동 갱신 중에는 선택 끄기)
        tvFullLog.setTextIsSelectable(false);
        // 체크박스 상태에 따라 복사 기능 제어
        cbAutoScroll.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                // 자동 갱신 켜짐 -> 선택 기능 끄기 (스크롤 튐 방지)
                tvFullLog.setTextIsSelectable(false);
            } else {
                // 자동 갱신 꺼짐 -> 선택 기능 켜기 (드래그 복사 가능)
                tvFullLog.setTextIsSelectable(true);
            }
        });

        startLogPolling();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isPolling = false;
    }

    private void startLogPolling() {
        new Thread(() -> {
            while (isPolling) {
                try {
                    String targetUrl = "http://" + HomeActivity.serverIp + ":5005/log/tmux";
                    URL url = new URL(targetUrl);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(2000);

                    int responseCode = conn.getResponseCode();

                    if (responseCode == 200) {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) sb.append(line).append("\n");
                        reader.close();

                        JSONObject json = new JSONObject(sb.toString());
                        String logContent = json.optString("log", "No content");

                        // 색상 변환을 백그라운드에서 미리 수행 (UI 스레드 부하 감소)
                        CharSequence coloredLog = parseAnsiColor(logContent);

                        runOnUiThread(() -> {
                            // ★ [수정] 체크박스가 켜져 있을 때만 화면을 갱신하고 스크롤합니다.
                            // 체크를 끄면 화면이 '일시정지' 상태가 되어 로그를 읽기 편해집니다.
                            if (cbAutoScroll.isChecked()) {
                                tvFullLog.setText(coloredLog);
                                scrollViewLog.post(() -> scrollViewLog.fullScroll(ScrollView.FOCUS_DOWN));
                            }
                        });
                    } else {
                        runOnUiThread(() -> tvFullLog.setText("Failed: " + responseCode));
                    }
                    conn.disconnect();

                    Thread.sleep(1000); // 1초 간격 갱신

                } catch (Exception e) {
                    try { Thread.sleep(2000); } catch (InterruptedException ex) {}
                }
            }
        }).start();
    }

    // ANSI 색상 파싱 메서드 (기존과 동일하게 유지)
    private CharSequence parseAnsiColor(String text) {
        SpannableStringBuilder builder = new SpannableStringBuilder();
        Pattern pattern = Pattern.compile("\\u001B\\[([0-9;]*)m");
        Matcher matcher = pattern.matcher(text);

        int lastIndex = 0;
        int defaultColor = Color.parseColor("#CCCCCC");
        int currentColor = defaultColor;

        while (matcher.find()) {
            int start = matcher.start();
            if (start > lastIndex) {
                int spanStart = builder.length();
                builder.append(text.substring(lastIndex, start));
                builder.setSpan(new ForegroundColorSpan(currentColor), spanStart, builder.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }

            String codeStr = matcher.group(1);
            if (codeStr != null && !codeStr.isEmpty()) {
                String[] codes = codeStr.split(";");
                for (String code : codes) {
                    try {
                        int c = Integer.parseInt(code);
                        switch (c) {
                            case 0: case 39: currentColor = defaultColor; break;
                            case 30: currentColor = Color.GRAY; break;
                            case 31: currentColor = Color.parseColor("#FF5555"); break;
                            case 32: currentColor = Color.parseColor("#50FA7B"); break;
                            case 33: currentColor = Color.parseColor("#F1FA8C"); break;
                            case 34: currentColor = Color.parseColor("#BD93F9"); break;
                            case 35: currentColor = Color.parseColor("#FF79C6"); break;
                            case 36: currentColor = Color.parseColor("#8BE9FD"); break;
                            case 37: currentColor = Color.WHITE; break;

                            case 90: currentColor = Color.DKGRAY; break;
                            case 91: currentColor = Color.parseColor("#FF6E6E"); break;
                            case 92: currentColor = Color.parseColor("#69FF94"); break;
                            case 93: currentColor = Color.parseColor("#FFFFA5"); break;
                            case 94: currentColor = Color.parseColor("#D6ACFF"); break;
                            case 95: currentColor = Color.parseColor("#FF92DF"); break;
                            case 96: currentColor = Color.parseColor("#A4FFFF"); break;
                            case 97: currentColor = Color.WHITE; break;
                        }
                    } catch (NumberFormatException e) { }
                }
            } else {
                currentColor = defaultColor;
            }
            lastIndex = matcher.end();
        }
        if (lastIndex < text.length()) {
            int spanStart = builder.length();
            builder.append(text.substring(lastIndex));
            builder.setSpan(new ForegroundColorSpan(currentColor), spanStart, builder.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return builder;
    }
}
