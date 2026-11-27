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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class VehicleDiagnosticsActivity extends AppCompatActivity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final StringBuilder trace = new StringBuilder();
    private Elm327Client client;
    private boolean busy;
    private boolean traceTruncated;
    private int generation;
    private Button connectButton, standardButton, dtcButton, batteryButton, cellsButton, allButton, rawButton;
    private TextView connection, standardResult, dtcResult, batteryResult, cellsResult, allResult, rawResult;

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

        allButton = button(root, "전체 진단 RAW 수집", () -> {
            resetResults();
            synchronized (trace) { trace.setLength(0); traceTruncated = false; }
            runQuery(allResult, this::readAll);
        });
        text(root, "지원 PID 전체 → 고장 코드 → 배터리·셀 전압 순서로 수집합니다. 수집 중에는 이 화면을 유지해 주세요. 중단해도 받은 RAW는 저장할 수 있습니다.", 14);
        allResult = text(root, "", 16);
        standardButton = button(root, "차량 PID 전체 조회", () -> runQuery(standardResult, this::readStandard));
        standardResult = text(root, "", 16);
        dtcButton = button(root, "고장 코드 조회", () -> runQuery(dtcResult, this::readDtcs));
        dtcResult = text(root, "", 16);
        batteryButton = button(root, "배터리 상세 조회", () -> runQuery(batteryResult, this::readBattery));
        batteryResult = text(root, "", 16);
        cellsButton = button(root, "", this::toggleCells);
        cellsResult = text(root, "", 16);
        rawButton = button(root, "차량 응답 RAW 펼치기", () -> {
            boolean expand = rawResult.getVisibility() != View.VISIBLE;
            rawResult.setText(rawReport());
            rawResult.setVisibility(expand ? View.VISIBLE : View.GONE);
            rawButton.setText(expand ? "차량 응답 RAW 접기" : "차량 응답 RAW 펼치기");
        });
        rawResult = text(root, "", 13);
        rawResult.setTextIsSelectable(true);
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
                traceTruncated = false;
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
        main.post(() -> {
            if (client == session && busy) connection.setText("수집 중 · " + command);
        });
        try {
            String response = session.query(command);
            appendTrace("[" + command + "]\n" + response + "\n");
            return response;
        } catch (IOException e) {
            appendTrace("[" + command + "]\n조회 실패 · " + e.getMessage() + "\n");
            throw e;
        }
    }

    private void appendTrace(String entry) {
        synchronized (trace) {
            while (trace.length() + entry.length() > 4 * 1024 * 1024) {
                int next = trace.indexOf("\n[");
                trace.delete(0, next < 0 ? trace.length() : next + 1);
                traceTruncated = true;
            }
            trace.append(entry);
        }
    }

    private String rawReport() {
        synchronized (trace) {
            return (traceTruncated ? "RAW 용량 제한으로 이전 응답 일부 생략\n" : "") + trace;
        }
    }

    private void setup(Elm327Client session, String command) throws IOException {
        if (!query(session, command).toUpperCase(Locale.ROOT).contains("OK")) {
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
        final String standardText, dtcText;

        QueryResult(String text) {
            this(text, Collections.emptyMap());
        }

        QueryResult(String text, Map<Integer, String> cells) {
            this(text, cells, null, null);
        }

        QueryResult(String text, Map<Integer, String> cells, String standardText, String dtcText) {
            this.text = text;
            this.cells = cells;
            this.standardText = standardText;
            this.dtcText = dtcText;
        }
    }

    private void runQuery(TextView output, ReadQuery query) {
        Elm327Client session = client;
        if (busy || session == null || !session.connected()) return;
        int token = generation;
        busy = true;
        updateButtons();
        setResult(output, "조회 중…");
        if (output == batteryResult || output == allResult) setCells(Collections.emptyMap());
        worker.execute(() -> {
            try {
                QueryResult result = query.read(session);
                String timestamp = DateFormat.getDateTimeInstance().format(new Date());
                done(token, () -> {
                    connection.setText(session.connected() ? "OBD 연결됨" : "연결 끊김");
                    String time = "조회 시각  " + timestamp + "\n\n";
                    if (output == allResult) {
                        setResult(allResult, "전체 진단 수집 결과 · " + timestamp);
                        setResult(standardResult, time + result.standardText);
                        setResult(dtcResult, time + result.dtcText);
                        setResult(batteryResult, time + result.text);
                    } else {
                        setResult(output, time + result.text);
                    }
                    if (output == batteryResult || output == allResult) setCells(result.cells);
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
        return new QueryResult(StandardPidScan.read(command -> query(session, command)));
    }

    private QueryResult readAll(Elm327Client session) throws IOException {
        query(session, "ATDP");
        query(session, "ATRV");
        QueryResult standard = readStandard(session);
        QueryResult dtcs = readDtcs(session);
        QueryResult battery = readBattery(session);
        return new QueryResult(battery.text, battery.cells, standard.text, dtcs.text);
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
        appendSection(result, "충전·배터리 제어 상태", battery.status);
        appendSection(result, "배터리 온도", battery.temperatures);
        appendSection(result, "충·방전 누계", battery.totals);
        if (result.length() == 0 && battery.cells.isEmpty()) {
            result.append("해석할 수 있는 배터리 응답이 없습니다.\n");
        }
        appendSection(result, "셀 전압 요약", battery.cellSummary());
        result.append("셀 전압  ").append(battery.cells.size()).append("개 수신\n");
        if (battery.cells.isEmpty()) result.append("개별 셀 응답 미수신 · RAW에서 응답·미지원 여부 확인\n");
        if (interruption != null) {
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
        cellsResult.setVisibility(cells.isEmpty() ? View.GONE : View.VISIBLE);
        cellsButton.setText("셀 전압 접기 (" + cells.size() + ")");
        cellsButton.setVisibility(cells.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void toggleCells() {
        boolean expand = cellsResult.getVisibility() != View.VISIBLE;
        cellsResult.setVisibility(expand ? View.VISIBLE : View.GONE);
        cellsButton.setText(expand ? "셀 전압 접기" : "셀 전압 펼치기");
    }

    private void resetResults() {
        setResult(allResult, "");
        rawResult.setText("");
        rawResult.setVisibility(View.GONE);
        rawButton.setText("차량 응답 RAW 펼치기");
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
            rawResult.setText(rawReport());
            updateButtons();
        });
    }

    private void updateButtons() {
        connectButton.setEnabled(!busy);
        boolean connected = !busy && client != null && client.connected();
        allButton.setEnabled(connected);
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
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            report.append("앱 버전  ").append(info.versionName).append('\n');
        } catch (PackageManager.NameNotFoundException ignored) { }
        if (allResult.length() > 0) report.append('\n').append(allResult.getText()).append('\n');
        TextView[] sections = {standardResult, dtcResult, batteryResult, cellsResult};
        String[] titles = {"차량 PID", "고장 코드", "배터리 상세", "개별 셀 전압"};
        for (int i = 0; i < sections.length; i++) {
            if (sections[i].length() > 0) report.append('\n').append(titles[i]).append('\n')
                    .append(sections[i].getText()).append('\n');
        }
        String raw = rawReport();
        if (!raw.isEmpty()) report.append("\n차량 응답 원문\n").append(raw);
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
