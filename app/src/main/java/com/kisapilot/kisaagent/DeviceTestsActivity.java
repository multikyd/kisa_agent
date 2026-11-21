package com.kisapilot.kisaagent;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Stationary, bounded blinker experiment. Future tests have independent cards. */
public class DeviceTestsActivity extends AppCompatActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView connection, conditions, operation;
    private static final int TARGET_ANGLE_LIMIT_DEG = 30;
    private Button left, right;
    private Button lowerTargetAngle, raiseTargetAngle;
    private TextView targetAngle, measuredAngle, steeringPreview;
    private int targetAngleDeg;
    private boolean active, busy, held, ready;
    private String token, pressIp, direction, diagnostics = "아직 진단 정보 없음";
    private long pressUntil;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!active) return;
            if (held && android.os.SystemClock.elapsedRealtime() >= pressUntil) endPress();
            if (held && token != null && !busy) command("renew", token, pressIp);
            else if (!busy) refresh();
            handler.postDelayed(this, held ? 200 : 500);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18); root.setPadding(pad, pad, pad, pad); scroll.addView(root); setContentView(scroll);
        ScreenInsets.apply(scroll);
        Button back = new Button(this); back.setText("‹ 돌아가기"); root.addView(back);
        back.setOnClickListener(v -> finish());
        text(root, "기능 시험", 26);
        connection = text(root, "C4 연결 확인 중…", 17);
        addBlinkerCard(root);
        addSteeringCard(root);
        Button copy = new Button(this); copy.setText("시험 진단 복사"); root.addView(copy);
        copy.setOnClickListener(v -> ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("C4 blinker test", diagnostics)));
    }

    private void addBlinkerCard(LinearLayout root) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(16), dp(12), dp(16)); root.addView(card);
        text(card, "깜빡이 시험", 23);
        text(card, "왼쪽 / 오른쪽 버튼을 누르고 있는 동안만 시험합니다. 한 번에 최대 3초이며, 재시험은 1.1초 후 가능합니다.", 18);
        conditions = text(card, "차량 정보 대기", 17);
        operation = text(card, "대기 · 실제 점등은 차량 관측값으로 확인하세요.", 18);
        operation.setTextColor(Color.rgb(0, 105, 92));
        LinearLayout row = new LinearLayout(this); card.addView(row);
        left = button(row, "◀ 왼쪽"); Button stop = button(row, "종료"); right = button(row, "오른쪽 ▶");
        bindHold(left, "left"); bindHold(right, "right"); stop.setOnClickListener(v -> endPress());
        left.setEnabled(false); right.setEnabled(false);
        text(card, "D단 이동 시험은 TBT 실험 설정을 켜고, 40 km/h 이하에서 방향지시등 레버를 중립에 두면 가능해요. P/D단 정차 또는 D단 저속 이동에서 시험할 수 있어요. 브레이크, 가속페달, 조향·가감속 제어 상태로 버튼을 제한하지 않아요. 두 시험 모두 문 닫힘과 안전벨트 착용이 필요해요.\n순정 주차 기능은 종료해 주세요. 손을 떼거나 화면을 나가면 종료합니다. 연결이 끊기면 요청은 0.65초 이내 만료됩니다.", 16);
    }

    private void addSteeringCard(LinearLayout root) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(16), dp(12), dp(16));
        root.addView(card);
        text(card, "SPAS 조향 시험 준비", 23);
        text(card, "조향 송신 미연결 · 목표각 설정과 실제 핸들 각도 관측", 16);
        targetAngle = text(card, "", 19);
        measuredAngle = text(card, "실제 핸들 각도: 데이터 대기", 19);

        LinearLayout row = new LinearLayout(this);
        card.addView(row);
        lowerTargetAngle = button(row, "← −1°");
        Button reset = button(row, "0°");
        raiseTargetAngle = button(row, "+1° →");
        lowerTargetAngle.setContentDescription("목표각 1도 감소");
        raiseTargetAngle.setContentDescription("목표각 1도 증가");
        reset.setContentDescription("목표각 0도로 초기화");
        lowerTargetAngle.setOnClickListener(v -> setTargetAngle(targetAngleDeg - 1));
        reset.setOnClickListener(v -> setTargetAngle(0));
        raiseTargetAngle.setOnClickListener(v -> setTargetAngle(targetAngleDeg + 1));
        setTargetAngle(0);

        Button preview = new Button(this);
        preview.setText("C4 연결 확인");
        card.addView(preview);
        steeringPreview = text(card, "목표각을 C4에 전달하고 수신값을 확인합니다.", 16);
        preview.setOnClickListener(v -> checkSteeringConnection());
    }

    private void checkSteeringConnection() {
        if (held || busy || !active) {
            steeringPreview.setText("다른 요청이 끝난 뒤 다시 눌러 주세요.");
            return;
        }
        final int target = targetAngleDeg;
        final String ip = HomeActivity.serverIp;
        busy = true;
        steeringPreview.setText("C4 응답 대기…");
        worker.execute(() -> {
            String message;
            try {
                JSONObject body = new JSONObject().put("target_angle_deg", target);
                JSONObject response = exchange(ip, "/tests/steering/preview", body);
                if (!"preview".equals(response.optString("status")) ||
                    response.optBoolean("transmitted", true) ||
                    response.getInt("target_angle_deg") != target) {
                    throw new IllegalStateException("Unexpected steering preview response");
                }
                double measured = response.optDouble("steering_angle_deg", Double.NaN);
                message = "C4 수신 확인 · 목표각 " + target + "°";
                if (Double.isFinite(measured)) {
                    message += String.format(java.util.Locale.getDefault(), " · 실제 %.1f°", measured);
                } else {
                    message += " · 실제 각도 데이터 대기";
                }
            } catch (Exception error) {
                message = "연결 확인 실패 · C4 주소와 업데이트를 확인해 주세요.";
            }
            final String result = message;
            handler.post(() -> {
                busy = false;
                if (active && java.util.Objects.equals(ip, HomeActivity.serverIp)) {
                    steeringPreview.setText(result);
                }
            });
        });
    }

    private void setTargetAngle(int angle) {
        targetAngleDeg = Math.max(-TARGET_ANGLE_LIMIT_DEG, Math.min(TARGET_ANGLE_LIMIT_DEG, angle));
        targetAngle.setText("목표각: " + targetAngleDeg + "°");
        lowerTargetAngle.setEnabled(targetAngleDeg > -TARGET_ANGLE_LIMIT_DEG);
        raiseTargetAngle.setEnabled(targetAngleDeg < TARGET_ANGLE_LIMIT_DEG);
    }

    private void bindHold(Button button, String action) {
        button.setOnTouchListener((v, event) -> {
            int type = event.getActionMasked();
            if (type == MotionEvent.ACTION_DOWN) {
                if (ready && !held && !busy) {
                    held = true; direction = action; pressIp = HomeActivity.serverIp;
                    pressUntil = android.os.SystemClock.elapsedRealtime() + 3000;
                    command(action, null, pressIp);
                    handler.removeCallbacks(tick); handler.postDelayed(tick, 200);
                }
                return true;
            }
            if (type == MotionEvent.ACTION_UP || type == MotionEvent.ACTION_CANCEL ||
                    type == MotionEvent.ACTION_POINTER_DOWN ||
                    (type == MotionEvent.ACTION_MOVE && (event.getX() < 0 || event.getY() < 0 ||
                     event.getX() >= v.getWidth() || event.getY() >= v.getHeight()))) endPress();
            return true;
        });
    }

    private void endPress() {
        held = false;
        if (token != null) {
            String old = token; token = null;
            command("stop", old, pressIp);
        }
        if (operation != null) operation.setText("종료 요청 · 차량 점등 상태를 확인하세요.");
    }

    private JSONObject exchange(String ip, String path, JSONObject body) throws Exception {
        if (ip == null || !ip.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) throw new IllegalStateException("C4 연결 필요");
        HttpURLConnection http = (HttpURLConnection)new URL("http://" + ip + ":5005" + path).openConnection();
        try {
            http.setConnectTimeout(800); http.setReadTimeout(800); http.setUseCaches(false);
            if (body != null) {
                http.setRequestMethod("POST"); http.setDoOutput(true);
                http.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream out = http.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            }
            int code = http.getResponseCode(); StringBuilder data = new StringBuilder();
            java.io.InputStream stream = code >= 400 ? http.getErrorStream() : http.getInputStream();
            if (stream == null) throw new IllegalStateException("HTTP " + code);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line; while ((line = reader.readLine()) != null) {
                    data.append(line); if (data.length() > 32768) throw new IllegalStateException("응답 크기 초과");
                }
            }
            return new JSONObject(data.toString());
        } finally { http.disconnect(); }
    }

    private void refresh() {
        busy = true; final String ip = HomeActivity.serverIp;
        worker.execute(() -> {
            try {
                JSONObject response = exchange(ip, "/tests/capabilities", null);
                if (response.getInt("protocol") != 2) throw new IllegalStateException("C4 시험 API 업데이트 필요");
                handler.post(() -> { busy = false; if (!active) return;
                    if (!ip.equals(HomeActivity.serverIp)) { ready = false; endPress(); return; }
                    showStatus(response); });
            } catch (Exception error) { failed(error); }
        });
    }

    private void command(String action, String session, String ip) {
        if (worker.isShutdown()) { token = null; return; }
        // Stop can queue behind one in-flight request; lease expiry is the fallback.
        if (!action.equals("stop") && busy) return;
        busy = true;
        worker.execute(() -> {
            try {
                JSONObject body = new JSONObject().put("action", action);
                if (session != null) body.put("token", session);
                JSONObject result = exchange(ip, "/tests/blinkers", body);
                // Query observation independently: requested != observed != accepted by Panda.
                JSONObject status = exchange(ip, "/tests/capabilities", null);
                handler.post(() -> {
                    busy = false;
                    String returned = result.optString("token", "");
                    if (!returned.isEmpty()) token = returned;
                    if (!active || !held || !ip.equals(HomeActivity.serverIp)) {
                        if (token != null) endPress(); return;
                    }
                    showStatus(status);
                    String state = result.optString("status", "unknown");
                    if (!state.equals("requested")) {
                        endPress(); operation.setText("시험 중단: " + state);
                    } else operation.setText((direction.equals("left") ? "왼쪽" : "오른쪽") +
                        " CAN 송신 요청 중 · 남은 시간 " + String.format(java.util.Locale.US, "%.1f", result.optDouble("seconds_left")) + "초");
                });
            } catch (Exception error) { failed(error); }
        });
    }

    private void showStatus(JSONObject response) {
        JSONObject p = response.optJSONObject("preflight"); if (p == null) return;
        diagnostics = "KisaAgent blinker test\n" + response.toString();
        double angle = p.optDouble("steering_angle_deg", Double.NaN);
        measuredAngle.setText(p.optBoolean("fresh") && Double.isFinite(angle)
            ? String.format(java.util.Locale.getDefault(), "실제 핸들 각도: %.1f°", angle)
            : "실제 핸들 각도: 데이터 대기");
        JSONObject sender = p.optJSONObject("sender");
        ready = p.optBoolean("conditions_met") && p.optBoolean("panda_test_flag") && sender != null && sender.length() > 0 && response.optDouble("cooldown_seconds", 0) <= 0;
        connection.setText("C4 연결됨 · " + (p.optBoolean("supported") ? "시험 지원 차량" : "차량 코드 / 설정 확인 필요"));
        conditions.setText("기어: " + p.optString("gear", "unknown") +
            "\n조건: " + p.optString("reason") + " · Panda 설정: " + yes(p.optBoolean("panda_test_flag")) +
            "\n관측 점등: 왼쪽 " + yes(p.optBoolean("left_observed")) + " / 오른쪽 " + yes(p.optBoolean("right_observed")) +
            "\n컨트롤러: " + (sender == null ? "대기" : sender.optString("reason")) +
            " · 송신 요청 프레임: " + (sender == null ? 0 : sender.optInt("requested_frames")) +
            "\nPanda 전체 송신 차단 누계: " + p.optLong("panda_tx_blocked"));
        if (!held) { left.setEnabled(ready); right.setEnabled(ready); }
    }

    private void failed(Exception error) {
        handler.post(() -> {
            busy = false; ready = false; held = false; token = null;
            if (!active) return;
            connection.setText("연결 / 응답 오류 · 요청 자동 만료");
            measuredAngle.setText("실제 핸들 각도: 데이터 대기");
            operation.setText(error.getClass().getSimpleName()); left.setEnabled(false); right.setEnabled(false);
            diagnostics = "KisaAgent blinker test: " + error.getClass().getSimpleName();
        });
    }
    private String yes(boolean value) { return value ? "확인" : "아니오"; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private Button button(LinearLayout row, String label) {
        Button button = new Button(this); button.setText(label); button.setTextSize(19);
        row.addView(button, new LinearLayout.LayoutParams(0, dp(88), 1)); return button;
    }
    private TextView text(LinearLayout parent, String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size);
        view.setPadding(0, dp(6), 0, dp(10)); parent.addView(view); return view;
    }
    @Override protected void onResume() { super.onResume(); active = true; handler.post(tick); }
    @Override protected void onPause() { active = false; handler.removeCallbacks(tick); endPress(); super.onPause(); }
    @Override protected void onDestroy() { worker.shutdown(); super.onDestroy(); }
}
