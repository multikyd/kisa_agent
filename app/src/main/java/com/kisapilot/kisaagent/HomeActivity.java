package com.kisapilot.kisaagent;

import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;

import android.content.Context;
import android.net.Uri;
import android.widget.Toast;

import android.graphics.Color;
import android.content.res.ColorStateList;
import androidx.appcompat.app.AlertDialog;
import android.widget.LinearLayout;
import android.view.View;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import android.graphics.Typeface;

import java.net.URL;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import org.json.JSONObject;

import android.content.ClipData;
import android.content.ClipboardManager;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import android.os.Environment;

import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import androidx.appcompat.widget.Toolbar;
import android.view.MotionEvent;
import androidx.appcompat.widget.SwitchCompat;

import android.content.pm.PackageInfo;


public class HomeActivity extends AppCompatActivity {
    private volatile boolean manualConnecting;
    public static volatile String serverIp = "Not connected"; // 전역 변수
    private TextView tvIpStatus;
    private TextView tvConsole;
    private ScrollView scrollViewConsole;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private WifiManager.MulticastLock multicastLock;
    private final List<Thread> udpListenerThreads = new ArrayList<>();

    private AppUpdater appUpdater;
    private volatile boolean isPolling = false;
    private Button btnParam, btnCmd, btnWebUi;
    private Button btnTmuxLog;
    private Button btnRecord;
    private static final String PARAM_KEY_RECORD = "RecordingRunning";
    private Button btnBranch;
    private final java.util.concurrent.atomic.AtomicBoolean branchFetchRunning = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile int branchMonitorGeneration;
    private String[] loadedBranches = null; // 브랜치 목록 저장용
    private Button btnAppRestartTop; // 버튼 변수
    private String[] loadedCommits = null; // 커밋 목록 저장용
    private Button btnGitPull;
    private Button btnGitRestore;
    private Button btnSaveParamsFile, btnLoadParamsFile, btnViewParamsFile;
    private static final int PICK_JSON_FILE_REQUEST = 101; // 파일 선택 요청 코드
    private static final int PICK_JSON_FILE_VIEW_REQUEST = 102; // ★ 확인용 요청 코드 추가

    private Button btnModelSelector;
    private List<String> modelInfoList = new ArrayList<>();
    private Button btnFileExplorer;
    private Button btnDriveLogs;
    private Button btnBookmarks;

    private SwitchCompat nightModeSwitch; // ★ 스위치 변수 추가


    @Override
    protected void onStop() {
        super.onStop();
        if (appUpdater != null) appUpdater.onStop();
        isPolling = false; // ★★★ 액티비티가 보이지 않으면 모든 폴링 중지 ★★★
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        appUpdater = new AppUpdater(this);
        appUpdater.check();
        String savedTheme = ThemeUtil.getSavedTheme(this);
        ThemeUtil.applyTheme(savedTheme);

        setContentView(R.layout.activity_home);

        Toolbar toolbar = findViewById(R.id.toolbar);

        setSupportActionBar(toolbar);

        // 1. 앱 제목 설정
        // 1. 앱 제목 및 로고 설정 (커스텀 액션바 적용)
        if (getSupportActionBar() != null) {
            // 기존 기본 타이틀/로고 비활성화
            getSupportActionBar().setDisplayShowTitleEnabled(false);
            getSupportActionBar().setDisplayShowHomeEnabled(false);
            getSupportActionBar().setDisplayUseLogoEnabled(false);

            // 커스텀 뷰 활성화
            getSupportActionBar().setDisplayShowCustomEnabled(true);

            // 커스텀 레이아웃 적용
            getSupportActionBar().setCustomView(R.layout.custom_actionbar);

            // ★ 중요: 왼쪽 여백(Gap)을 없애기 위해 Toolbar 내부 여백 제거
            androidx.appcompat.widget.Toolbar parent = (androidx.appcompat.widget.Toolbar) getSupportActionBar().getCustomView().getParent();
            if (parent != null) {
                parent.setContentInsetsAbsolute(0, 0);
                parent.setPadding(30, 0, 0, 0); // 필요하다면 여기서 왼쪽 패딩(30)을 조절하세요
            }
        }


        // 2. 뷰 연결
        tvConsole = findViewById(R.id.tv_console);
        scrollViewConsole = findViewById(R.id.scroll_view_console);

        tvIpStatus = findViewById(R.id.tv_ip_status);
        btnParam = findViewById(R.id.btn_param);
        btnCmd = findViewById(R.id.btn_cmd);

        btnWebUi = findViewById(R.id.btn_web_ui);

        btnTmuxLog = findViewById(R.id.btn_tmux_log);

        btnRecord = findViewById(R.id.btn_record);

        btnBranch = findViewById(R.id.btn_branch);
        btnBranch.setOnClickListener(v -> showBranchSelectionDialog());
        btnBranch.setOnLongClickListener(v -> { fetchBranchInfo(); return true; });

        tvIpStatus = findViewById(R.id.tv_ip_status);
        btnGitPull = findViewById(R.id.btn_git_pull);
        btnGitRestore = findViewById(R.id.btn_git_restore);

        btnSaveParamsFile = findViewById(R.id.btn_save_params_file);
        btnLoadParamsFile = findViewById(R.id.btn_load_params_file);
        btnViewParamsFile = findViewById(R.id.btn_view_params_file); // ★ 연결

        btnModelSelector = findViewById(R.id.btn_model_selector);
        btnFileExplorer = findViewById(R.id.btn_file_explorer);
        btnDriveLogs = findViewById(R.id.btn_drive_logs);
        btnBookmarks = findViewById(R.id.btn_bookmarks);
        findViewById(R.id.btn_camera_trial).setOnClickListener(v -> startActivity(new Intent(this, CameraTrialActivity.class)));
        findViewById(R.id.btn_device_tests).setOnClickListener(v -> startActivity(new Intent(this, DeviceTestsActivity.class)));
        findViewById(R.id.btn_vehicle_diagnostics).setOnClickListener(v -> startActivity(new Intent(this, VehicleDiagnosticsActivity.class)));
        btnAppRestartTop = findViewById(R.id.btn_app_restart_top);
        Button btnFingerprint = findViewById(R.id.btn_fingerprint);
        Button btnTerminal = findViewById(R.id.btn_terminal);
        ScrollView consoleScrollView = findViewById(R.id.scroll_view_console);




        btnFileExplorer.setOnClickListener(v -> {
            if (serverIp == null || serverIp.equals("Not connected")) {
                Toast.makeText(this, getString(R.string.msg_connect_first), Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(HomeActivity.this, FileExplorerActivity.class);
            startActivity(intent);
        });


        btnDriveLogs.setOnClickListener(v -> {
            if (serverIp == null || serverIp.equals("Not connected")) {
                Toast.makeText(this, getString(R.string.msg_connect_first), Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(HomeActivity.this, VideoListActivity.class);
            startActivity(intent);
        });


        btnBookmarks.setOnClickListener(v -> {
            if (serverIp == null || serverIp.equals("Not connected")) {
                Toast.makeText(this, getString(R.string.msg_connect_first), Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(HomeActivity.this, BookmarkLogsActivity.class);
            startActivity(intent);
        });



        // 버튼 클릭 이벤트
        btnParam.setOnClickListener(v -> {
            if (serverIp == null || serverIp.equals("Not connected")) {
                Toast.makeText(this, getString(R.string.msg_connect_first), Toast.LENGTH_SHORT).show();
                return;
            }
            //logToConsole("Moving to Parameters screen..."); // 로그 출력 예시
            Intent intent = new Intent(HomeActivity.this, ParamActivity.class);
            startActivity(intent);
        });

        findViewById(R.id.btn_reboot_safe).setOnClickListener(v -> showRebootConfirmation(false));
        findViewById(R.id.btn_reboot_force).setOnClickListener(v -> showRebootConfirmation(true));
        btnCmd.setOnClickListener(v -> {
            if (serverIp == null || serverIp.equals("Not connected")) {
                Toast.makeText(this, getString(R.string.msg_connect_first), Toast.LENGTH_SHORT).show();
                return;
            }
            //logToConsole("Moving to Commands screen..."); // 로그 출력 예시
            Intent intent = new Intent(HomeActivity.this, CmdActivity.class);
            startActivity(intent);
        });

        btnTmuxLog.setOnClickListener(v -> {
            if (serverIp == null || serverIp.equals("Not connected")) {
                Toast.makeText(this, getString(R.string.msg_connect_first), Toast.LENGTH_SHORT).show();
                return;
            }

            // 기존 fetchTmuxLog() 호출 대신 액티비티 전환
            Intent intent = new Intent(HomeActivity.this, LogActivity.class);
            startActivity(intent);
        });





        btnAppRestartTop.setOnClickListener(v -> showRestartConfirmationDialog());

        btnGitPull.setOnClickListener(v -> showGitPullConfirmation());

        btnGitPull.setOnLongClickListener(v -> {
            showGitPullForceConfirmation();
            return true; // true를 반환해야 짧은 클릭 이벤트가 함께 호출되는 것을 막습니다.
        });

        btnGitRestore.setOnClickListener(v -> fetchGitCommitsAndShowDialog());
        btnGitRestore.setOnLongClickListener(v -> {
            showGitResetConfirmationDialog();
            return true; // true를 반환해야 짧게 누르기(onClick) 이벤트가 무시됩니다.
        });

        btnSaveParamsFile.setOnClickListener(v -> showSaveConfirmationDialog());

        btnLoadParamsFile.setOnClickListener(v -> openFilePicker());
        btnViewParamsFile.setOnClickListener(v -> openFileViewer());

        btnFingerprint.setOnClickListener(v -> executeFingerprint());

        // HomeActivity.java의 onCreate 메서드 내부

        btnTerminal.setOnClickListener(v -> {
            if (serverIp == null || serverIp.equals("Not connected")) {
                Toast.makeText(this, getString(R.string.msg_connect_first), Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(HomeActivity.this, TerminalActivity.class);
            intent.putExtra("SERVER_IP", serverIp);
            startActivity(intent);
        });

        // 2. 터치 리스너를 설정합니다.
        consoleScrollView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                // 부모 스크롤뷰(화면 전체)가 스크롤 이벤트를 가로채지 못하도록 요청합니다.
                // true: 자식 뷰(콘솔)가 이벤트를 처리하겠다.
                // false: 부모 뷰에게 이벤트를 넘기겠다.
                v.getParent().requestDisallowInterceptTouchEvent(true);
                return false; // onTouch 이벤트를 계속 전파하여 정상적인 스크롤이 가능하도록 false를 반환합니다.
            }
        });
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // 1. 우리가 만든 home_menu.xml 파일을 가져와서 실제 메뉴로 만듭니다.
        getMenuInflater().inflate(R.menu.home_menu, menu);

        // 2. 메뉴 아이템(스위치)을 찾습니다.
        MenuItem item = menu.findItem(R.id.action_night_mode);

        // 3. 스위치의 레이아웃에서 실제 스위치 뷰(View)를 찾습니다.
        nightModeSwitch = item.getActionView().findViewById(R.id.switch_compat);

        // 4. 현재 저장된 테마 설정을 불러와 스위치의 체크 상태를 초기화합니다.
        //    (앱을 껐다 켜도 마지막 상태를 기억하게 함)
        String savedTheme = ThemeUtil.getSavedTheme(this);
        nightModeSwitch.setChecked(savedTheme.equals(ThemeUtil.DARK_MODE));

        // 5. 스위치의 체크 상태가 변경될 때의 동작을 정의합니다.
        nightModeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                // 스위치가 켜지면 -> 다크 모드로 변경하고 그 선택을 저장합니다.
                ThemeUtil.applyTheme(ThemeUtil.DARK_MODE);
                ThemeUtil.saveTheme(this, ThemeUtil.DARK_MODE);
            } else {
                // 스위치가 꺼지면 -> 라이트 모드로 변경하고 그 선택을 저장합니다.
                ThemeUtil.applyTheme(ThemeUtil.LIGHT_MODE);
                ThemeUtil.saveTheme(this, ThemeUtil.LIGHT_MODE);
            }
        });
        checkNetworkAndConnect();
        return true; // 메뉴 생성을 완료했음을 알림
    }

    // ★★★ [신설] 2. 메뉴 아이템이 클릭되었을 때의 동작을 정의하는 메서드 ★★★
    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        // 클릭된 메뉴 아이템의 ID를 확인합니다.
        if (item.getItemId() == R.id.action_manual_connect) {
            showManualConnectionDialog();
            return true;
        }
        if (item.getItemId() == R.id.action_app_info) {
            // '앱 정보' 메뉴가 클릭되었다면, 정보 다이얼로그를 보여주는 함수를 호출합니다.
            showAppInfoDialog();
            return true; // 이벤트 처리를 완료했음을 알림
        }
        return super.onOptionsItemSelected(item);
    }

    // ★★★ [신설] 3. '앱 정보' 다이얼로그를 실제로 보여주는 메서드 ★★★
    private void showRebootConfirmation(boolean force) {
        if (serverIp == null || "Not connected".equals(serverIp)) {
            Toast.makeText(this, R.string.msg_connect_first, Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle(force ? R.string.btn_reboot_force : R.string.btn_reboot_safe)
                .setMessage(R.string.reboot_confirm_disengage)
                .setPositiveButton(R.string.action_yes, (d, w) -> sendCommandAndPollLog(force ? "sudo_reboot" : "reboot"))
                .setNegativeButton(R.string.action_no, null).show();
    }

    private void showManualConnectionDialog() {
        if (manualConnecting) {
            Toast.makeText(this, R.string.manual_connect_progress, Toast.LENGTH_SHORT).show();
            return;
        }
        android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setHint("192.168.0.194");
        input.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        input.setText(getSharedPreferences("connection", MODE_PRIVATE).getString("last_ip", ""));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.action_manual_connect)
                .setMessage(R.string.manual_connect_message)
                .setView(input)
                .setPositiveButton(R.string.manual_connect_button, null)
                .setNegativeButton(R.string.action_cancel, null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final String ip;
            try { ip = DeviceAddress.normalize(input.getText().toString()); }
            catch (IllegalArgumentException e) { input.setError(getString(R.string.manual_connect_invalid)); return; }
            manualConnecting = true;
            dialog.dismiss();
            logToConsole(getString(R.string.manual_connect_progress));
            new Thread(() -> {
                HttpURLConnection connection = null;
                String failure = null;
                try {
                    connection = (HttpURLConnection) new URL("http://" + ip + ":5005/param/get?name=RecordingRunning").openConnection();
                    connection.setConnectTimeout(5000);
                    connection.setReadTimeout(5000);
                    connection.setRequestMethod("GET");
                    if (connection.getResponseCode() != 200) throw new java.io.IOException("HTTP " + connection.getResponseCode());
                    StringBuilder body = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                        char[] chars = new char[1024]; int count;
                        while ((count = reader.read(chars)) != -1) {
                            body.append(chars, 0, count);
                            if (body.length() > 65536) throw new java.io.IOException("Invalid device response");
                        }
                    }
                    JSONObject response = new JSONObject(body.toString());
                    if (!response.has("value") || response.has("error")) throw new java.io.IOException("Invalid device response");
                } catch (Exception e) { failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
                finally { if (connection != null) connection.disconnect(); }
                final String error = failure;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (error == null) {
                        synchronized (HomeActivity.this) { serverIp = ip; manualConnecting = false; }
                        stopAllUdpListeners();
                        getSharedPreferences("connection", MODE_PRIVATE).edit().putString("last_ip", ip).apply();
                        tvIpStatus.setText(getString(R.string.device_ip) + " " + ip);
                        logToConsole(getString(R.string.found_device_ip) + ip);
                        loadedBranches = null;
                        fetchBranchInfo();


                    } else {
                        manualConnecting = false;
                        String message = getString(R.string.manual_connect_failed, error);
                        logToConsole(message);
                        new AlertDialog.Builder(HomeActivity.this).setTitle(R.string.action_manual_connect)
                                .setMessage(message).setPositiveButton(R.string.action_ok, null).show();
                        if ("Not connected".equals(serverIp)) checkNetworkAndConnect();
                    }
                });
            }, "manual-device-connect").start();
        }));
        dialog.show();
    }

    private void showAppInfoDialog() {
        // 앱의 버전 정보를 가져옵니다. (build.gradle 파일에 정의된 versionName)
        String versionName = "";
        try {
            android.content.pm.PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            versionName = pInfo.versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }

        String startYear = "2025"; // 이 프로젝트를 처음 시작한 연도를 여기에 고정
        String currentYear = String.valueOf(java.util.Calendar.getInstance().get(java.util.Calendar.YEAR));
        String copyrightYear;
        if (startYear.equals(currentYear)) {
            copyrightYear = startYear; // 시작 연도와 현재 연도가 같으면 하나만 표시
        } else {
            copyrightYear = startYear + "-" + currentYear; // 다르면 "2023-2025" 형식으로 표시
        }

        // 다이얼로그에 표시될 메시지를 만듭니다.
        // strings.xml에서 문자열을 가져와 동적으로 메시지를 구성합니다.
        String appInfoMessage = getString(R.string.app_info_version) + ": " + versionName + "\n\n"
                + getString(R.string.app_info_developer) + ": multikyd\n"
                + getString(R.string.app_info_contact) + ": multikyd@gmail.com\n\n"
                + String.format(getString(R.string.app_info_copyright), copyrightYear);

        // AlertDialog를 생성하여 화면에 보여줍니다.
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.app_info_title)) // 제목 설정
                .setMessage(appInfoMessage) // 메시지 설정
                .setCancelable(true) // 바깥쪽을 눌러도 닫히도록 설정

                // "확인" 버튼
                .setPositiveButton(getString(R.string.action_ok), null)
                .show(); // 다이얼로그를 화면에 보여줍니다.
    }

    @Override
    protected void onStart() {
        super.onStart();
        appUpdater.onStart();
        fetchBranchInfo();
        AutoBookmarkService.resumeIfEnabled(this);
    }

    @Override
    protected void onDestroy() {
        ++branchMonitorGeneration;
        appUpdater.onDestroy();
        super.onDestroy();
    }

    private void showGitPullForceConfirmation() {
        if (serverIp == null || serverIp.equals("Not connected")) {
            Toast.makeText(this, getString(R.string.msg_connect_first), Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.git_pull_force_title))
                .setMessage(getString(R.string.git_pull_force_confirm_message))
                .setPositiveButton(getString(R.string.action_force_execute), (dialog, which) -> {
                    executeGitPullForce();
                })
                .setNegativeButton(getString(R.string.action_cancel), null)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .show();
    }

    private void executeGitPullForce() {
        if (serverIp == null || serverIp.equals("Not connected")) {
            Toast.makeText(this, getString(R.string.msg_connect_first), Toast.LENGTH_SHORT).show();
            return;
        }

        // 바로 명령을 보내는 대신, 로그 폴링 기능이 있는 통합 메서드를 호출합니다.
        // 서버와 약속된 명령어 ID "git_pull_reboot_force"를 전달합니다.
        sendCommandAndPollLog("git_pull_reboot_force");
    }

    private void showGitResetConfirmationDialog() {
        if (serverIp == null || serverIp.equals("Not connected")) {
            Toast.makeText(this, getString(R.string.msg_connect_first), Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.git_reset_confirm_title))
                .setMessage(getString(R.string.git_reset_confirm_message))
                .setPositiveButton(getString(R.string.action_force_execute), (dialog, which) -> {
                    executeGitResetandReboot();
                })
                .setNegativeButton(getString(R.string.action_cancel), null)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .show();
    }
    private void executeGitResetandReboot() {
        if (serverIp == null || serverIp.equals("Not connected")) {
            Toast.makeText(this, getString(R.string.msg_connect_first), Toast.LENGTH_SHORT).show();
            return;
        }

        // 바로 명령을 보내는 대신, 로그 폴링 기능이 있는 통합 메서드를 호출합니다.
        // 서버와 약속된 명령어 ID "git_reset_reboot_force"를 전달합니다.
        sendCommandAndPollLog("git_reset_reboot_force");
    }

    // ★ 콘솔에 메시지를 추가하는 헬퍼 메서드
    private void logToConsole(String message, boolean overwrite) {
        if (tvConsole == null || scrollViewConsole == null) return;

        runOnUiThread(() -> {
            if (overwrite) {
                tvConsole.setText(message);
            } else {
                tvConsole.append(message + "\n");
            }
            // 항상 스크롤을 맨 아래로 이동
            scrollViewConsole.post(() -> scrollViewConsole.fullScroll(View.FOCUS_DOWN));
        });
    }

    public void logToConsole(String message) {
        runOnUiThread(() -> {
            String currentTime = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date());
            String newLog = "> [" + currentTime + "] " + message + "\n";
            tvConsole.append(newLog);

            // 자동으로 맨 아래로 스크롤
            scrollViewConsole.post(() -> scrollViewConsole.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private void checkNetworkAndConnect() {
        // 이전에 실행 중이던 리스너가 있다면 모두 중단시킵니다.
        stopAllUdpListeners();

        // 활성화된 모든 Wi-Fi 관련 인터페이스의 브로드캐스트 주소를 찾습니다.
        List<String> broadcastAddresses = getBroadcastAddresses();

        if (!broadcastAddresses.isEmpty()) {
            // UDP 탐색을 시작하기 직전에, 여기서 단 한 번만 MulticastLock을 획득합니다.
            acquireMulticastLock();

            String message = String.format(getString(R.string.discovery_start_on_interfaces), broadcastAddresses.size());
            logToConsole(message);

            for (String broadcastIp : broadcastAddresses) {
                String localIp = getLocalIpForBroadcast(broadcastIp); // 로그 표시용
                message = String.format(getString(R.string.wifi_connected_start_discovery_detailed), localIp, broadcastIp);
                logToConsole(message);
                // 🔥 찾은 브로드캐스트 주소마다 별도의 리스너 스레드를 시작합니다.
                startUdpBroadcastListener(broadcastIp);
            }
        } else {
            // 활성화된 Wi-Fi 네트워크(핫스팟 포함)가 없는 경우
            logToConsole(getString(R.string.no_wifi_connection));
        }
    }
    @Nullable
    private NetworkInfo getActiveNetworkInfo() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        return cm.getActiveNetworkInfo();
    }
    @Nullable
    private String getLocalIpAddress() {
        try {
            // 모든 네트워크 인터페이스를 순회합니다.
            for (java.util.Enumeration<java.net.NetworkInterface> en = java.net.NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
                java.net.NetworkInterface intf = en.nextElement();
                // 루프백이 아니고, 활성화 상태인 인터페이스만 대상으로 합니다.
                if (intf.isLoopback() || !intf.isUp()) continue;

                // 🔥 "wlan"(Wi-Fi) 또는 "ap"(Access Point/Hotspot) 이름이 포함된 인터페이스를 모두 확인합니다.
                String interfaceName = intf.getDisplayName().toLowerCase();
                if (!interfaceName.contains("wlan") && !interfaceName.contains("ap")) continue;

                for (java.util.Enumeration<java.net.InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements(); ) {
                    java.net.InetAddress inetAddress = enumIpAddr.nextElement();
                    // 루프백이 아닌 IPv4 주소만 사용합니다.
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof java.net.Inet4Address) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (Exception ex) {
            Log.e("IPAddress", "Failed to get local IP address", ex);
        }
        return null;
    }

    private void startUdpBroadcastListener(String broadcastIp) {
        Thread listenerThread = new Thread(() -> {
            // DatagramSocket은 루프 밖에서 생성하고, 스레드가 끝날 때 닫습니다.
            try (DatagramSocket socket = new DatagramSocket(null)) {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(broadcastIp, 5004));
                socket.setSoTimeout(5000); // 5초 타임아웃

                // IP를 찾거나 스레드가 외부에서 중단되기 전까지 루프를 돕니다.
                while (!Thread.currentThread().isInterrupted() && "Not connected".equals(serverIp)) {
                    try {
                        byte[] buf = new byte[1024];
                        DatagramPacket packet = new DatagramPacket(buf, buf.length);

                        Log.d("UdpListener", "Waiting for UDP broadcast on " + broadcastIp + "...");
                        socket.receive(packet); // 5초간 대기

                        String senderIp = packet.getAddress().getHostAddress();
                        onServerFound(senderIp);

                        break; // 성공했으므로 루프를 즉시 탈출합니다.

                    } catch (SocketTimeoutException e) {
                        Log.d("UdpListener", "UDP listen timed out on " + broadcastIp + ". Retrying...");
                    }
                }
            } catch (Exception e) {
                // 소켓 생성/바인딩 실패 등 심각한 오류 처리
                e.printStackTrace();
                logToConsole(getString(R.string.udp_error) + e.getMessage());
            }
            Log.d("UdpListener", "UDP Listener thread for " + broadcastIp + " has finished.");
        });

        // 스레드 리스트에 추가하고 시작
        udpListenerThreads.add(listenerThread);
        listenerThread.start();
    }

    private void onServerFound(String ip) {
        // 여러 스레드가 동시에 이 메서드를 호출하는 것을 방지 (동기화)
        synchronized (this) {
            // 이미 다른 스레드가 IP를 찾았다면 아무것도 하지 않음
            if (manualConnecting || !"Not connected".equals(serverIp)) {
                return;
            }
            serverIp = ip;
        }

        // 🔥 IP를 찾았으므로, 더 이상 필요 없는 모든 리스너 스레드와 MulticastLock을 정리합니다.
        stopAllUdpListeners();

        runOnUiThread(() -> {
            tvIpStatus.setText(getString(R.string.device_ip) + " " + ip);
            logToConsole(getString(R.string.found_device_ip) + ip);
            loadedBranches = null;
            fetchBranchInfo();


        });
    }

    private void stopAllUdpListeners() {
        for (Thread thread : udpListenerThreads) {
            if (thread.isAlive()) {
                thread.interrupt(); // 스레드에 중단 신호 보내기
            }
        }
        udpListenerThreads.clear(); // 리스트 비우기

        // MulticastLock 해제
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
            Log.d("UdpListener", "MulticastLock released by stopAll.");
        }
    }

    private void acquireMulticastLock() {
        if (multicastLock != null && multicastLock.isHeld()) return;
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            multicastLock = wifi.createMulticastLock("KisaAgentUdpLock");
            multicastLock.setReferenceCounted(true);
            multicastLock.acquire();
            Log.d("UdpListener", "MulticastLock acquired.");
        }
    }

    private List<String> getBroadcastAddresses() {
        List<String> broadcastAddresses = new ArrayList<>();
        try {
            for (java.util.Enumeration<java.net.NetworkInterface> niEnum = java.net.NetworkInterface.getNetworkInterfaces(); niEnum.hasMoreElements(); ) {
                java.net.NetworkInterface ni = niEnum.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                String interfaceName = ni.getDisplayName().toLowerCase();
                if (!interfaceName.contains("wlan") && !interfaceName.contains("ap")) continue;

                for (java.net.InterfaceAddress interfaceAddress : ni.getInterfaceAddresses()) {
                    if (interfaceAddress.getBroadcast() != null) {
                        broadcastAddresses.add(interfaceAddress.getBroadcast().getHostAddress());
                    }
                }
            }
        } catch (Exception e) {
            Log.e("BroadcastAddress", "Failed to get broadcast addresses", e);
        }
        return broadcastAddresses;
    }

    private String getLocalIpForBroadcast(String broadcastIp) {
        return getLocalIpAddress();
    }

    public void checkRecordStatus() {
        if (serverIp == null || serverIp.equals("Not connected")) return;

        new Thread(() -> {
            try {
                // 파라미터 값 조회
                String targetUrl = "http://" + serverIp + ":5005/param/get?name=" + PARAM_KEY_RECORD;
                URL url = new URL(targetUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(2000);
        conn.setReadTimeout(5000);
        conn.setReadTimeout(5000);

                if (conn.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();

                    JSONObject json = new JSONObject(sb.toString());
                    boolean isOn = false;

                    // "value" 키가 존재하는지 먼저 확인
                    if (json.has("value")) {
                        Object valObj = json.get("value");

                        if (valObj instanceof Boolean) {
                            isOn = (Boolean) valObj;
                        } else if (valObj instanceof Number) {
                            // 정수 1 또는 실수 1.0 이면 true로 인식
                            isOn = ((Number) valObj).doubleValue() == 1.0;
                        } else if (valObj instanceof String) {
                            String sVal = (String) valObj;
                            isOn = sVal.equals("1") || sVal.equalsIgnoreCase("true");
                        }
                    }

                    // 람다 변수 캡처를 위해 final 변수 사용 (혹은 바로 사용)
                    boolean finalIsOn = isOn;
                    runOnUiThread(() -> updateRecordButtonUI(finalIsOn));
                }
                conn.disconnect();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    // 녹화 상태 토글 (POST /param/set)
    private void toggleRecordParam() {
        if (serverIp == null || serverIp.equals("Not connected")) {
            Toast.makeText(this, getString(R.string.msg_connect_first), Toast.LENGTH_SHORT).show();
            return;
        }

        // 현재 버튼 텍스트를 보고 반대로 설정 (ON -> 0, OFF -> 1)
        boolean currentlyOn = btnRecord.getText().toString().contains("ON");
        int newValue = currentlyOn ? 0 : 1;

        new Thread(() -> {
            try {
                URL url = new URL("http://" + serverIp + ":5005/param/set");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                JSONObject json = new JSONObject();
                json.put("param", PARAM_KEY_RECORD);
                json.put("value", newValue);

                OutputStream os = conn.getOutputStream();
                os.write(json.toString().getBytes());
                os.flush();
                os.close();

                int code = conn.getResponseCode();
                if (code == 200) {
                    runOnUiThread(() -> {
                        updateRecordButtonUI(newValue == 1);
                        String msg = (newValue == 1) ? getString(R.string.recording_started) : getString(R.string.recording_stopped);
                        logToConsole(msg);
                    });
                } else {
                    runOnUiThread(() -> Toast.makeText(HomeActivity.this, getString(R.string.update_failed) + code, Toast.LENGTH_SHORT).show());
                }
                conn.disconnect();
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(HomeActivity.this, getString(R.string.error_prefix) + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // 버튼 UI 업데이트 (ON=빨강, OFF=회색)
    private void updateRecordButtonUI(boolean isOn) {
        if (isOn) {
            btnRecord.setText(getString(R.string.screen_record_on));
            // Fix: Use android.graphics.Color (referenced simply as Color)
            btnRecord.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#C62828")));
        } else {
            btnRecord.setText(getString(R.string.screen_record_off));
            // Fix: Use android.graphics.Color
            btnRecord.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#777777")));
        }
    }

    // 1. 브랜치 정보 가져오기 (재시도 로직 추가)
    private void fetchBranchInfo() {
        final String host = serverIp;
        if (host == null || "Not connected".equals(host) || !branchFetchRunning.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                for (int retry = 0; retry < 10 && host.equals(serverIp); retry++) {
                    try {
                        String base = "http://" + host + ":5005/param/get?name=";
                        String target = new JSONObject(getResponseFromUrl(base + "UpdaterTargetBranch")).optString("value", "");
                        if (target.isEmpty() || "Unknown".equals(target) || "null".equals(target)) {
                            target = new JSONObject(getResponseFromUrl(base + "GitBranch")).optString("value", "");
                        }
                        final String current = target;
                        String raw = new JSONObject(getResponseFromUrl(base + "UpdaterAvailableBranches")).optString("value", "");
                        final String[] branches = java.util.Arrays.stream(raw.split(","))
                                .map(String::trim).filter(v -> !v.isEmpty() && !"null".equals(v)).distinct().toArray(String[]::new);
                        if (branches.length == 0) throw new java.io.IOException("Branch list unavailable");
                        runOnUiThread(() -> {
                            if (!host.equals(serverIp) || isFinishing() || isDestroyed()) return;
                            loadedBranches = branches;
                            btnBranch.setText(getString(R.string.branch_prefix) + " " + current);
                        });
                        return;
                    } catch (Exception error) {
                        if (retry == 9) {
                            runOnUiThread(() -> {
                                if (host.equals(serverIp) && !isFinishing() && !isDestroyed()) {
                                    loadedBranches = null;
                                    btnBranch.setText(R.string.branch_list_retry);
                                }
                            });
                        } else {
                            try { Thread.sleep(2000); }
                            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                        }
                    }
                }
            } finally { branchFetchRunning.set(false); }
        }, "branch-info").start();
    }

    // 2. 브랜치 선택 다이얼로그
    private void showBranchSelectionDialog() {
        if (serverIp == null || serverIp.equals("Not connected")) {
            Toast.makeText(this, getString(R.string.msg_connect_first), Toast.LENGTH_SHORT).show();
            return;
        }
        if (loadedBranches == null || loadedBranches.length == 0) {
            fetchBranchInfo();
            Toast.makeText(this, getString(R.string.btn_branch_list_loading), Toast.LENGTH_SHORT).show();

            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.select_branch_title));
        builder.setItems(loadedBranches, (dialog, which) -> {
            String selectedBranch = loadedBranches[which];
            showBranchChangeConfirmationDialog(selectedBranch);
        });
        builder.show();
    }

    // 브랜치 변경 전 확인 팝업
    private void showBranchChangeConfirmationDialog(String branchName) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.branch_confirm_title)); // "브랜치 변경 확인"
        builder.setMessage(getString(R.string.branch_confirm_msg)); // "변경을 하시겠습니까?..."

        // '변경' 버튼 클릭 시 실제 변경 로직(setTargetBranch) 수행
        builder.setPositiveButton(getString(R.string.action_change), (dialog, which) -> {
            setTargetBranch(branchName);
        });

        // '취소' 버튼
        builder.setNegativeButton(getString(R.string.action_cancel), null);

        builder.show();
    }

    // 3. 브랜치 변경 요청 (POST)
    private void setTargetBranch(String branchName) {
        new Thread(() -> {
            try {
                URL url = new URL("http://" + serverIp + ":5005/param/set");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(10000);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                JSONObject json = new JSONObject();
                json.put("param", "UpdaterTargetBranch");
                json.put("value", branchName);

                logToConsole(getString(R.string.requesting_branch_change, branchName));

                java.io.OutputStream os = conn.getOutputStream();
                os.write(json.toString().getBytes("UTF-8"));
                os.flush();
                os.close();

                int code = conn.getResponseCode();
                if (code == 200) {
                    runOnUiThread(() -> {
                        btnBranch.setText(getString(R.string.branch_prefix) + " " + branchName);
                        Toast.makeText(HomeActivity.this, "브랜치 변경 요청을 접수했습니다. 다운로드 상태를 확인합니다.", Toast.LENGTH_LONG).show();

                        logToConsole(getString(R.string.branch_change_request_success));

                        // ★ 변경 성공 시 다운로드 진행 상태 추적 시작
                        checkUpdaterProgress(branchName);
                    });
                } else {
                    // 로그 추가: 실패
                    runOnUiThread(() -> {
                        String err = getString(R.string.branch_update_failed, String.valueOf(code));
                        Toast.makeText(HomeActivity.this, err, Toast.LENGTH_SHORT).show();
                        logToConsole(err);
                    });
                }
                conn.disconnect();
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    String err = getString(R.string.branch_error, e.getMessage());
                    Toast.makeText(HomeActivity.this, err, Toast.LENGTH_SHORT).show();
                    logToConsole(err);
                });
            }
        }).start();
    }

    // URL에서 JSON 문자열을 받아오는 헬퍼 메서드
    private String getResponseFromUrl(String urlString) throws Exception {
        URL url = new URL(urlString);
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(2000);
        conn.setReadTimeout(5000);

        java.io.BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();
        conn.disconnect();
        return sb.toString();
    }

    // ★ 업데이터 상태 추적 및 설치 유도
    private Object updaterParam(String host, String name) throws Exception {
        JSONObject response = new JSONObject(getResponseFromUrl("http://" + host + ":5005/param/get?name=" + name));
        return response.isNull("value") ? "" : response.opt("value");
    }

    private void checkUpdaterProgress(String requestedBranch) {
        final String host = serverIp;
        final int generation = ++branchMonitorGeneration;
        new Thread(() -> {
            int idleTicks = 0;
            String last = "";
            while (generation == branchMonitorGeneration && !isDestroyed() && host.equals(serverIp)) {
                try {
                    String raw = String.valueOf(updaterParam(host, "UpdaterState"));
                    boolean onroad = !BranchUpdatePolicy.truth(updaterParam(host, "IsOffroad"));
                    boolean disabled = BranchUpdatePolicy.truth(updaterParam(host, "DisableUpdates"));
                    boolean available = BranchUpdatePolicy.truth(updaterParam(host, "UpdateAvailable"));
                    String prepared = available ? String.valueOf(updaterParam(host, "UpdaterNewDescription")) : "";
                    BranchUpdatePolicy.State state = BranchUpdatePolicy.state(raw, onroad, disabled, available, prepared, requestedBranch);
                    String message;
                    switch (state) {
                        case DISABLED: logToConsole("업데이트가 비활성화되어 있습니다. C4의 DisableUpdates 설정을 확인하세요."); return;
                        case READY:
                            runOnUiThread(() -> { if (generation == branchMonitorGeneration && !isDestroyed()) { logToConsole("선택한 브랜치 다운로드 완료 · 재부팅 후 적용할 수 있습니다."); showInstallConfirmationDialog(); } });
                            return;
                        case STALE_READY: message = "이전 브랜치의 준비 정보를 확인했습니다. 선택한 브랜치의 다운로드를 기다립니다."; break;
                        case DOWNLOADING: message = "브랜치 다운로드 중: " + requestedBranch; break;
                        case FINALIZING: message = "다운로드 완료 · 적용 파일 준비 중"; break;
                        case CHECKING: message = "선택한 브랜치 업데이트 확인 중"; break;
                        case STARTING: message = "C4 업데이터 시작 중"; break;
                        case WAITING_OS_OFFROAD: message = "브랜치 코드 다운로드 완료 · OS 버전 변경 준비는 오프로드에서 진행됩니다."; break;
                        case ONROAD_IDLE: message = "온로드 · 브랜치 요청 접수 후 다운로드 시작 대기 중. C4의 온로드 업데이터 지원이 필요합니다."; break;
                        default: message = "다운로드 시작 대기 중 · C4 업데이터 상태 확인 중"; break;
                    }
                    if (!message.equals(last)) { logToConsole(message); last = message; }
                    if (state == BranchUpdatePolicy.State.IDLE || state == BranchUpdatePolicy.State.ONROAD_IDLE || state == BranchUpdatePolicy.State.STALE_READY) {
                        if (++idleTicks >= 100) {
                            String failure = String.valueOf(updaterParam(host, "LastUpdateException"));
                            logToConsole("다운로드가 시작되지 않았습니다. C4 업데이터 실행 상태를 확인하세요." + (failure.isEmpty() ? "" : "\n마지막 오류: " + failure));
                            return;
                        }
                    } else { idleTicks = 0; }
                    Thread.sleep(3000);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                catch (Exception e) { logToConsole(getString(R.string.updater_monitor_error, e.getMessage())); return; }
            }
        }, "branch-update-monitor").start();
    }
    // ★ 설치 확인 팝업
    private void showInstallConfirmationDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.update_ready_title));
        builder.setMessage(getString(R.string.update_ready_message));

        // INSTALL 버튼을 누르면 재부팅 명령 전송
        builder.setPositiveButton(getString(R.string.action_install), (dialog, which) -> {
            triggerRebootForInstall();
        });

        builder.setNegativeButton(getString(R.string.action_later), null);
        builder.setCancelable(false); // 실수로 닫기 방지
        builder.show();
    }
    // ★ 설치(재부팅) 명령 전송
    private void triggerRebootForInstall() {
        new Thread(() -> {
            try {
                RebootSupport.require(serverIp, getString(R.string.reboot_server_update_required));
                URL preInstallUrl = new URL("http://" + serverIp + ":5005/cmd/run");
                HttpURLConnection preInstallConn = (HttpURLConnection) preInstallUrl.openConnection();
                preInstallConn.setRequestMethod("POST");
                preInstallConn.setRequestProperty("Content-Type", "application/json");
                preInstallConn.setDoOutput(true);

                JSONObject preInstallJson = new JSONObject();
                // 서버에 정의된 새로운 명령어 ID를 사용합니다.
                preInstallJson.put("id", "pre_install_cleanup");

                // 명령어 전송
                try (java.io.OutputStream os = preInstallConn.getOutputStream()) {
                    os.write(preInstallJson.toString().getBytes("UTF-8"));
                }

                preInstallConn.disconnect();

                // 잠시 대기 (명령어 처리를 위한 시간)
                Thread.sleep(1000);

                logToConsole(getString(R.string.msg_reboot_command_sent)); // 로그 추가

                // DoReboot 파라미터를 1로 설정하면 기기가 재부팅되며 설치됨
                URL url = new URL("http://" + serverIp + ":5005/param/set");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                JSONObject json = new JSONObject();
                json.put("param", "DoReboot");
                json.put("value", "1");

                java.io.OutputStream os = conn.getOutputStream();
                os.write(json.toString().getBytes("UTF-8"));
                os.flush();
                os.close();

                if (conn.getResponseCode() == 200) {
                    runOnUiThread(() -> {
                        Toast.makeText(HomeActivity.this, getString(R.string.msg_rebooting_install), Toast.LENGTH_LONG).show();
                        logToConsole(getString(R.string.msg_reboot_command_sent));
                    });
                } else {
                    runOnUiThread(() -> {
                        Toast.makeText(HomeActivity.this, getString(R.string.msg_reboot_failed), Toast.LENGTH_SHORT).show();
                        logToConsole(getString(R.string.msg_reboot_failed));
                    });
                }
                conn.disconnect();
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    String err = getString(R.string.msg_reboot_error, e.getMessage());
                    Toast.makeText(HomeActivity.this, err, Toast.LENGTH_SHORT).show();
                    logToConsole(err);
                });
            }
        }).start();
    }

    // ★ 앱 재시작 확인 다이얼로그
    private void showRestartConfirmationDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.restart_app_title));
        builder.setMessage(getString(R.string.restart_app_msg));

        builder.setPositiveButton(getString(R.string.action_yes), (dialog, which) -> {
            restartApp();
        });

        builder.setNegativeButton(getString(R.string.action_no), null);

        builder.show();
    }

    // --- 1. Git Pull & Reboot 기능 ---

    private void showGitPullConfirmation() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        if (serverIp == null || serverIp.equals("Not connected")) {
            Toast.makeText(this, getString(R.string.msg_connect_first), Toast.LENGTH_SHORT).show();
            return;
        }
        builder.setTitle("Git Pull");
        builder.setMessage(getString(R.string.git_pull_confirm));

        // '예'를 누르면 executeGitPull() 대신 새로운 통합 메서드를 호출합니다.
        builder.setPositiveButton(getString(R.string.action_yes), (dialog, which) -> {
            // 서버와 약속된 명령어 ID "git_pull_reboot"을 전달합니다.
            sendCommandAndPollLog("git_pull_reboot");
        });

        builder.setNegativeButton(getString(R.string.action_no), null);
        builder.show();
    }

    // --- 2. Git Restore 기능 ---
    private void fetchGitCommitsAndShowDialog() {
        if (serverIp == null || serverIp.equals("Not connected")) {
            Toast.makeText(this, getString(R.string.msg_connect_first), Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, getString(R.string.git_restore_msg), Toast.LENGTH_SHORT).show();

        // 백그라운드 스레드에서 네트워크 작업 수행
        executor.execute(() -> {
            boolean success = false;
            try {
                // 1) 서버에 git log 파일 생성을 요청합니다. (로그 추적이 필요 없는 단순 실행)
                URL url = new URL("http://" + serverIp + ":5005/cmd/run");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; utf-8");
                conn.setDoOutput(true);

                JSONObject jsonParam = new JSONObject();
                // 서버의 schema.json에 정의된, 로그 파일 생성용 ID를 사용합니다.
                jsonParam.put("id", "git_log_export");

                try (DataOutputStream os = new DataOutputStream(conn.getOutputStream())) {
                    os.write(jsonParam.toString().getBytes(StandardCharsets.UTF_8));
                }

                // 요청이 성공적으로 보내졌는지 확인 (여기서는 UUID를 받아오지만 사용하지는 않음)
                if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    // 응답 본문을 읽고 연결을 닫습니다.
                    getResponseFromUrlConnection(conn);
                    success = true;
                } else {
                    throw new Exception(getString(R.string.git_restore_server_error + conn.getResponseCode()));
                }

                if (success) {
                    // 2) 잠시 대기 (서버에서 파일 생성 및 저장 시간 확보)
                    Thread.sleep(2000);

                    // 3) GitCommits 파라미터 읽어오기
                    String urlStr = "http://" + serverIp + ":5005/param/get?name=GitCommits";
                    JSONObject json = new JSONObject(getResponseFromUrl(urlStr));
                    String commitsRaw = json.optString("value", "");

                    runOnUiThread(() -> {
                        if (!commitsRaw.isEmpty()) {
                            loadedCommits = commitsRaw.split("\n");
                            showCommitSelectionDialog();
                        } else {
                            Toast.makeText(HomeActivity.this, getString(R.string.git_restore_failed_to_load), Toast.LENGTH_SHORT).show();
                        }
                    });
                }

            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(HomeActivity.this, getString(R.string.git_restore_error, e.getMessage()), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void showCommitSelectionDialog() {
        if (loadedCommits == null || loadedCommits.length == 0) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.git_restore_title));

        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(
                this,
                R.layout.item_commit,
                loadedCommits
        );

        builder.setAdapter(adapter, (dialog, which) -> {
            String selectedLine = loadedCommits[which];
            try {
                String commitHash = selectedLine.split(",")[0].trim();
                confirmRestore(commitHash, selectedLine);
            } catch (Exception e) {
                Toast.makeText(this, getString(R.string.error_parsing_commit_hash), Toast.LENGTH_SHORT).show();
            }
        });

        builder.setNegativeButton(getString(R.string.action_cancel), null);

        builder.show();
    }

    private void confirmRestore(String commitHash, String fullMsg) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.restore_confirm_title));
        builder.setMessage(getString(R.string.restore_confirm_msg, fullMsg));

        builder.setPositiveButton(getString(R.string.action_restore), (dialog, which) -> {
            // 바로 명령을 보내는 대신, 새로운 통합 메서드를 호출합니다.
            // 서버와 약속된 명령어 ID "git_restore_reboot"와 추가 파라미터(commit hash)를 전달합니다.
            sendCommandAndPollLog("git_restore_reboot", commitHash);
        });

        builder.setNegativeButton(getString(R.string.action_cancel), null);
        builder.show();
    }

    private void sendCommandAndPollLog(String cmdId, String... params) {
        if (isPolling) {
            Toast.makeText(this, getString(R.string.log_polling_another_task_running), Toast.LENGTH_SHORT).show();
            return;
        }

        executor.execute(() -> {
            try {
                // UI 스레드에서 UI 업데이트
                runOnUiThread(() -> {
                    logToConsole(getString(R.string.log_polling_requesting_command, cmdId));
                    btnGitPull.setEnabled(false);
                    btnGitRestore.setEnabled(false);
                });

                if (cmdId.contains("reboot")) RebootSupport.require(serverIp, getString(R.string.reboot_server_update_required));
                URL url = new URL("http://" + serverIp + ":5005/cmd/run");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; utf-8");
                conn.setDoOutput(true);

                JSONObject jsonParam = new JSONObject();
                jsonParam.put("id", cmdId);
                // 추가 파라미터(예: 커밋 해시)가 있으면 JSON에 추가
                if (params.length > 0) {
                    jsonParam.put("param", params[0]);
                }

                try (DataOutputStream os = new DataOutputStream(conn.getOutputStream())) {
                    os.write(jsonParam.toString().getBytes(StandardCharsets.UTF_8));
                }

                if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    JSONObject resJson = new JSONObject(getResponseFromUrlConnection(conn));
                    String cmdUuid = resJson.optString("id");
                    if (cmdUuid != null && !cmdUuid.isEmpty()) {
                        // 성공적으로 UUID를 받으면 로그 폴링 시작
                        pollLogs(cmdUuid);
                    } else {
                        throw new Exception(getString(R.string.log_polling_invalid_id));
                    }
                } else {
                    throw new Exception(getString(R.string.log_polling_server_error, conn.getResponseCode()));
                }
            } catch (Exception e) {
                // ★★★ 이 부분의 catch 블록을 수정합니다 ★★★
                String errorMessage = e.getMessage();
                // 재부팅으로 인해 정상적으로 발생할 수 있는 연결 실패 메시지인지 확인
                if (errorMessage != null && (errorMessage.toLowerCase().contains("failed to connect") || errorMessage.toLowerCase().contains("connection refused") || errorMessage.toLowerCase().contains("connection reset"))) {
                    // 이 경우는 재부팅 명령이 성공적으로 전달되어 서버가 꺼진 것이므로, 오류가 아님.
                    // 따라서 아무것도 하지 않고 스레드를 조용히 종료합니다.
                    Log.d("CommandSend", "Connection lost after sending command, likely due to reboot. Suppressing error message.");
                } else {
                    // 그 외의 실제 얘기치 못한 오류일 경우에만 기존처럼 오류 로그를 출력
                    e.printStackTrace();
                    runOnUiThread(() -> {
                        logToConsole(getString(R.string.log_polling_error, errorMessage));
                        btnGitPull.setEnabled(true);
                        btnGitRestore.setEnabled(true);
                    });
                }
            }
        });
    }

    // ★★★ [신설] 로그를 주기적으로 가져오는 폴링 메서드 ★★★
    private void pollLogs(String uuid) {
        isPolling = true;

        executor.execute(() -> {
            try {
                while (isPolling) {
                    Thread.sleep(500); // 1초 간격으로 폴링

                    URL url = new URL("http://" + serverIp + ":5005/cmd/output?id=" + uuid);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");

                    // ★★★ [핵심 수정] 응답 코드를 변수에 미리 저장 ★★★
                    int responseCode = conn.getResponseCode();

                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        JSONObject logObj = new JSONObject(getResponseFromUrlConnection(conn));

                        String outputLog = logObj.optString("output", "");
                        boolean isDone = logObj.optBoolean("done", false);

                        runOnUiThread(() -> logToConsole(outputLog, true)); // 전체 로그를 덮어쓰기

                        if (isDone) {
                            isPolling = false; // 루프 종료
                        }
                    } else {
                        isPolling = false; // 서버에서 오류 응답 시 폴링 중단
                        // ★★★ [핵심 수정] 저장해둔 변수를 사용 ★★★
                        runOnUiThread(() -> logToConsole(getString(R.string.log_polling_receive_error, responseCode)));
                    }
                }
            } catch (Exception e) {
                // ★★★ 핵심 수정 부분 ★★★
                String errorMessage = e.getMessage();
                // 재부팅으로 인해 정상적으로 발생할 수 있는 연결 실패 메시지인지 확인
                if (errorMessage != null && (errorMessage.toLowerCase().contains("failed to connect") || errorMessage.toLowerCase().contains("connection refused") || errorMessage.toLowerCase().contains("connection reset"))) {
                    // 이 경우는 재부팅이 시작되어 서버가 꺼진 것이므로, 오류가 아님.
                    // 따라서 아무것도 하지 않고 스레드를 조용히 종료합니다.
                    Log.d("Polling", "Connection lost, likely due to reboot. Suppressing error message.");
                } else {
                    // 그 외의 실제 얘기치 못한 오류일 경우에만 기존처럼 오류 로그를 출력
                    e.printStackTrace();
                    runOnUiThread(() -> logToConsole(getString(R.string.log_polling_exception, errorMessage)));
                }
                // ★★★ 여기까지 수정 ★★★
            } finally {
                runOnUiThread(() -> {
                    logToConsole(getString(R.string.log_polling_task_complete), false);
                    btnGitPull.setEnabled(true);
                    btnGitRestore.setEnabled(true);
                });
                isPolling = false;
            }
        });
    }

    // ★★★ [신설] getResponseFromUrl을 HttpURLConnection을 받도록 수정 (코드 중복 방지) ★★★
    private String getResponseFromUrlConnection(HttpURLConnection conn) throws java.io.IOException {
        // 이 메서드는 스트림만 읽고, disconnect는 호출한 쪽에서 처리하도록 함
        try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            StringBuilder response = new StringBuilder();
            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            return response.toString();
        } finally {
            conn.disconnect();
        }
    }

    // --- [기능 1] 파라미터 파일로 저장 (내보내기) ---
    private void fetchAndSaveParamsToFile() {
        if (serverIp == null || serverIp.equals("Not connected")) {
            Toast.makeText(this, getString(R.string.msg_connect_first), Toast.LENGTH_SHORT).show();
            return;
        }
        // 연결 확인 로직이 showSaveConfirmationDialog로 이동했으므로 여기서는 바로 시작
        Toast.makeText(this, getString(R.string.msg_fetching_params), Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            try {
                // 1. 서버에서 전체 데이터 가져오기
                String urlStr = "http://" + serverIp + ":5005/param_schema.json";
                String rawJsonStr = getResponseFromUrl(urlStr);

                if (rawJsonStr != null && !rawJsonStr.isEmpty()) {
                    // ★ [추가된 로직] 불필요한 스키마 정보 제거하고 param, value만 추출
                    org.json.JSONArray originalArray = new org.json.JSONArray(rawJsonStr);
                    org.json.JSONArray simpleArray = new org.json.JSONArray();

                    for (int i = 0; i < originalArray.length(); i++) {
                        org.json.JSONObject item = originalArray.getJSONObject(i);

                        String key = item.optString("param");
                        // 값이 없으면 default값이라도 가져오도록 처리
                        Object val = item.opt("value");
                        if (val == null) val = item.opt("default");

                        // 키와 값이 유효한 경우에만 저장
                        if (!key.isEmpty() && val != null) {
                            org.json.JSONObject simpleItem = new org.json.JSONObject();
                            simpleItem.put("param", key);
                            simpleItem.put("value", val);

                            simpleArray.put(simpleItem);
                        }
                    }

                    // 2. 간소화된 JSON을 파일로 저장 (들여쓰기 2칸으로 보기 좋게)
                    saveJsonToDownloads(simpleArray.toString(2));

                } else {
                    runOnUiThread(() -> Toast.makeText(this, getString(R.string.msg_fetch_data_failed), Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> logToConsole(getString(R.string.msg_export_error, e.getMessage())));
            }
        }).start();
    }

    // 안드로이드 공용 다운로드 폴더에 저장 (앱 삭제되어도 유지됨)
    private void saveJsonToDownloads(String jsonContent) {
        try {
            // 파일명에 시간 포함하여 중복 방지
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss");
            String currentDate = sdf.format(new java.util.Date());
            String fileName = "kisa_params_backup_" + currentDate + ".json";

            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/json");
            // 다운로드 폴더 지정
            values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS);

            // 파일 생성
            android.net.Uri uri = getContentResolver().insert(android.provider.MediaStore.Files.getContentUri("external"), values);

            if (uri != null) {
                java.io.OutputStream outputStream = getContentResolver().openOutputStream(uri);
                if (outputStream != null) {
                    outputStream.write(jsonContent.getBytes());
                    outputStream.close();

                    runOnUiThread(() -> {
                        // "저장 완료!\nDownload/파일명"
                        Toast.makeText(this, getString(R.string.msg_save_complete, "Download/" + fileName), Toast.LENGTH_LONG).show();
                        // "File saved to: ..."
                        logToConsole(getString(R.string.msg_file_saved_log, "Download/" + fileName));
                    });
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            // "파일 저장 실패: " + 메시지
            runOnUiThread(() -> Toast.makeText(this, getString(R.string.msg_save_failed, e.getMessage()), Toast.LENGTH_SHORT).show());
        }
    }

    // --- [기능 2] 파라미터 파일에서 복원 (불러오기) ---
    private void openFilePicker() {
        if (serverIp == null || serverIp.equals("Not connected")) {
            Toast.makeText(this, getString(R.string.msg_connect_first), Toast.LENGTH_SHORT).show();
            return;
        }
        // 파일 선택기 실행 (권한 필요 없음, 사용자가 직접 선택하므로)
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json"); // JSON 파일만 필터링

        try {
            startActivityForResult(intent, PICK_JSON_FILE_REQUEST);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.msg_cannot_open_file_manager), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // 결과가 OK이고 데이터가 있을 때만 실행
        if (resultCode == RESULT_OK && data != null) {
            android.net.Uri uri = data.getData();

            if (uri != null) {
                // [케이스 1] 파일에서 복원 (Load Params)
                if (requestCode == PICK_JSON_FILE_REQUEST) {
                    logToConsole(getString(R.string.log_restore_file_selected, uri.getPath()));
                    readAndRestoreParams(uri);
                }
                // [케이스 2] 파일 내용 확인 (View File) - 이 부분이 있는지 꼭 확인!
                else if (requestCode == PICK_JSON_FILE_VIEW_REQUEST) {
                    logToConsole(getString(R.string.log_view_file_selected, uri.getPath()));
                    readAndShowFileContent(uri);
                }
            }
        } else {
            // 취소했거나 에러가 난 경우
            logToConsole(getString(R.string.log_file_selection_failed));
        }
    }

    private void readAndRestoreParams(android.net.Uri uri) {
        try {
            // 1. 파일 읽기
            java.io.InputStream inputStream = getContentResolver().openInputStream(uri);
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(inputStream));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            inputStream.close();
            String jsonStr = sb.toString();

            // 2. 서버에 전송하여 복구
            restoreParamsToServer(jsonStr);

        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, getString(R.string.msg_read_failed), Toast.LENGTH_SHORT).show();
        }
    }

    private void restoreParamsToServer(String jsonStr) {
        if (serverIp == null || serverIp.equals("Not connected")) {
            Toast.makeText(this, getString(R.string.msg_connect_first), Toast.LENGTH_SHORT).show();
            return;
        }

        new Thread(() -> {
            try {
                // 1. 파일에서 읽은 JSON 파싱
                org.json.JSONArray jsonArray = new org.json.JSONArray(jsonStr);
                int count = 0;

                for (int i = 0; i < jsonArray.length(); i++) {
                    org.json.JSONObject item = jsonArray.getJSONObject(i);

                    String key = item.optString("param");

                    // ★ [핵심 수정] 값 가져오기 로직 개선
                    // 'value' 키가 존재하면 가져오고, 없으면 'default' 값을 가져옴
                    // opt()는 Object 타입을 반환하므로 숫자, 불리언도 안전하게 가져올 수 있음
                    Object rawVal = item.opt("value");
                    if (rawVal == null) {
                        rawVal = item.opt("default");
                    }

                    // 값이 없으면 건너뜀
                    if (key.isEmpty() || rawVal == null) continue;

                    // ★ 모든 타입을 문자열로 변환 (String.valueOf 사용)
                    // 예: 100 -> "100", true -> "true"
                    String valStr = String.valueOf(rawVal);

                    // 서버로 전송
                    restoreParameter(key, valStr);

                    count++;
                    // 서버 과부하 방지를 위해 아주 짧은 대기 (선택 사항)
                    Thread.sleep(20);
                }

                int finalCount = count;
                runOnUiThread(() -> {
                    // "XXX개 파라미터 복구 완료"
                    Toast.makeText(this, getString(R.string.msg_restore_complete, finalCount), Toast.LENGTH_LONG).show();
                    // "Restored XXX params to server."
                    logToConsole(getString(R.string.msg_restored_log, finalCount));
                });
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    String message = getString(R.string.msg_restore_error, e.getMessage());
                    logToConsole(message);
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void restoreParameter(String key, String value) throws IOException, org.json.JSONException {
        URL url = new URL("http://" + serverIp + ":5005/param/set");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            JSONObject json = new JSONObject();
            json.put("param", key);
            json.put("value", value);
            json.put("restoreBackup", true);

            try (OutputStream output = conn.getOutputStream()) {
                output.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException(key + " (HTTP " + status + ")");
            }
        } finally {
            conn.disconnect();
        }
    }

    // ★ [추가] 파라미터 파일 저장 확인 다이얼로그
    private void showSaveConfirmationDialog() {
        // 먼저 기기와 연결되었는지 확인
        if (serverIp == null || serverIp.equals("Not connected")) {
            Toast.makeText(this, getString(R.string.msg_connect_first), Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        // strings.xml에서 제목과 메시지를 가져옴
        builder.setTitle(getString(R.string.save_confirm_title));
        builder.setMessage(getString(R.string.save_confirm_msg));

        // '저장' 버튼을 누르면 실제 저장 로직(fetchAndSaveParamsToFile) 실행
        builder.setPositiveButton(getString(R.string.action_save), (dialog, which) -> {
            fetchAndSaveParamsToFile();
        });

        // '취소' 버튼
        builder.setNegativeButton(getString(R.string.action_cancel), null);

        builder.show();
    }

    // --- [기능 3] 저장된 파일 내용 확인 (View) ---

    private void openFileViewer() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");

        try {
            startActivityForResult(intent, PICK_JSON_FILE_VIEW_REQUEST); // 102번 요청
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.msg_cannot_open_file_manager), Toast.LENGTH_SHORT).show();
        }
    }

    // 파일 내용을 읽어서 다이얼로그로 표시
    private void readAndShowFileContent(android.net.Uri uri) {
        try {
            String fileName = "Unknown File";
            android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (nameIndex != -1) {
                    fileName = cursor.getString(nameIndex);
                }
                cursor.close();
            }

            java.io.InputStream inputStream = getContentResolver().openInputStream(uri);
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(inputStream));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            inputStream.close();

            String jsonStr = sb.toString();

            // 보기 좋게 포맷팅 (Pretty Print)
            String formattedJson;
            try {
                if (jsonStr.trim().startsWith("[")) {
                    org.json.JSONArray jsonArray = new org.json.JSONArray(jsonStr);
                    formattedJson = jsonArray.toString(4); // 들여쓰기 4칸
                } else {
                    org.json.JSONObject jsonObject = new org.json.JSONObject(jsonStr);
                    formattedJson = jsonObject.toString(4);
                }
            } catch (Exception e) {
                formattedJson = jsonStr; // 파싱 실패 시 원본 그대로 출력
            }

            showContentDialog(fileName, formattedJson);

        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, getString(R.string.msg_read_failed), Toast.LENGTH_SHORT).show();
        }
    }

    // 스크롤 가능한 텍스트 다이얼로그 표시
    private void showContentDialog(String fileName, String content) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        builder.setTitle(getString(R.string.view_file_title));

        // 1. 다이얼로그의 전체 레이아웃 (수직 방향)
        LinearLayout dialogLayout = new LinearLayout(this);
        dialogLayout.setOrientation(LinearLayout.VERTICAL);
        dialogLayout.setPadding(30, 20, 30, 20); // 전체 여백

        // 2. 파일명 텍스트뷰 (상단 고정)
        TextView fileNameView = new TextView(this);
        String displayName = fileName.replace(".json", "");
        fileNameView.setText(displayName);
        fileNameView.setTextSize(16);
        // 테마의 Primary 색상(강조색)을 가져와서 설정 (주/야간 모드 자동 대응)
        fileNameView.setTextColor(getThemeColor(com.google.android.material.R.attr.colorSecondary));
        fileNameView.setTypeface(null, android.graphics.Typeface.BOLD);
        fileNameView.setPadding(0, 0, 0, 15); // 아래쪽 여백

        // 3. 구분선 (상단 고정)
        View separator = new View(this);
        separator.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                2 // 구분선 두께
        ));
        // 테마의 보조 색상을 가져와서 설정
        separator.setBackgroundColor(getThemeColor(com.google.android.material.R.attr.colorOnSurface));


        // ★★★ 핵심 수정 ★★★
        // 4. 파일 내용을 담을 스크롤뷰 (이 부분만 스크롤됨)
        ScrollView contentScrollView = new ScrollView(this);
        // 부모 레이아웃의 남은 공간을 모두 차지하도록 설정
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT // 남은 공간을 모두 차지
        );
        contentScrollView.setLayoutParams(scrollParams);


        // 5. 파일 내용 텍스트뷰
        TextView contentView = new TextView(this);
        contentView.setText(content);
        contentView.setTextSize(15);
        contentView.setTypeface(android.graphics.Typeface.MONOSPACE); // 고정폭 글꼴
        // 테마의 기본 텍스트 색상을 가져와 설정
        contentView.setTextColor(getThemeColor(com.google.android.material.R.attr.colorSecondary));
        contentView.setPadding(0, 15, 0, 0); // 위쪽 여백

        // 6. 스크롤뷰 안에 내용 텍스트뷰 추가
        contentScrollView.addView(contentView);

        // 7. 전체 레이아웃에 고정될 뷰와 스크롤될 뷰를 순서대로 추가
        dialogLayout.addView(fileNameView);
        dialogLayout.addView(separator);
        dialogLayout.addView(contentScrollView); // 스크롤뷰를 마지막에 추가

        builder.setView(dialogLayout);
        builder.setPositiveButton(getString(R.string.action_ok), null);
        builder.show();
    }

    // ★ [필수] 테마 속성으로부터 실제 색상 값을 가져오는 메서드
    // (만약 클래스 내에 없다면 이 메서드를 꼭 추가해주세요)
    private int getThemeColor(int attr) {
        android.util.TypedValue typedValue = new android.util.TypedValue();
        getTheme().resolveAttribute(attr, typedValue, true);
        return typedValue.data;
    }

    // --- 모델 선택기 기능 ---

    // 1. 서버에서 모델 목록을 가져와 다이얼로그 표시
    private void fetchAndShowModels() {
        if (serverIp == null || serverIp.equals("Not connected")) {
            Toast.makeText(this, getString(R.string.msg_connect_first), Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, getString(R.string.loading_model_list), Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            try {
                String urlStr = "http://" + serverIp + ":5005/models";
                String jsonResponse = getResponseFromUrl(urlStr);

                org.json.JSONObject responseJson = new org.json.JSONObject(jsonResponse);
                String currentModel = responseJson.getString("current_model");
                org.json.JSONArray modelsArray = responseJson.getJSONArray("models");

                modelInfoList.clear();
                final List<String> displayList = new ArrayList<>();

                for (int i = 0; i < modelsArray.length(); i++) {
                    org.json.JSONObject model = modelsArray.getJSONObject(i);
                    String name = model.getString("name");

                    if (name.startsWith("Original_with_latest:")) {
                        String latestModelName = name.split(":")[1];
                        displayList.add(getString(R.string.restore_original_with_latest, latestModelName));
                    } else if ("Original".equals(name)) {
                        displayList.add(getString(R.string.restore_original_model_text));
                    } else {
                        displayList.add(name);
                    }

                    modelInfoList.add(model.toString());
                }

                runOnUiThread(() -> {
                    showModelDialog(currentModel, displayList);
                });

            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(HomeActivity.this, getString(R.string.failed_load_model_list), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }


    // 안정적인 스크롤을 위한 다이얼로그 생성 메서드
    private void showModelDialog(String currentModelName, List<String> modelNames) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        // 1. "현재 모델" 정보를 표시할 커스텀 제목 뷰 생성
        LinearLayout customTitleLayout = new LinearLayout(this);
        customTitleLayout.setOrientation(LinearLayout.VERTICAL);
        customTitleLayout.setPadding(40, 30, 40, 20);

        TextView currentModelTitle = new TextView(this);
        currentModelTitle.setText(getString(R.string.current_model_title));
        currentModelTitle.setTextSize(20);

        TextView currentModelValue = new TextView(this);
        currentModelValue.setText(currentModelName);
        currentModelValue.setTextSize(20);
        currentModelValue.setTextColor(Color.parseColor("#007AFF")); // 파란색 계열
        currentModelValue.setTypeface(null, Typeface.BOLD);
        currentModelValue.setPadding(40, 5, 0, 15); // 아래쪽 여백

        customTitleLayout.addView(currentModelTitle);
        customTitleLayout.addView(currentModelValue);

        // 2. AlertDialog의 제목 영역에 커스텀 뷰 설정
        builder.setCustomTitle(customTitleLayout);

        // 3. 모델 목록을 표시할 어댑터 생성
        // (이전에 커밋용으로 만든 촘촘한 레이아웃을 재사용하여 간격 조절)
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(
                this,
                R.layout.item_commit,
                modelNames
        );

        // 4. 어댑터와 클릭 리스너 설정 (이 방법이 스크롤을 자동으로 지원함)
        builder.setAdapter(adapter, (dialog, which) -> {
            try {
                // 사용자가 선택한 모델의 전체 정보 가져오기
                org.json.JSONObject selectedModel = new org.json.JSONObject(modelInfoList.get(which));
                String modelName = selectedModel.getString("name");
                String modelPrefix = selectedModel.getString("prefix");

                if (modelPrefix.equals("Original")) {
                    confirmRestoreOriginalModel(); // 기본 모델 복원
                } else {
                    confirmChangeModel(modelName, modelPrefix); // 커스텀 모델 변경
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        builder.setNegativeButton(R.string.action_cancel, null);
        builder.show();
    }

    // 2. 커스텀 모델로 변경할지 확인하는 팝업
    private void confirmChangeModel(String modelName, String modelPrefix) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.model_change_confirm_title));
        builder.setMessage(getString(R.string.model_change_confirm_msg, modelName));
        builder.setPositiveButton(getString(R.string.action_change), (dialog, which) -> {
            startModelChangeAndMonitor(modelName, modelPrefix);
        });
        builder.setNegativeButton(R.string.action_cancel, null);
        builder.show();
    }

    // 모델 변경 시작 및 로그 모니터링 메서드
    private void startModelChangeAndMonitor(String modelName, String modelPrefix) {
        new Thread(() -> {
            try {
                // 1. 서버에 모델 변경 시작 요청 (POST)
                RebootSupport.require(serverIp, getString(R.string.reboot_server_update_required));
                URL url = new URL("http://" + serverIp + ":5005/model/change");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                JSONObject json = new JSONObject();
                json.put("prefix", modelPrefix);

                OutputStream os = conn.getOutputStream();
                os.write(json.toString().getBytes("UTF-8"));
                os.flush();
                os.close();

                if (conn.getResponseCode() != 200) {
                    runOnUiThread(() -> logToConsole(getString(R.string.log_model_change_start_fail)));
                    return;
                }
                conn.disconnect();

                // 2. 로그 모니터링 시작
                runOnUiThread(() -> logToConsole(getString(R.string.log_model_change_started, modelName)));
                String lastLog = "";
                boolean wasLastLogProgress = false; // ★ 마지막 로그가 진행률이었는지 기억
                while (true) {
                    Thread.sleep(500); // 2초마다 로그 확인

                    String logContent = getResponseFromUrl("http://" + serverIp + ":5005/log/download");

                    // 재부팅 로그를 감지하거나 서버 응답이 없으면 모니터링 정상 종료
                    if (logContent == null || logContent.contains("Rebooting...")) {
                        if (logContent != null && !lastLog.contains("Rebooting...")) {
                            runOnUiThread(() -> logToConsole(getString(R.string.log_rebooting)));
                        }
                        break; // 루프 탈출
                    }

                    final String currentLineRaw = logContent.substring(lastLog.length()).replace("\r", "").trim();

                    if (!currentLineRaw.isEmpty()) {
                        // wget 진행률 로그인지 확인
                        boolean isProgressLog = currentLineRaw.contains("%");

                        String lineToShow;
                        if (isProgressLog) {
                            // ★★★ 핵심 수정 부분 ★★★
                            // 진행률 로그라면, 모든 점(.) 문자를 제거합니다.
                            lineToShow = currentLineRaw.replaceAll("\\.", "").replaceAll(" +", " ");
                        } else {
                            lineToShow = currentLineRaw;
                        }
                        // ★★★ 여기까지 수정 ★★★

                        final String finalLine = lineToShow;

                        if (isProgressLog) {
                            // 현재 로그가 진행률 로그일 때
                            if (wasLastLogProgress) {
                                // ★ 이전 로그도 진행률이었다면, 마지막 줄을 교체
                                runOnUiThread(() -> {
                                    String currentConsoleText = tvConsole.getText().toString();
                                    int lastNewline = currentConsoleText.lastIndexOf('\n');
                                    if (lastNewline != -1) {
                                        // 마지막 줄바꿈 이전의 텍스트 + 새로운 진행률 로그
                                        String newText = currentConsoleText.substring(0, lastNewline + 1) + finalLine;
                                        tvConsole.setText(newText);
                                    } else {
                                        // 콘솔에 한 줄만 있다면 그냥 전체 교체
                                        tvConsole.setText(finalLine);
                                    }
                                });
                            } else {
                                // ★ 이전 로그가 일반 로그였다면, 그냥 추가
                                runOnUiThread(() -> logToConsole(finalLine));
                            }
                            wasLastLogProgress = true; // 마지막 로그가 진행률이었음을 기억
                        } else {
                            // 일반 로그는 그냥 추가
                            runOnUiThread(() -> logToConsole(finalLine));
                            wasLastLogProgress = false; // 마지막 로그가 일반 로그였음을 기억
                        }
                    }
                    lastLog = logContent;
                }
            } catch (Exception e) {
                // 이 catch 블록은 이제 정상적인 재부팅 시에는 실행되지 않아야 합니다.
                e.printStackTrace();
                runOnUiThread(() -> logToConsole(getString(R.string.log_monitoring_error, e.getMessage())));
            }
        }).start();
    }

    // 3. 기본 모델로 복원할지 확인하는 팝업 (기존과 동일, 확인용)
    private void confirmRestoreOriginalModel() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.model_restore_confirm_title);
        builder.setMessage(R.string.model_restore_confirm_msg);
        builder.setPositiveButton(R.string.action_restore, (dialog, which) -> {
            monitorRestoreLog();
        });
        builder.setNegativeButton(R.string.action_cancel, null);
        builder.show();
    }

    // ★ [추가] 기본 모델 복원 로그를 모니터링하는 메서드
    private void monitorRestoreLog() {
        new Thread(() -> {
            try {
                // 1. 서버에 기본 모델 복원 API 호출
                RebootSupport.require(serverIp, getString(R.string.reboot_server_update_required));
                URL url = new URL("http://" + serverIp + ":5005/model/restore_original");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(false); // 보내는 데이터 없음

                if (conn.getResponseCode() != 200) {
                    runOnUiThread(() -> logToConsole(getString(R.string.log_restore_request_failed)));
                    return; // 요청 실패 시 종료
                }
                conn.disconnect();

                // 2. 로그 모니터링 시작
                runOnUiThread(() -> Toast.makeText(this, getString(R.string.model_restoring), Toast.LENGTH_LONG).show());
                String lastLog = "";

                // 최대 100번 (약 3분) 동안 로그를 확인
                for (int i = 0; i < 20; i++) {
                    Thread.sleep(500); // 3초마다 로그 확인

                    // ★ 서버의 복원 로그 파일을 가져옴 (예: /log/restore)
                    String logContent = getResponseFromUrl("http://" + serverIp + ":5005/log/restore");

                    if (logContent == null) continue;

                    // ★★★ 핵심 수정 부분 (매우 간단해짐) ★★★
                    // 새로 추가된 로그 내용만 추출하여 출력
                    if (logContent.length() > lastLog.length()) {
                        String newLogPart = logContent.substring(lastLog.length());
                        // 여러 줄이 한 번에 들어올 수 있으므로, 줄바꿈 기준으로 나눠서 하나씩 출력
                        String[] lines = newLogPart.trim().split("\n");
                        for (String line : lines) {
                            if (!line.trim().isEmpty()) {
                                final String finalLine = line.trim();
                                runOnUiThread(() -> logToConsole(finalLine));
                            }
                        }
                    }
                    // ★★★ 여기까지 수정 ★★★

                    lastLog = logContent; // 다음 비교를 위해 현재 로그를 저장

                    // 재부팅 로그를 감지하면 모니터링 정상 종료
                    if (logContent.contains("Rebooting...")) {
                        runOnUiThread(() -> logToConsole(getString(R.string.log_rebooting)));
                        break; // 루프 탈출
                    }
                }

            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> logToConsole(getString(R.string.log_restore_error, e.getMessage())));
            }
        }).start();
    }

    private void executeFingerprint() {
        if (serverIp == null || serverIp.equals("Not connected")) {
            Toast.makeText(this, getString(R.string.msg_connect_first), Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, getString(R.string.fingerprint_fetching), Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            try {
                String urlStr = "http://" + serverIp + ":5005/execute_fingerprint";
                String jsonResponse = getResponseFromUrl(urlStr);

                runOnUiThread(() -> {
                    if (jsonResponse != null) {
                        try {
                            JSONObject responseJson = new JSONObject(jsonResponse);
                            if (responseJson.has("fingerprint")) {
                                String fingerprintResult = responseJson.getString("fingerprint");
                                logToConsole(getString(R.string.log_fingerprint_success));
                                runOnUiThread(() -> showFingerprintDialog(fingerprintResult));
                            } else if (responseJson.has("error")) {
                                String errorMessage = responseJson.getString("error");
                                logToConsole(getString(R.string.log_fingerprint_error, errorMessage));
                                runOnUiThread(() -> showErrorDialog(getString(R.string.fingerprint_error_title), errorMessage));
                            } else {
                                logToConsole(getString(R.string.log_fingerprint_fail_no_response));
                                runOnUiThread(() -> Toast.makeText(HomeActivity.this, getString(R.string.server_no_response), Toast.LENGTH_SHORT).show());
                            }

                        } catch (Exception e) {
                            showErrorDialog(getString(R.string.response_parsing_error_title), getString(R.string.response_parsing_error_message));
                        }
                    } else {
                        logToConsole(getString(R.string.log_fingerprint_fail_no_response));
                        runOnUiThread(() -> Toast.makeText(HomeActivity.this, getString(R.string.server_no_response), Toast.LENGTH_SHORT).show());
                    }
                });
            } catch (Exception e) {
                logToConsole(getString(R.string.log_fingerprint_exception, e.getMessage()));
                runOnUiThread(() -> Toast.makeText(HomeActivity.this, getString(R.string.error_prefix) + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void showFingerprintDialog(String content) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.fingerprint_result_title));

        // 스크롤 가능한 TextView를 다이얼로그에 추가
        ScrollView scrollView = new ScrollView(this);
        TextView textView = new TextView(this);
        textView.setText(content);
        textView.setPadding(50, 40, 50, 40);
        textView.setTextIsSelectable(true); // 텍스트 복사 가능하게
        scrollView.addView(textView);
        builder.setView(scrollView);

        // 버튼 설정
        builder.setNeutralButton(getString(R.string.fingerprint_save_file), (dialog, which) -> {
            saveFingerprintToFile(content);
        });

        builder.setNegativeButton(getString(R.string.fingerprint_copy_clipboard), (dialog, which) -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Fingerprint", content);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, getString(R.string.clipboard_copied), Toast.LENGTH_SHORT).show();
        });
        builder.setPositiveButton(getString(R.string.dialog_btn_close), null);
        builder.show();
    }

    private void saveFingerprintToFile(String content) {
        // 파일을 저장할 외부 저장소의 Downloads 폴더 경로
        File path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File file = new File(path, "fingerprint_result.txt");

        try (FileWriter writer = new FileWriter(file)) {
            writer.append(content);
            writer.flush();
            String successMsg = getString(R.string.file_save_success, file.getAbsolutePath());
            Toast.makeText(this, successMsg, Toast.LENGTH_LONG).show();
            logToConsole(successMsg);
        } catch (IOException e) {
            e.printStackTrace();
            String failMsg = getString(R.string.file_save_failed, e.getMessage());
            Toast.makeText(this, failMsg, Toast.LENGTH_LONG).show();
            logToConsole(getString(R.string.log_fingerprint_save_fail));
        }
    }

    private void showErrorDialog(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(getString(R.string.action_ok), null)
                .show();
    }

    // ★ 앱 완전 재시작 메서드
    private void restartApp() {
        android.content.pm.PackageManager pm = getPackageManager();
        Intent intent = pm.getLaunchIntentForPackage(getPackageName());
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP); // 기존 스택 제거
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); // 새 태스크 시작
            startActivity(intent);
            finish(); // 현재 액티비티 종료
            Runtime.getRuntime().exit(0); // 프로세스 강제 종료 (확실한 초기화)
        }
    }

}
