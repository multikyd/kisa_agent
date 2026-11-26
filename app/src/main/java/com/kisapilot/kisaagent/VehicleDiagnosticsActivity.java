package com.kisapilot.kisaagent;

import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class VehicleDiagnosticsActivity extends AppCompatActivity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final StringBuilder trace = new StringBuilder();
    private Elm327Client client;
    private boolean busy;
    private int generation;
    private Button connectButton, standardButton, dtcButton, batteryButton, cellsButton;
    private TextView connection, standardResult, dtcResult, batteryResult, cellsResult;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(16);
        root.setPadding(padding, padding, padding, padding);
        scroll.addView(root);
        setContentView(scroll);
        ScreenInsets.apply(scroll);

        button(root, "‹ 돌아가기", this::finish);
        text(root, "차량 진단", 25);
        button(root, "블루투스 설정", () -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        connectButton = button(root, "OBD 연결", this::chooseDevice);
        button(root, "연결 종료", this::disconnect);
        connection = text(root, "연결 안 됨", 16);

        standardButton = button(root, "차량 데이터 조회", () -> runQuery(standardResult, this::readStandard));
        standardResult = text(root, "", 16);
        dtcButton = button(root, "고장 코드 조회", () -> runQuery(dtcResult, this::readDtcs));
        dtcResult = text(root, "", 16);
        batteryButton = button(root, "배터리 상세 조회", () -> runQuery(batteryResult, this::readBattery));
        batteryResult = text(root, "", 16);
        cellsButton = button(root, "", this::toggleCells);
        cellsResult = text(root, "", 16);
        resetResults();

        button(root, "진단 결과 복사", () -> ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("차량 진단", report())));
        button(root, "진단 결과 파일 저장", this::saveReport);
        updateButtons();
    }

    @Override protected void onPause() {
        disconnect();
        super.onPause();
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private int dp(int size) {
        return Math.round(size * getResources().getDisplayMetrics().density);
    }

    private TextView text(LinearLayout parent, String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setPadding(0, dp(7), 0, dp(7));
        parent.addView(view);
        return view;
    }

    private Button button(LinearLayout parent, String value, Runnable action) {
        Button view = new Button(this);
        view.setText(value);
        parent.addView(view);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private void chooseDevice() {
        if (busy) return;
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 701);
            return;
        }
        try {
            BluetoothManager manager = getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                connection.setText("블루투스를 켜 주세요.");
                return;
            }
            List<BluetoothDevice> devices = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                if (device.getType() == BluetoothDevice.DEVICE_TYPE_LE) continue;
                devices.add(device);
                String address = device.getAddress();
                String name = device.getName();
                names.add((name == null ? "이름 없음" : name) + " · " + address.substring(address.length() - 5));
            }
            if (devices.isEmpty()) {
                connection.setText("블루투스 설정에서 진단 장치를 먼저 페어링해 주세요.");
                return;
            }
            new AlertDialog.Builder(this).setTitle("연결할 기기 선택")
                    .setItems(names.toArray(new String[0]), (dialog, index) -> connect(devices.get(index)))
                    .setNegativeButton("취소", null).show();
        } catch (SecurityException e) {
            connection.setText("근처 기기 연결 권한이 필요합니다.");
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 701 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) chooseDevice();
    }

    private void connect(BluetoothDevice device) {
        disconnect();
        resetResults();
        Elm327Client session = new Elm327Client();
        client = session;
        int token = generation;
        busy = true;
        updateButtons();
        connection.setText("연결 중…");
        worker.execute(() -> {
            synchronized (trace) {
                trace.setLength(0);
            }
            try {
                session.connect(device);
                session.query("ATZ");
                for (String command : new String[]{"ATE0", "ATL0", "ATH1", "ATS1", "ATD0", "ATSP6",
                        "ATCAF1", "ATCFC1", "ATSTFF"}) setup(session, command);
                done(token, () -> connection.setText("OBD 연결됨"));
            } catch (Exception e) {
                session.close();
                done(token, () -> connection.setText("연결 실패 · " + e.getMessage()));
            } finally {
                finishQuery(token);
            }
        });
    }

    private String query(Elm327Client session, String command) throws IOException {
        String response = session.query(command);
        String entry = "[" + command + "]\n" + response.trim() + "\n";
        synchronized (trace) {
            while (trace.length() + entry.length() > 131072) {
                int next = trace.indexOf("\n[");
                trace.delete(0, next < 0 ? trace.length() : next + 1);
            }
            trace.append(entry);
        }
        return response;
    }

    private void setup(Elm327Client session, String command) throws IOException {
        if (!session.query(command).toUpperCase(Locale.ROOT).contains("OK")) {
            throw new IOException("진단 장치 설정 실패 (" + command + ")");
        }
    }

    private void standardHeader(Elm327Client session) throws IOException {
        setup(session, "ATCRA");
        setup(session, "ATSH7DF");
    }

    private interface ReadQuery {
        QueryResult read(Elm327Client session) throws IOException;
    }

    private static final class QueryResult {
        final String text;
        final Map<Integer, String> cells;

        QueryResult(String text) {
            this(text, Collections.emptyMap());
        }

        QueryResult(String text, Map<Integer, String> cells) {
            this.text = text;
            this.cells = cells;
        }
    }

    private void runQuery(TextView output, ReadQuery query) {
        Elm327Client session = client;
        if (busy || session == null || !session.connected()) return;
        int token = generation;
        busy = true;
        updateButtons();
        setResult(output, "조회 중…");
        if (output == batteryResult) setCells(Collections.emptyMap());
        worker.execute(() -> {
            try {
                QueryResult result = query.read(session);
                String timestamp = DateFormat.getDateTimeInstance().format(new Date());
                done(token, () -> {
                    connection.setText(session.connected() ? "OBD 연결됨" : "연결 끊김");
                    setResult(output, "조회 시각  " + timestamp + "\n\n" + result.text);
                    if (output == batteryResult) setCells(result.cells);
                });
            } catch (Exception e) {
                session.close();
                done(token, () -> {
                    connection.setText("연결 끊김");
                    setResult(output, "조회 실패 · " + e.getMessage());
                });
            } finally {
                finishQuery(token);
            }
        });
    }

    private QueryResult readStandard(Elm327Client session) throws IOException {
        standardHeader(session);
        Set<Integer> supported = new HashSet<>();
        for (int base = 0; base <= 0xA0; base += 0x20) {
            if (base != 0 && !supported.contains(base)) break;
            ObdVehicleData.supportedPids(query(session, standardCommand(base)), base, supported);
        }
        StringBuilder data = new StringBuilder();
        int monitor = ObdVehicleData.MONITOR_STATUS;
        if (supported.contains(monitor)) {
            for (Map.Entry<String, byte[]> reply : ObdVehicleData.replies(
                    query(session, standardCommand(monitor)), 0x41, monitor).entrySet()) {
                byte[] bytes = reply.getValue();
                if (bytes.length != 4) continue;
                data.append("고장 표시등  ").append((bytes[0] & 0x80) == 0 ? "꺼짐" : "켜짐")
                        .append(" (ECU ").append(reply.getKey()).append(")\n")
                        .append("보고된 고장 코드 수  ").append(bytes[0] & 0x7F)
                        .append(" (ECU ").append(reply.getKey()).append(")\n");
            }
        }
        for (Map.Entry<Integer, ObdVehicleData.Signal> entry : ObdVehicleData.STANDARD.entrySet()) {
            int pid = entry.getKey();
            if (!supported.contains(pid)) continue;
            ObdVehicleData.Signal signal = entry.getValue();
            for (Map.Entry<String, byte[]> reply : ObdVehicleData.replies(
                    query(session, standardCommand(pid)), 0x41, pid).entrySet()) {
                String value = signal.value(reply.getValue());
                if (value != null) data.append(signal.label).append("  ").append(value)
                        .append(" (ECU ").append(reply.getKey()).append(")\n");
            }
        }
        StringBuilder vehicle = new StringBuilder();
        for (Map.Entry<String, byte[]> reply : ObdVehicleData.replies(
                query(session, ObdVehicleData.VIN), 0x49, 2).entrySet()) {
            String vin = ObdVehicleData.vin(reply.getValue());
            if (vin != null) vehicle.append("차대번호  ").append(vin)
                    .append(" (ECU ").append(reply.getKey()).append(")\n");
        }
        StringBuilder result = new StringBuilder();
        if (data.length() > 0) result.append("차량 데이터\n").append(data).append('\n');
        if (vehicle.length() > 0) result.append("차량 정보\n").append(vehicle);
        return new QueryResult(result.length() == 0 ? "조회된 차량 데이터가 없습니다." : result.toString().trim());
    }

    private static String standardCommand(int pid) {
        return String.format(Locale.ROOT, "01%02X", pid);
    }

    private QueryResult readDtcs(Elm327Client session) throws IOException {
        standardHeader(session);
        StringBuilder result = new StringBuilder();
        String[] commands = {"03", "07", "0A"};
        String[] labels = {"저장 코드", "대기 코드", "영구 코드"};
        for (int i = 0; i < commands.length; i++) {
            Elm327Protocol.Result decoded = Elm327Protocol.dtcs(query(session, commands[i]),
                    Integer.parseInt(commands[i], 16));
            if (i > 0) result.append("\n\n");
            result.append(labels[i]).append(" · ").append(decoded.status());
            for (Map.Entry<String, List<String>> ecu : decoded.codes.entrySet()) {
                result.append("\nECU ").append(ecu.getKey());
                if (ecu.getValue().isEmpty()) result.append("  보고된 코드 없음");
                for (String code : ecu.getValue()) result.append("\n  ").append(code);
            }
        }
        return new QueryResult(result.toString());
    }

    private QueryResult readBattery(Elm327Client session) throws IOException {
        setup(session, "ATSH7E4");
        setup(session, "ATCRA7EC");
        ObdVehicleData.Battery battery = new ObdVehicleData.Battery();
        IOException interruption = null;
        try {
            battery.add(ObdVehicleData.BATTERY_MAIN, query(session, ObdVehicleData.BATTERY_MAIN));
            battery.add(ObdVehicleData.BATTERY_EXTRA, query(session, ObdVehicleData.BATTERY_EXTRA));
            for (String command : ObdVehicleData.CELL_BLOCKS.keySet()) battery.add(command, query(session, command));
        } catch (IOException e) {
            session.close();
            interruption = e;
        }
        StringBuilder result = new StringBuilder();
        appendSection(result, "배터리 팩", battery.pack);
        appendSection(result, "배터리 온도", battery.temperatures);
        appendSection(result, "충·방전 누계", battery.totals);
        if (!battery.cells.isEmpty()) result.append("셀 전압  ").append(battery.cells.size()).append("개 수신\n\n");
        if (result.length() == 0) {
            if (interruption != null) throw interruption;
            result.append("해석할 수 있는 배터리 응답이 없습니다.");
        } else if (interruption != null) {
            result.append("일부 항목 조회 중단 · ").append(interruption.getMessage());
        }
        return new QueryResult(result.toString().trim(), battery.cells);
    }

    private static void appendSection(StringBuilder result, String title, Map<String, String> values) {
        if (values.isEmpty()) return;
        result.append(title).append('\n');
        values.forEach((label, value) -> result.append(label).append("  ").append(value).append('\n'));
        result.append('\n');
    }

    private void setCells(Map<Integer, String> cells) {
        StringBuilder lines = new StringBuilder();
        cells.forEach((number, value) -> lines.append("셀 ").append(number).append("  ").append(value).append('\n'));
        cellsResult.setText(lines.toString().trim());
        cellsResult.setVisibility(View.GONE);
        cellsButton.setText("셀 전압 펼치기 (" + cells.size() + ")");
        cellsButton.setVisibility(cells.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void toggleCells() {
        boolean expand = cellsResult.getVisibility() != View.VISIBLE;
        cellsResult.setVisibility(expand ? View.VISIBLE : View.GONE);
        cellsButton.setText(expand ? "셀 전압 접기" : "셀 전압 펼치기");
    }

    private void resetResults() {
        setResult(standardResult, "");
        setResult(dtcResult, "");
        setResult(batteryResult, "");
        setCells(Collections.emptyMap());
    }

    private void setResult(TextView view, String value) {
        view.setText(value);
        view.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void done(int token, Runnable action) {
        main.post(() -> {
            if (generation == token) action.run();
        });
    }

    private void finishQuery(int token) {
        done(token, () -> {
            busy = false;
            updateButtons();
        });
    }

    private void updateButtons() {
        connectButton.setEnabled(!busy);
        boolean connected = !busy && client != null && client.connected();
        standardButton.setEnabled(connected);
        dtcButton.setEnabled(connected);
        batteryButton.setEnabled(connected);
    }

    private void disconnect() {
        generation++;
        if (client != null) client.close();
        client = null;
        busy = false;
        if (connection != null) connection.setText("연결 안 됨");
        if (connectButton != null) updateButtons();
    }

    private String report() {
        StringBuilder report = new StringBuilder("차량 진단\n");
        for (TextView section : new TextView[]{standardResult, dtcResult, batteryResult, cellsResult}) {
            if (section.length() > 0) report.append('\n').append(section.getText()).append('\n');
        }
        synchronized (trace) {
            if (trace.length() > 0) report.append("\n차량 응답 원문\n").append(trace);
        }
        return report.toString();
    }

    private void saveReport() {
        try {
            File storage = getExternalFilesDir(null);
            if (storage == null) throw new IOException("저장공간을 사용할 수 없습니다.");
            File folder = new File(storage, "kisa_vehicle_diagnostics");
            if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("폴더 생성 실패");
            File file = new File(folder, "diagnostics-" + System.currentTimeMillis() + ".txt");
            try (OutputStream out = new FileOutputStream(file)) {
                out.write(report().getBytes(StandardCharsets.UTF_8));
            }
            Toast.makeText(this, "저장: " + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Toast.makeText(this, "저장 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
