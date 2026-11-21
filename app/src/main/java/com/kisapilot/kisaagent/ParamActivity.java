package com.kisapilot.kisaagent;

import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.app.AlertDialog;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ParamActivity extends AppCompatActivity {

    private RecyclerView rvParams;
    private ParamAdapter adapter;
    private LinearLayout layoutFilterGroups; // 그룹 버튼 영역

    private List<ParamItem> allParams;
    private List<ParamItem> displayedParams;

    private String currentGroup = "ALL";     // 현재 선택된 그룹

    private Button btnSelectCar;
    private String[] loadedCarList = null; // 차량 목록 저장용

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_param);

        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        // 상단 그룹 버튼 레이아웃 연결
        layoutFilterGroups = findViewById(R.id.layout_filter_groups);
        rvParams = findViewById(R.id.rv_params);
        rvParams.setLayoutManager(new LinearLayoutManager(this));

//        // ★ 구분선 추가 코드 (이 줄을 추가하세요)
//        rvParams.addItemDecoration(new androidx.recyclerview.widget.DividerItemDecoration(this, androidx.recyclerview.widget.DividerItemDecoration.VERTICAL));

        allParams = new ArrayList<>();
        displayedParams = new ArrayList<>();
        adapter = new ParamAdapter(displayedParams);
        rvParams.setAdapter(adapter);

        btnSelectCar = findViewById(R.id.btn_select_car);
        btnSelectCar.setOnClickListener(v -> showCarSelectionDialog());

        findViewById(R.id.btn_bookmark_server).setOnClickListener(v -> startActivity(new android.content.Intent(this, BookmarkServerActivity.class)));

        fetchParamSchema();
        fetchCarInfo();
    }

    // 1. 서버에서 스키마 가져오기
    private void fetchParamSchema() {
        new Thread(() -> {
            try {
                URL url = new URL("http://" + HomeActivity.serverIp + ":5005/param/schema");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(3000);

                int code = conn.getResponseCode();
                if (code == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();

                    runOnUiThread(() -> parseSchema(sb.toString()));
                } else {
                    runOnUiThread(() -> Toast.makeText(ParamActivity.this, getString(R.string.schema_load_failed) + code, Toast.LENGTH_SHORT).show());
                }
                conn.disconnect();

            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(ParamActivity.this, getString(R.string.connection_error) + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // ★ 1. 서버에서 CarList와 CarName 가져오기
    private void fetchCarInfo() {
        new Thread(() -> {
            try {
                // CarList 가져오기
                String listUrl = "http://" + HomeActivity.serverIp + ":5005/param/get?name=CarList";
                String listJson = getResponseFromUrl(listUrl);
                JSONObject listObj = new JSONObject(listJson);
                String carListRaw = listObj.optString("value", "");

                // CarName 가져오기
                String selectedUrl = "http://" + HomeActivity.serverIp + ":5005/param/get?name=CarName";
                String selectedJson = getResponseFromUrl(selectedUrl);
                JSONObject selectedObj = new JSONObject(selectedJson);
                String currentCar = selectedObj.optString("value", "");

                runOnUiThread(() -> {
                    // 1. 차량 목록 파싱 (줄바꿈 문자로 분리)
                    if (!carListRaw.isEmpty()) {
                        loadedCarList = carListRaw.split("\n");
                    }

                    // 2. 버튼 텍스트 업데이트
                    if (currentCar != null && !currentCar.isEmpty() && !currentCar.equals("null")) {
                        btnSelectCar.setText(currentCar);
                    } else {
                        btnSelectCar.setText(getString(R.string.select_car_placeholder));
                    }
                });

            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    // ★ 2. 차량 선택 팝업 띄우기
    private void showCarSelectionDialog() {
        if (loadedCarList == null || loadedCarList.length == 0) {
            Toast.makeText(this, getString(R.string.loading_car_list), Toast.LENGTH_SHORT).show();
            // 목록이 없어도 다시 한번 불러오기 시도
            fetchCarInfo();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.car_select_title));

        builder.setItems(loadedCarList, (dialog, which) -> {
            String selectedCar = loadedCarList[which];
            updateSelectedCar(selectedCar); // 서버에 저장
        });

        builder.setNegativeButton(getString(R.string.action_cancel), null);

        String currentButtonText = btnSelectCar.getText().toString();
        String placeholderText = getString(R.string.select_car_placeholder);

        // 현재 버튼 텍스트가 기본 플레이스홀더가 아닐 경우에만 '선택 해제' 버튼을 추가합니다.
        if (!currentButtonText.equals(placeholderText)) {
            builder.setNeutralButton(getString(R.string.action_deselect), (dialog, which) -> {
                removeSelectedCar();
            });
        }

        builder.show();
    }

    // ★ 3. 선택한 차량 서버에 저장하기 (POST)
    private void updateSelectedCar(String carName) {
        new Thread(() -> {
            try {
                URL url = new URL("http://" + HomeActivity.serverIp + ":5005/param/set");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                JSONObject json = new JSONObject();
                json.put("param", "CarName");
                json.put("value", carName); // 문자열 그대로 전송

                java.io.OutputStream os = conn.getOutputStream();
                os.write(json.toString().getBytes("UTF-8"));
                os.flush();
                os.close();

                if (conn.getResponseCode() == 200) {
                    runOnUiThread(() -> {
                        btnSelectCar.setText(carName); // 버튼 텍스트 변경
                        String message = getString(R.string.car_changed_to, carName);
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                    });
                }
                conn.disconnect();
            } catch (Exception e) {
                runOnUiThread(() -> {
                    String errorMessage = getString(R.string.error_occurred) + e.getMessage();
                    Toast.makeText(this, errorMessage, Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void removeSelectedCar() {
        new Thread(() -> {
            try {
                // 서버에 추가될 "param/remove" 엔드포인트 호출
                URL url = new URL("http://" + HomeActivity.serverIp + ":5005/param/remove");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                // 삭제할 파라미터 이름("CarName")을 JSON으로 전송
                JSONObject json = new JSONObject();
                json.put("param", "CarName");

                java.io.OutputStream os = conn.getOutputStream();
                os.write(json.toString().getBytes("UTF-8"));
                os.flush();
                os.close();

                if (conn.getResponseCode() == 200) {
                    runOnUiThread(() -> {
                        // 성공 시, 버튼 텍스트를 기본값으로 변경
                        btnSelectCar.setText(getString(R.string.select_car_placeholder));
                        Toast.makeText(this, getString(R.string.car_info_removed_successfully), Toast.LENGTH_SHORT).show();
                    });
                } else {
                    final int responseCode = conn.getResponseCode();
                    runOnUiThread(() -> {
                        // 실패 메시지를 strings.xml에서 가져오도록 수정
                        String errorMessage = getString(R.string.remove_failed) + responseCode;
                        Toast.makeText(this, errorMessage, Toast.LENGTH_SHORT).show();
                    });
                }
                conn.disconnect();
            } catch (Exception e) {
                runOnUiThread(() -> {
                    // 오류 메시지를 strings.xml에서 가져오도록 수정
                    String errorMessage = getString(R.string.deselect_error) + e.getMessage();
                    Toast.makeText(this, errorMessage, Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    // (유틸) URL에서 응답 문자열 가져오기
    private String getResponseFromUrl(String urlString) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(2000);

        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line).append("\n");
        reader.close();
        conn.disconnect();

        return sb.toString();
    }

    // 다국어 처리 헬퍼 메서드
    private String getLocalizedString(JSONObject parent, String key, String defaultValue) {
        Object obj = parent.opt(key);

        // 1. 만약 값이 {"ko": "...", "en": "..."} 형태의 JSON 객체라면
        if (obj instanceof JSONObject) {
            JSONObject map = (JSONObject) obj;
            // 현재 폰의 언어 설정을 가져옵니다 ("ko", "en" 등)
            String systemLang = java.util.Locale.getDefault().getLanguage();

            if ("ko".equals(systemLang)) {
                // 한국어면 "ko" 값을 우선 찾고, 없으면 "en" 값을 씁니다.
                return map.optString("ko", map.optString("en", defaultValue));
            } else {
                // 그 외 언어(영어 등)면 "en" 값을 우선 찾고, 없으면 "ko" 값을 씁니다.
                return map.optString("en", map.optString("ko", defaultValue));
            }
        }
        // 2. 만약 값이 그냥 문자열("Screen Record")이라면 그대로 반환
        else if (obj instanceof String) {
            return (String) obj;
        }

        // 3. 둘 다 아니면 기본값 반환
        return defaultValue;
    }

    // 2. JSON 파싱 및 그룹 생성
    private void parseSchema(String jsonStr) {
        try {
            allParams.clear();
            JSONArray arr = new JSONArray(jsonStr);

            Set<String> groupNames = new HashSet<>();
            groupNames.add("ALL");

            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);

                // 오타 처리 (grop -> group)
                String group = obj.has("group") ? obj.getString("group") : obj.optString("grop", "General");

                groupNames.add(group);

                // special_texts 파싱
                java.util.Map<String, String> specialMap = new java.util.HashMap<>();
                JSONObject specialObj = obj.optJSONObject("special_texts");
                if (specialObj != null) {
                    java.util.Iterator<String> keys = specialObj.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        // ★ [수정] special_text 값도 다국어 처리를 위해 헬퍼 메서드 사용
                        String localizedValue = getLocalizedString(specialObj, k, "");
                        specialMap.put(k, localizedValue);
                    }
                }

                // ★ [수정] 다국어 헬퍼 메서드를 사용하여 title과 description 가져오기
                String title = getLocalizedString(obj, "title", "Unknown");
                String description = getLocalizedString(obj, "description", getString(R.string.no_description));

                allParams.add(new ParamItem(
                        group,
                        title,         // 번역된 타이틀
                        obj.getString("param"),
                        description,   // 번역된 설명
                        obj.optDouble("value", 0),
                        obj.optString("value_type", "STRING"),
                        obj.optDouble("step", 1.0),
                        obj.optDouble("min_value", 0),
                        obj.optDouble("max_value", 100),
                        specialMap // ★ 생성자에 맵 전달
                ));
            }

            // 그룹 버튼 생성
            createGroupButtons(groupNames);

            // 초기 목록 표시
            filterList("ALL");

            // 스키마 로드가 끝나면 실제 값 가져오기 시작
            fetchCurrentValues();

        } catch (JSONException e) {
            e.printStackTrace();
            Toast.makeText(this, getString(R.string.parsing_error), Toast.LENGTH_SHORT).show();
        }
    }

    // ★ 그룹 버튼 동적 생성
    private void createGroupButtons(Set<String> groups) {
        layoutFilterGroups.removeAllViews();List<String> sortedGroups = new ArrayList<>(groups);

        // 1. 리스트에서 "ALL"이 있다면 일단 제거 (중복 방지 및 정렬 제외)
        sortedGroups.remove("ALL");

        // 2. 나머지 그룹 이름들을 알파벳순으로 정렬
        Collections.sort(sortedGroups);

        // 3. "ALL"을 맨 앞에 명시적으로 추가
        sortedGroups.add(0, "ALL");

        for (String groupName : sortedGroups) {
            TextView btn = new TextView(this);
            btn.setText(groupName);
            btn.setPadding(40, 20, 40, 20);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, 0, 16, 0);
            btn.setLayoutParams(params);
            btn.setGravity(Gravity.CENTER);
            btn.setTag(groupName);

            btn.setOnClickListener(v -> {
                currentGroup = groupName;
                updateGroupButtonStyles();
                filterList(groupName);
            });

            layoutFilterGroups.addView(btn);
        }
        updateGroupButtonStyles();
    }

    // 버튼 스타일 갱신
    private void updateGroupButtonStyles() {
        for (int i = 0; i < layoutFilterGroups.getChildCount(); i++) {
            TextView btn = (TextView) layoutFilterGroups.getChildAt(i);
            String tag = (String) btn.getTag();
            if (tag.equals(currentGroup)) {
                btn.setBackgroundResource(R.drawable.bg_filter_button_selected);
                btn.setTextColor(Color.WHITE);
            } else {
                btn.setBackgroundResource(R.drawable.bg_filter_button_unselected);
                btn.setTextColor(Color.BLACK);
            }
        }
    }

    // ★ 리스트 필터링
    private void filterList(String groupName) {
        displayedParams.clear();
        if (groupName.equals("ALL")) {
            displayedParams.addAll(allParams);
        } else {
            for (ParamItem item : allParams) {
                if (item.group.equals(groupName)) {
                    displayedParams.add(item);
                }
            }
        }
        adapter.notifyDataSetChanged();
    }


    // 데이터 클래스
    private static class ParamItem {
        String group, title, key, description, type; // description 추가됨
        double value;
        double step, min, max;
        java.util.Map<String, String> specialTexts;

        ParamItem(String group, String title, String key, String description, double value, String type, double step, double min, double max, java.util.Map<String, String> specialTexts) {
            this.group = group;
            this.title = title;
            this.key = key;
            this.description = description;
            this.value = value;
            this.type = type;
            this.step = step;
            this.min = min;
            this.max = max;
            this.specialTexts = specialTexts; // ★ 저장
        }
    }

    // Adapter
    private class ParamAdapter extends RecyclerView.Adapter<ParamViewHolder> {
        private final List<ParamItem> params; // Use java.util.List
        ParamAdapter(List<ParamItem> params) { this.params = params; }

        @Override
        public ParamViewHolder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            View view = android.view.LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_param, parent, false);
            return new ParamViewHolder(view);
        }

        @Override
        public void onBindViewHolder(ParamViewHolder holder, int position) {
            holder.bind(params.get(position));
        }

        @Override
        public int getItemCount() { return params.size(); }
    }

    // ViewHolder
    private class ParamViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvTitle, tvValue;
        private final LinearLayout layoutNumeric;
        private final Button btnPlus, btnMinus, btnToggle, btnEdit;
        private final ImageButton btnHelp; // ★ 물음표 버튼 추가

        ParamViewHolder(View itemView) {
            super(itemView);
            tvTitle = itemView.findViewById(R.id.tv_param_title);
            tvValue = itemView.findViewById(R.id.tv_param_value);
            layoutNumeric = itemView.findViewById(R.id.layout_numeric_control);
            btnPlus = itemView.findViewById(R.id.btn_plus);
            btnMinus = itemView.findViewById(R.id.btn_minus);
            btnToggle = itemView.findViewById(R.id.btn_toggle);
            btnEdit = itemView.findViewById(R.id.btn_edit);

            // 레이아웃 파일(item_param.xml)에 이 ID가 있어야 함
            btnHelp = itemView.findViewById(R.id.btn_help);
        }

        void bind(ParamItem item) {
            tvTitle.setText(item.title);
            updateDisplayValue(item);

            // ★ 도움말 버튼 클릭 이벤트
            if (btnHelp != null) {
                btnHelp.setOnClickListener(v -> {
                    new AlertDialog.Builder(ParamActivity.this)
                            .setTitle(item.title)
                            .setMessage("Key: " + item.key + "\n\n" + item.description)
                            .setPositiveButton(getString(R.string.action_ok), null)
                            .show();
                });
            }

            layoutNumeric.setVisibility(View.GONE);
            btnToggle.setVisibility(View.GONE);
            btnEdit.setVisibility(View.GONE);

            switch (item.type) {
                case "INT":
                case "FLOAT":
                    layoutNumeric.setVisibility(View.VISIBLE);
                    btnPlus.setOnClickListener(v -> handleValueChange(item, item.step, tvValue));
                    btnMinus.setOnClickListener(v -> handleValueChange(item, -item.step, tvValue));
                    break;
                case "BOOL":
                    btnToggle.setVisibility(View.VISIBLE);
                    updateToggleStyle(item);
                    btnToggle.setOnClickListener(v -> {
                        // 🔥 [핵심 수정 2] 스위치 토글 시에도 원래 값을 기억하고, 실패 시 롤백합니다.
                        double oldValue = item.value; // 1. 원래 값을 기억합니다.
                        double newValue = (oldValue == 1.0) ? 0.0 : 1.0;

                        item.value = newValue; // 2. UI를 먼저 업데이트합니다.
                        updateDisplayValue(item);
                        updateToggleStyle(item);

                        // 3. 서버 전송을 시도하고, 실패 콜백을 전달합니다.
                        sendParamUpdate(item, () -> {
                            // 4. 실패 시 실행될 롤백 로직
                            item.value = oldValue; // 값을 원래대로 되돌립니다.
                            updateDisplayValue(item);
                            updateToggleStyle(item);
                        });
                    });
                    break;
                default:
                    btnEdit.setVisibility(View.VISIBLE);
                    break;
            }
        }

        // 🔥 [핵심 수정 3] handleValueChange 메서드 시그니처를 변경하고 롤백 로직을 추가합니다.
        private void handleValueChange(ParamItem item, double delta, TextView valueTextView) {
            double oldValue = item.value; // 1. 원래 값을 기억합니다.
            double newValue = oldValue + delta;

            newValue = Math.round(newValue * 100000.0) / 100000.0;
            double epsilon = 0.000001;

            if (newValue >= (item.min - epsilon) && newValue <= (item.max + epsilon)) {
                item.value = newValue; // 2. UI를 먼저 업데이트합니다.
                if ("INT".equals(item.type)) item.value = Math.round(item.value);
                updateDisplayValue(item);

                // 3. 서버 전송을 시도하고, 실패 콜백을 전달합니다.
                sendParamUpdate(item, () -> {
                    // 4. 실패 시 실행될 롤백 로직
                    item.value = oldValue; // 값을 원래대로 되돌립니다.
                    updateDisplayValue(item);
                });
            } else {
                Toast.makeText(ParamActivity.this, getString(R.string.out_of_range), Toast.LENGTH_SHORT).show();
            }
        }

        private void updateDisplayValue(ParamItem item) {
            // ★ 수정됨: 스페셜 텍스트 확인 로직 강화

            if (item.specialTexts != null) {
                // 1. 실수형 키 (예: "0.5") 그대로 검색
                // 소수점 끝자리 0 제거 로직 (예: 2.0 -> 2)
                String floatKey = String.valueOf(item.value);
                if (floatKey.endsWith(".0")) {
                    floatKey = floatKey.replace(".0", "");
                }

                if (item.specialTexts.containsKey(floatKey)) {
                    tvValue.setText(item.specialTexts.get(floatKey));
                    return;
                }

                // 2. 정수형 키 검색은 값이 정말 정수일 때만 수행
                // (현재 값이 정수와 같은지 확인: 0.5 == 0 (False), 0.0 == 0 (True))
                if (item.value == (int) item.value) {
                    String intKey = String.valueOf((int) item.value);
                    if (item.specialTexts.containsKey(intKey)) {
                        tvValue.setText(item.specialTexts.get(intKey));
                        return;
                    }
                }
            }

            // 기존 로직 (스페셜 텍스트가 없을 때 숫자 표시)
            if ("INT".equals(item.type)) {
                tvValue.setText(String.valueOf((int) item.value));
            } else if ("BOOL".equals(item.type)) {
                tvValue.setText(item.value == 1.0 ? "ON" : "OFF");
            } else {
                // [수정] FLOAT 타입: 소수점 자릿수 자동 처리
                // 1. 실수 값을 문자열로 변환
                String formatted = String.valueOf(item.value);

                // 2. ".0"으로 끝나는 경우(정수 형태) 뒤에 ".0" 제거 (예: 5.0 -> 5)
                if (formatted.endsWith(".0")) {
                    formatted = formatted.substring(0, formatted.length() - 2);
                }

                // 3. 만약 부동소수점 오차로 0.001000003 처럼 길게 나온다면
                //    최대 소수점 4~5자리 정도에서 자르는 포맷팅 사용 권장
                //    여기서는 깔끔하게 String.format을 사용하여 뒤에 0을 제거하는 방식 적용
                if (item.step < 1.0) {
                    // 최대 소수점 5자리까지 표시하되, 뒤에 붙는 0은 제거
                    formatted = String.format(Locale.US, "%.5f", item.value);
                    while (formatted.endsWith("0")) {
                        formatted = formatted.substring(0, formatted.length() - 1);
                    }
                    if (formatted.endsWith(".")) {
                        formatted = formatted.substring(0, formatted.length() - 1);
                    }
                }

                tvValue.setText(formatted);
            }
        }

        private void updateToggleStyle(ParamItem item) {
            if (item.value == 1.0) {
                btnToggle.setText("ON");
                btnToggle.setBackgroundColor(Color.parseColor("#4CAF50"));
            } else {
                btnToggle.setText("OFF");
                btnToggle.setBackgroundColor(Color.parseColor("#9E9E9E"));
            }
        }

        private void sendParamUpdate(ParamItem item, Runnable onFailure) {
            new Thread(() -> {
                try {
                    URL url = new URL("http://" + HomeActivity.serverIp + ":5005/param/set");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setConnectTimeout(2000); // 2초 타임아웃 설정
                    conn.setDoOutput(true);

                    JSONObject json = new JSONObject();
                    json.put("param", item.key);
                    json.put("value", ("INT".equals(item.type) || "BOOL".equals(item.type)) ? (int)item.value : item.value);

                    java.io.OutputStream os = conn.getOutputStream();
                    os.write(json.toString().getBytes());
                    os.flush();
                    os.close();

                    int code = conn.getResponseCode();
                    if (code != 200) {                // 3. 서버 응답이 실패했을 경우에도 롤백 콜백을 실행합니다.
                        if (onFailure != null) {
                            runOnUiThread(onFailure);
                        }
                        String message = getString(R.string.param_update_failed, String.valueOf(code));
                        runOnUiThread(() -> Toast.makeText(ParamActivity.this, message, Toast.LENGTH_SHORT).show());
                    }
                    Log.d("ParamActivity", "Update Response: " + code);
                    conn.disconnect();
                } catch (Exception e) {
                    e.printStackTrace();
                    if (onFailure != null) {
                        runOnUiThread(onFailure);
                    }
                    runOnUiThread(() -> Toast.makeText(ParamActivity.this, getString(R.string.param_update_error), Toast.LENGTH_SHORT).show());
                }
            }).start();
        }
    } // ★ 여기서 ParamViewHolder 클래스 종료

    // =================================================================
    // 아래 메서드들은 ParamViewHolder 밖, ParamActivity 안에 위치해야 합니다.
    // =================================================================

    // ★ 3. 현재 파라미터 값 전체 가져오기 (누락되었던 메서드 추가)
    private void fetchCurrentValues() {
        new Thread(() -> {
            try {
                // 서버의 /param/get_all 주소 호출 (서버에 해당 API가 있어야 함)
                // 만약 /param/get_all 이 없다면 /api/params 등 서버 설정에 맞는 주소 사용
                URL url = new URL("http://" + HomeActivity.serverIp + ":5005/param/get_all");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(3000);

                if (conn.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();

                    // UI 업데이트
                    runOnUiThread(() -> updateParamValues(sb.toString()));
                }
                conn.disconnect();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    // ★ 4. 가져온 값(JSON)을 리스트에 반영하기 (위치를 수정함)
    private void updateParamValues(String jsonStr) {
        try {
            JSONObject json = new JSONObject(jsonStr);

            // 전체 파라미터 리스트를 순회하며 값 업데이트
            for (ParamItem item : allParams) {
                // JSON에 해당 키가 있는지 확인
                if (json.has(item.key)) {
                    Object valObj = json.get(item.key);
                    double dVal = 0;

                    if (valObj instanceof Boolean) {
                        dVal = ((Boolean) valObj) ? 1.0 : 0.0;
                    } else if (valObj instanceof Number) {
                        dVal = ((Number) valObj).doubleValue();
                    } else {
                        try {
                            dVal = Double.parseDouble(valObj.toString());
                        } catch (NumberFormatException e) {
                            dVal = 0; // 변환 실패 시 0
                        }
                    }

                    item.value = dVal;
                }
            }
            // 어댑터 갱신하여 화면에 반영
            adapter.notifyDataSetChanged();

            // 현재 선택된 그룹에 맞게 리스트 다시 필터링 (화면 갱신)
            filterList(currentGroup);

        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

} // ParamActivity 클래스 종료
