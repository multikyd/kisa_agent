package com.kisapilot.kisaagent;

import android.os.Bundle;
import android.widget.Toast;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.app.AlertDialog;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;


public class CmdActivity extends AppCompatActivity {

    private RecyclerView rvCmds;
    private TextView tvCmdLog;
    private ScrollView scrollViewLog;
    private boolean isPolling = false;
    private String currentPid = null;
    private CmdAdapter adapter;
    private List<CmdItem> cmdList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cmd);

        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        rvCmds = findViewById(R.id.rv_cmds);
        tvCmdLog = findViewById(R.id.tv_cmd_log);
        scrollViewLog = findViewById(R.id.scroll_view_log);

        rvCmds.setLayoutManager(new LinearLayoutManager(this));

        cmdList = new ArrayList<>();
        adapter = new CmdAdapter(cmdList);
        rvCmds.setAdapter(adapter);

        // 액티비티 시작 시 서버에서 명령어 목록 가져오기
        fetchCommandList();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isPolling = false; // 액티비티 종료 시 폴링 중단
    }

    // ★ 1. 서버에서 명령어 목록(JSON) 가져오기
    private void fetchCommandList() {
        new Thread(() -> {
            try {
                String targetUrl = "http://" + HomeActivity.serverIp + ":5005/cmd/list";

                URL url = new URL(targetUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(3000);

                // 응답 코드를 백그라운드 스레드에서 미리 가져와서 변수에 저장
                int responseCode = conn.getResponseCode();

                if (responseCode == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();

                    // UI 업데이트
                    runOnUiThread(() -> parseAndDisplayCommands(response.toString()));
                } else {
                    runOnUiThread(() -> Toast.makeText(CmdActivity.this, getString(R.string.fetch_list_failed) + responseCode, Toast.LENGTH_SHORT).show());
                }
                conn.disconnect();

            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(CmdActivity.this, getString(R.string.connection_failed) + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // ★ 2. 가져온 JSON 파싱하여 리스트에 넣기
    private void parseAndDisplayCommands(String jsonStr) {
        try {
            cmdList.clear();
            JSONArray arr = new JSONArray(jsonStr);

            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);

                // JSON 필드 매핑 ("title", "cmd", "description", "confirm")
                String title = getLocalizedString(obj, "title", "Unknown");
                String cmdId = obj.optString("cmd", "");
                String desc = getLocalizedString(obj, "description", "");
                boolean confirm = obj.optBoolean("confirm", false);

                cmdList.add(new CmdItem(title, cmdId, desc, confirm));
            }
            adapter.notifyDataSetChanged(); // 리스트 갱신

        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, getString(R.string.json_error), Toast.LENGTH_SHORT).show();
        }
    }

    private String getLocalizedString(JSONObject parent, String key, String defaultValue) {
        Object obj = parent.opt(key);

        // 1. 만약 값이 {"ko": "...", "en": "..."} 형태의 JSON 객체라면
        if (obj instanceof JSONObject) {
            JSONObject map = (JSONObject) obj;
            // 현재 폰의 언어 설정을 가져옵니다 ("ko", "en" 등)
            String systemLang = java.util.Locale.getDefault().getLanguage();

            if ("ko".equals(systemLang)) {
                return map.optString("ko", map.optString("en", defaultValue));
            } else {
                return map.optString("en", map.optString("ko", defaultValue));
            }
        }
        // 2. 만약 값이 그냥 문자열이라면 그대로 반환 (기존 호환성 유지)
        else if (obj instanceof String) {
            return (String) obj;
        }

        return defaultValue;
    }

    private void startLogPolling(String uuid) {
        // 기존 폴링 중단 및 초기화
        isPolling = false;
        currentPid = uuid; // 변수명은 currentPid여도 실제 값은 UUID가 들어감

        runOnUiThread(() -> {
            tvCmdLog.setText(getString(R.string.fetching_logs, uuid) + "\n");
        });




        // 잠시 후 새 폴링 시작
        new Thread(() -> {
            try {
                Thread.sleep(500); // 이전 스레드 정리 대기
                isPolling = true;

                while (isPolling) {
                    try {
                        // 서버의 /cmd/output?id={uuid} 호출
                        String targetUrl = "http://" + HomeActivity.serverIp + ":5005/cmd/output?id=" + uuid;
                        URL url = new URL(targetUrl);
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("GET");
                        conn.setConnectTimeout(2000);

                        int code = conn.getResponseCode();
                        if (code == 200) {
                            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                            StringBuilder sb = new StringBuilder();
                            String line;
                            while ((line = reader.readLine()) != null) sb.append(line).append("\n");
                            reader.close();

                            String jsonResponse = sb.toString();
                            JSONObject logObj = new JSONObject(jsonResponse);

                            // [수정] 서버가 누적된 로그를 보내주므로 setText로 덮어씌워야 중복이 안 생김
                            String outputLog = logObj.optString("output", "");
                            boolean isDone = logObj.optBoolean("done", false);

                            runOnUiThread(() -> {
                                tvCmdLog.setText(outputLog);
                                scrollViewLog.fullScroll(ScrollView.FOCUS_DOWN);
                            });

                            if (isDone) {
                                isPolling = false;
                                runOnUiThread(() -> Toast.makeText(CmdActivity.this, getString(R.string.command_complete), Toast.LENGTH_SHORT).show());

                            }
                        }
                        conn.disconnect();

                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                    if (isPolling) {
                        Thread.sleep(500); // 1초 간격 갱신
                    }
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();
    }


    // 데이터 클래스 (서버 JSON 구조 반영)
    // 데이터 클래스 (서버 JSON 구조 반영)
    private static class CmdItem {
        String title;
        String cmdId;
        String description;
        boolean confirm;

        CmdItem(String title, String cmdId, String description, boolean confirm) {
            this.title = title;
            this.cmdId = cmdId;
            this.description = description;
            this.confirm = confirm;
        }
    }


    // RecyclerView Adapter
    private class CmdAdapter extends RecyclerView.Adapter<CmdViewHolder> {
        private final List<CmdItem> cmds;

        CmdAdapter(List<CmdItem> cmds) {
            this.cmds = cmds;
        }

        @Override
        public CmdViewHolder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            // item_cmd.xml 레이아웃 사용 (기존 것 재활용하되, 설명 텍스트뷰가 있으면 더 좋음)
            android.view.View view = android.view.LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_cmd, parent, false);
            return new CmdViewHolder(view);
        }

        @Override
        public void onBindViewHolder(CmdViewHolder holder, int position) {
            holder.bind(cmds.get(position));
        }

        @Override
        public int getItemCount() {
            return cmds.size();
        }
    }

    // ViewHolder
    private class CmdViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvName;
        private final Button btnExec;
        private final android.widget.ImageButton btnHelp;
        // private final TextView tvDesc; // item_cmd.xml에 설명 칸이 있다면 추가

        CmdViewHolder(android.view.View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tv_cmd_name);
            btnExec = itemView.findViewById(R.id.btn_exec);
            btnHelp = itemView.findViewById(R.id.btn_help);
            // tvDesc = itemView.findViewById(R.id.tv_cmd_desc);
        }

        void bind(CmdItem item) {
            tvName.setText(item.title);
            // if (tvDesc != null) tvDesc.setText(item.description);

            btnHelp.setOnClickListener(v -> {
                String desc = (item.description == null || item.description.isEmpty())
                        ? getString(R.string.no_description)
                        : item.description;

                new AlertDialog.Builder(CmdActivity.this)
                        .setTitle(item.title)
                        .setMessage(desc)
                        .setPositiveButton(getString(R.string.action_ok), null)
                        .show();
            });

            btnExec.setOnClickListener(v -> {
                if (item.confirm) {
                    // confirm이 true면 확인 팝업 띄우기
                    new AlertDialog.Builder(CmdActivity.this)
                            .setTitle(getString(R.string.exec_confirm_title))
                            .setMessage(item.description + getString(R.string.exec_confirm_msg))
                            .setPositiveButton(getString(R.string.action_ok), (dialog, which) -> sendCommand(item))
                            .setNegativeButton(getString(R.string.action_cancel), null)
                            .show();
                } else {
                    sendCommand(item);
                }
            });
        }

        // ★ 3. 명령어 실행 요청 (cmd ID만 전송)
        private void sendCommand(CmdItem item) {
            Toast.makeText(CmdActivity.this, getString(R.string.requesting), Toast.LENGTH_SHORT).show();

            // 로그창 초기화
            //CmdActivity.this.runOnUiThread(() -> tvCmdLog.setText(getString(R.string.starting_command, item.title) + "\n"));

            new Thread(() -> {
                try {
                    // ★ 포트 5005 확인
                    if (item.cmdId.contains("reboot")) RebootSupport.require(HomeActivity.serverIp, getString(R.string.reboot_server_update_required));
                    URL url = new URL("http://" + HomeActivity.serverIp + ":5005/cmd/run");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                    conn.setDoOutput(true);

                    JSONObject jsonBody = new JSONObject();
                    jsonBody.put("id", item.cmdId);

                    OutputStream os = conn.getOutputStream();
                    os.write(jsonBody.toString().getBytes("UTF-8"));
                    os.flush();
                    os.close();

                    int code = conn.getResponseCode();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(
                            (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream()));
                    StringBuilder resp = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) resp.append(line);
                    reader.close();

                    JSONObject resJson = new JSONObject(resp.toString());

                    CmdActivity.this.runOnUiThread(() -> {
                        if (code == 200) {
                            String cmdUuid = resJson.optString("id");
                            //Toast.makeText(CmdActivity.this, getString(R.string.started_id, cmdUuid), Toast.LENGTH_SHORT).show();

                            if (cmdUuid != null && !cmdUuid.isEmpty() && !cmdUuid.equals("null")) {
                                startLogPolling(cmdUuid);
                            } else {
                                tvCmdLog.append("Warning: ID not received from server.\n");
                            }
                        } else {
                            String err = resJson.optString("error", "Error");
                            tvCmdLog.append("Error: " + err + "\n");
                            Toast.makeText(CmdActivity.this, getString(R.string.exec_failed) + err, Toast.LENGTH_LONG).show();
                        }
                    });
                    conn.disconnect();

                } catch (Exception e) {
                    e.printStackTrace();
                    runOnUiThread(() -> tvCmdLog.append(getString(R.string.connection_error) + e.getMessage() + "\n"));
                }
            }).start();
        }
    }
}
