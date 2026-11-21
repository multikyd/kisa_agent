package com.kisapilot.kisaagent;

import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;

import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.graphics.Color;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.InputStream;
import android.text.TextWatcher;
import android.text.Editable;
import android.util.Log;


public class TerminalActivity extends AppCompatActivity {
    private static final String TAG = "TerminalActivity";

    private TextView tvTerminalLog;
    private ScrollView scrollView;
    private EditText etHiddenInput; // ★★★ 숨겨진 EditText

    private String serverUrl;
    private volatile String terminalSessionUuid;

    private ExecutorService executor;
    private final ExecutorService inputExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean isPolling = false;
    private volatile boolean screenStarted = false;
    private boolean colorSetupSent;
    private volatile int pollingGeneration = 0;
    private volatile HttpURLConnection pollingConnection;
    private java.util.concurrent.Future<?> pollingTask;

    private final StringBuilder currentInput = new StringBuilder(); // 현재 입력 중인 명령어

    private android.widget.HorizontalScrollView horizontalScroll;
    private float terminalFontSize = 10f;
    private boolean fitTerminal;
    private final TerminalScreen terminalScreen = new TerminalScreen(100, 32);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_terminal);

        executor = Executors.newSingleThreadExecutor();

        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        tvTerminalLog = findViewById(R.id.tv_terminal_log);
        scrollView = findViewById(R.id.terminal_scroll_view);
        etHiddenInput = findViewById(R.id.et_hidden_input);
        horizontalScroll = findViewById(R.id.terminal_horizontal_scroll);
        terminalFontSize = getPreferences(MODE_PRIVATE).getFloat("terminal_font_size", 10f);
        tvTerminalLog.setHorizontallyScrolling(true);
        findViewById(R.id.terminal_font_smaller).setOnClickListener(v -> changeFont(-1));
        findViewById(R.id.terminal_font_larger).setOnClickListener(v -> changeFont(1));
        findViewById(R.id.terminal_fit).setOnClickListener(v -> { fitTerminal = !fitTerminal; applyTerminalWidth(); });
        findViewById(R.id.terminal_ctrl_c).setOnClickListener(v -> sendControl("\u0003"));
        findViewById(R.id.terminal_escape).setOnClickListener(v -> sendControl("\u001b"));
        findViewById(R.id.terminal_tab).setOnClickListener(v -> sendControl("\t"));
        findViewById(R.id.terminal_up).setOnClickListener(v -> sendControl("\u001b[A"));
        findViewById(R.id.terminal_down).setOnClickListener(v -> sendControl("\u001b[B"));
        horizontalScroll.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> { if(r-l!=or-ol) applyTerminalWidth(); });
        horizontalScroll.post(this::applyTerminalWidth);

        String receivedIp = getIntent().getStringExtra("SERVER_IP");
        if (receivedIp != null && !receivedIp.equals("Not connected")) {
            serverUrl = "http://" + receivedIp + ":5005";
        } else {
            tvTerminalLog.setText(getString(R.string.terminal_server_not_connected));
            etHiddenInput.setEnabled(false);
        }

        // ★★★ 키보드 입력을 감지하는 TextWatcher 설정
        etHiddenInput.addTextChangedListener(new TextWatcher() {
            private boolean internalChange = false;

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (internalChange) return;

                // 🔥 핵심: 전체 문자열을 그대로 복사
                currentInput.setLength(0);
                currentInput.append(s.toString());

                updateTerminalView();
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });


        // ★★★ 엔터 키 이벤트 처리
        etHiddenInput.setOnEditorActionListener((v, actionId, event) -> {
            boolean isEnter =
                    actionId == EditorInfo.IME_ACTION_SEND ||
                            actionId == EditorInfo.IME_ACTION_DONE ||
                            (event != null &&
                                    event.getKeyCode() == KeyEvent.KEYCODE_ENTER &&
                                    event.getAction() == KeyEvent.ACTION_DOWN);
            if (isEnter) {
                String cmd = currentInput.toString();
                if (!cmd.isEmpty()) {
                    writeToTerminal(cmd + "\n");
                    //terminalBuffer.append(cmd).append("\n");
                }

                currentInput.setLength(0);
                etHiddenInput.getText().clear();
                updateTerminalView();
                return true;
            }
            return false;
        });

        setupInitialTerminalState();
    }

    // ★★★ [신설] 화면을 다시 그리는 메서드
    private void changeFont(int delta) {
        fitTerminal = false;
        terminalFontSize = Math.max(6, Math.min(22, terminalFontSize + delta));
        getPreferences(MODE_PRIVATE).edit().putFloat("terminal_font_size", terminalFontSize).apply();
        applyTerminalWidth();
    }

    private void applyTerminalWidth() {
        float size = terminalFontSize;
        if (fitTerminal && horizontalScroll.getWidth() > 0) {
            android.text.TextPaint measure = new android.text.TextPaint(tvTerminalLog.getPaint());
            measure.setTextSize(10 * getResources().getDisplayMetrics().scaledDensity);
            float available = horizontalScroll.getWidth() - tvTerminalLog.getPaddingLeft() - tvTerminalLog.getPaddingRight();
            size = Math.max(4, Math.min(22, 10 * available / (100 * measure.measureText("M"))));
        }
        tvTerminalLog.setTextSize(size);
        int width = (int)Math.ceil(100 * tvTerminalLog.getPaint().measureText("M")) + tvTerminalLog.getPaddingLeft() + tvTerminalLog.getPaddingRight();
        android.view.ViewGroup.LayoutParams layout = tvTerminalLog.getLayoutParams();
        if (layout.width != width) { layout.width = width; tvTerminalLog.setLayoutParams(layout); }
        if (fitTerminal) horizontalScroll.scrollTo(0, 0);
    }

    private void sendControl(String key) {
        // Flush pending input before special keys, preserving shell input order.
        String pending = currentInput.toString();
        currentInput.setLength(0);
        etHiddenInput.getText().clear();
        writeToTerminal(pending + key);
        updateTerminalView();
    }

    private void updateTerminalView() {
        TerminalScreen.Snapshot snapshot = terminalScreen.snapshot();
        android.text.SpannableStringBuilder colored = new android.text.SpannableStringBuilder(snapshot.text);
        for (TerminalScreen.Run run : snapshot.runs) {
            TerminalScreen.Style style = run.style;
            int fg = style.foreground < 0 ? 0xcccccc : style.foreground;
            int bg = style.background < 0 ? 0 : style.background;
            if (style.inverse) { int swap = fg; fg = bg; bg = swap; }
            colored.setSpan(new ForegroundColorSpan(0xff000000 | fg), run.start, run.end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (bg != 0 || style.background >= 0 || style.inverse) colored.setSpan(new android.text.style.BackgroundColorSpan(0xff000000 | bg), run.start, run.end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (style.bold) colored.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), run.start, run.end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (style.underline) colored.setSpan(new android.text.style.UnderlineSpan(), run.start, run.end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        int inputAt = colored.length();
        colored.append(currentInput).append("▌");
        colored.setSpan(new ForegroundColorSpan(0xff88bbff), inputAt, colored.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tvTerminalLog.setText(colored);
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
    }

    private void setupInitialTerminalState() {
        // 1. 화면의 메인 TextView(또는 그 부모인 ScrollView)를 클릭하면,
        //    숨겨진 EditText에 포커스를 주고 키보드를 올립니다.
        View.OnClickListener focusListener = v -> {
            etHiddenInput.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(etHiddenInput, InputMethodManager.SHOW_IMPLICIT);
            }
        };

        tvTerminalLog.setOnClickListener(focusListener);
        scrollView.setOnClickListener(focusListener);

        //2. 화면이 처음 열릴 때, 터미널 세션을 시작합니다.
        if (serverUrl != null) {
            Log.d(TAG, "Starting a new terminal session.");
            startNewTerminalSession();
        }

        // 3. (추가) 화면이 완전히 그려진 후에 키보드를 올리도록 시도합니다.
        //    이것이 가장 확실하게 키보드를 올리는 방법 중 하나입니다.
        etHiddenInput.postDelayed(() -> {
            etHiddenInput.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(etHiddenInput, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 100); // 100ms 정도의 딜레이를 주어 UI가 그려질 시간을 확보
    }

    // ★★★ [신설] 새로운 PTY 세션을 시작하는 메서드
    private void startNewTerminalSession() {
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(serverUrl + "/cmd/terminal_start");
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(10000);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true); // 내용이 없더라도 POST 요청을 위해 필요

                if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    String response = getResponseFromUrlConnection(conn);
                    JSONObject resJson = new JSONObject(response);
                    this.terminalSessionUuid = resJson.optString("id");
                    if (this.terminalSessionUuid != null && !this.terminalSessionUuid.isEmpty()) {
                        runOnUiThread(() -> {
                            if (screenStarted && !isDestroyed()) pollLogs(terminalSessionUuid);
                        });
                    } else {
                        logToTerminal("Failed to start terminal session.");
                    }
                } else {
                    logToTerminal("Error starting session: " + conn.getResponseCode());
                }
            } catch (Exception e) {
                logToTerminal("Error: " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    // ★★★ [신설] 사용자의 입력을 PTY에 써주는 메서드
    private void writeToTerminal(String input) {
        final String sessionId = terminalSessionUuid;
        if (sessionId == null || sessionId.isEmpty()) return;
        inputExecutor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(serverUrl + "/cmd/terminal_write");
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(10000);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                JSONObject json = new JSONObject();
                json.put("id", sessionId);
                json.put("input", input);

                try (DataOutputStream os =
                             new DataOutputStream(conn.getOutputStream())) {
                    os.write(json.toString().getBytes(StandardCharsets.UTF_8));
                }

                Log.e(TAG, "WRITE response=" + conn.getResponseCode());
            } catch (Exception e) {
                Log.e(TAG, "WRITE ERROR", e);
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private synchronized void pollLogs(String uuid) {
        if (isPolling || !screenStarted || executor.isShutdown()) return;
        isPolling = true;
        final int generation = ++pollingGeneration;
        pollingTask = executor.submit(() -> {
            try {
                while (screenStarted && generation == pollingGeneration && !Thread.currentThread().isInterrupted()) {
                    HttpURLConnection conn = (HttpURLConnection) new URL(
                            serverUrl + "/cmd/terminal_read?id=" + uuid).openConnection();
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(10000);
                    conn.setRequestMethod("GET");
                    synchronized (this) {
                        if (generation != pollingGeneration || !screenStarted) {
                            conn.disconnect();
                            break;
                        }
                        pollingConnection = conn;
                    }
                    try {
                        if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                            throw new java.io.IOException("HTTP " + conn.getResponseCode());
                        }
                        JSONObject logObj = new JSONObject(getResponseFromUrlConnection(conn));
                        String output = logObj.optString("output", "");
                        if (!output.isEmpty()) {
                            final String newOutput = output.replaceAll("\u001B\\[\\?2004[hl]", "");
                            runOnUiThread(() -> {
                                if (!isDestroyed() && generation == pollingGeneration) {
                                    terminalScreen.feed(newOutput);
                                    if (!colorSetupSent) {
                                        colorSetupSent = true;
                                        // This alias belongs only to this interactive PTY session.
                                        writeToTerminal("alias ls='ls --color=auto'\n");
                                    }
                                    updateTerminalView();
                                }
                            });
                        }
                        if (logObj.optBoolean("done", false)) break;
                    } finally {
                        conn.disconnect();
                        synchronized (this) {
                            if (pollingConnection == conn) pollingConnection = null;
                        }
                    }
                    Thread.sleep(200);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                if (generation == pollingGeneration) Log.e(TAG, "pollLogs error", e);
            } finally {
                synchronized (this) {
                    if (generation == pollingGeneration) isPolling = false;
                }
            }
        });
    }

    private synchronized void stopPolling() {
        screenStarted = false;
        ++pollingGeneration;
        isPolling = false;
        if (pollingTask != null) {
            pollingTask.cancel(true);
            pollingTask = null;
        }
        if (pollingConnection != null) {
            pollingConnection.disconnect();
            pollingConnection = null;
        }
    }

    private void logToTerminal(String message) {
        runOnUiThread(() -> {
            tvTerminalLog.append(message + "\n");
            scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
        });
    }

    // 네트워크 응답을 문자열로 읽는 헬퍼 메서드
    private String getResponseFromUrlConnection(HttpURLConnection conn) throws java.io.IOException {
        // 1. 응답 코드를 먼저 확인합니다.
        int responseCode = conn.getResponseCode();

        // 2. 응답이 성공(2xx)인지, 실패(4xx, 5xx)인지에 따라 다른 스트림을 사용합니다.
        InputStream inputStream = (responseCode >= 200 && responseCode < 300)
                ? conn.getInputStream()
                : conn.getErrorStream();

        // 3. 스트림이 null일 경우를 대비합니다.
        if (inputStream == null) {
            return "";
        }

        StringBuilder response = new StringBuilder();
        // 4. UTF-8 인코딩으로 스트림을 읽는 InputStreamReader를 생성합니다.
        try (InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
            // 5. 4096 크기의 버퍼(char 배열)를 만듭니다.
            char[] buffer = new char[4096];
            int bytesRead;

            // ★★★ [핵심] 스트림이 닫힐 때까지 기다리는 대신, 버퍼에 읽을 수 있는 만큼만 읽어옵니다. ★★★
            while ((bytesRead = reader.read(buffer)) != -1) {
                response.append(buffer, 0, bytesRead);
            }
        } finally {
            if (conn != null) {
                conn.disconnect(); // 6. 모든 작업이 끝나면 연결을 확실히 닫습니다.
            }
        }
        return response.toString();
    }

    @Override
    protected void onStart() {
        super.onStart();
        screenStarted = true;

        // 🔥 핵심: 액티비티가 다시 시작될 때마다, 깨끗한 새 스레드 풀을 생성합니다.
        // 이전에 onStop()에서 종료된 executor를 대체합니다.
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadExecutor();
        }

        // 만약 세션 ID가 남아있고, 폴링이 중단된 상태라면 다시 폴링을 시작합니다.
        if (terminalSessionUuid != null && !isPolling) {
            pollLogs(terminalSessionUuid);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopPolling();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // 1. 폴링을 먼저 중단합니다.
        stopPolling();
        inputExecutor.shutdownNow();

        // 🔥 핵심: executor가 종료되기 전에, 마지막으로 세션 종료 요청을 보냅니다.
        if (terminalSessionUuid != null && !terminalSessionUuid.isEmpty()) {
            // 이 작업은 앱이 종료되는 과정이므로, 응답을 기다리지 않고 요청만 보냅니다.
            new Thread(() -> {
                try {
                    URL url = new URL(serverUrl + "/cmd/terminal_close");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(2000); // 짧은 타임아웃
                    conn.setReadTimeout(2000);

                    JSONObject json = new JSONObject();
                    json.put("id", terminalSessionUuid);

                    try (DataOutputStream os = new DataOutputStream(conn.getOutputStream())) {
                        os.write(json.toString().getBytes(StandardCharsets.UTF_8));
                    }

                    // 응답 코드만 확인하고 끝냅니다.
                    int responseCode = conn.getResponseCode();
                    Log.d(TAG, "Terminal close response code=" + responseCode);

                } catch (Exception e) {
                    Log.e(TAG, "Error in terminal_close during onDestroy", e);
                }
            }).start();
        }

        // 2. 스레드 풀을 안전하게 종료합니다.
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
    }

}
