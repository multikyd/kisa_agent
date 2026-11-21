package com.kisapilot.kisaagent;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;

public class ScreenRecsActivity extends AppCompatActivity implements VideoAdapter.OnItemClickListener {

    private RecyclerView rvVideos;
    private VideoAdapter videoAdapter;
    private List<VideoItem> videoList = new ArrayList<>();
    private ActionMode actionMode;
    private ActionMode.Callback actionModeCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_screen_recs);

        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        rvVideos = findViewById(R.id.rv_videos);
        rvVideos.setLayoutManager(new GridLayoutManager(this, 2));

        // 어댑터 생성 시, 클릭 리스너로 'this' 전달
        videoAdapter = new VideoAdapter(this, videoList, this);
        rvVideos.setAdapter(videoAdapter);

        // 액션 모드 콜백 설정
        setupActionModeCallback();

        // 서버 연결 확인 후 목록 로드
        String serverIp = HomeActivity.serverIp;
        if (serverIp == null || serverIp.equals("Not connected")) {
            Toast.makeText(this, getString(R.string.server_not_connected), Toast.LENGTH_LONG).show();
        } else {
            loadScreenRecs();
        }
    }

    // --- VideoAdapter.OnItemClickListener 구현부 ---
    @Override
    public void onItemClick(VideoItem item, int position) {
        if (videoAdapter.isMultiSelectMode()) {
            // 선택 모드일 때 클릭하면, 선택 상태를 토글
            toggleSelection(position);
        } else {
            // 일반 모드일 때 클릭하면, ExoPlayer 실행
            openExoPlayer(item);
        }
    }

    @Override
    public void onItemLongClick(VideoItem item, int position) {
        if (!videoAdapter.isMultiSelectMode()) {
            // 일반 모드에서 롱클릭하면, 선택 모드 시작
            actionMode = startSupportActionMode(actionModeCallback);
            toggleSelection(position);
        }
    }
    // ----------------------------------------

    private void toggleSelection(int position) {
        videoAdapter.toggleSelection(position);
        int count = videoAdapter.getSelectedItems().size();
        if (count == 0 && actionMode != null) {
            actionMode.finish();
        } else if (actionMode != null) {
            actionMode.setTitle(getString(R.string.items_selected, count));
            actionMode.invalidate(); // onPrepareActionMode를 다시 호출하여 메뉴 업데이트
        }
    }

    private void setupActionModeCallback() {
        actionModeCallback = new ActionMode.Callback() {
            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                mode.getMenuInflater().inflate(R.menu.menu_action_mode, menu);
                videoAdapter.setMultiSelectMode(true);
                return true;
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                updateSelectAllMenuTitle(menu.findItem(R.id.action_select_all));
                return true;
            }

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                int itemId = item.getItemId();
                if (itemId == R.id.action_delete) {
                    showDeleteConfirmationDialog(videoAdapter.getSelectedItems());
                    return true;
                } else if (itemId == R.id.action_select_all) {
                    videoAdapter.selectAll();
                    int count = videoAdapter.getSelectedItems().size();
                    mode.setTitle(getString(R.string.items_selected, count));
                    mode.invalidate(); // 메뉴 텍스트 즉시 갱신
                    return true;
                }
                return false;
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) {
                videoAdapter.setMultiSelectMode(false);
                actionMode = null;
            }
        };
    }

    private void updateSelectAllMenuTitle(MenuItem selectAllItem) {
        if (selectAllItem != null) {
            boolean allSelected = !videoList.isEmpty() && videoAdapter.getSelectedItems().size() == videoList.size();
            if (allSelected) {
                selectAllItem.setTitle(getString(R.string.action_deselect_all));
            } else {
                selectAllItem.setTitle(getString(R.string.action_select_all));
            }
        }
    }

    // --- 데이터 로딩 및 클릭 이벤트 처리 메서드들 ---
    private void loadScreenRecs() {
        new Thread(() -> {
            try {
                String serverIp = HomeActivity.serverIp;
                String urlStr = "http://" + serverIp + ":5005/videos_files";
                String jsonResponse = getResponseFromUrl(urlStr);
                if (jsonResponse == null) return;

                List<VideoItem> newVideoList = new ArrayList<>();
                JSONArray items = new JSONObject(jsonResponse).getJSONArray("items");

                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    if (!item.getBoolean("is_dir") && item.getString("name").endsWith(".mp4")) {
                        String name = item.getString("name");
                        String path = item.getString("path");
                        String thumbName = name.replace(".mp4", ".jpg");
                        String thumbUrl = "http://" + serverIp + ":5005/media/videos/" + thumbName;
                        long sizeInBytes = item.optLong("size", 0); // "size" 키로 long 값을 가져오고, 없으면 0을 반환

                        // 'modified' 시간을 displayTitle로 사용
                        String displayTitle = item.optString("modified", name);
                        if (displayTitle.length() > 16) {
                            displayTitle = displayTitle.substring(0, 16);
                        }

                        // 생성자: (routeName, displayTitle, thumbnailUrl, segmentCount, type, path)
                        // Incorrect argument order
                        newVideoList.add(new VideoItem(displayTitle, name, 0, thumbUrl, VideoItem.TYPE_SCREEN_REC, path, sizeInBytes));

                    }
                }

                // ★★★ [핵심 수정] newVideoList를 displayTitle(수정 날짜) 기준으로 내림차순 정렬합니다. ★★★
                Collections.sort(newVideoList, (o1, o2) -> o2.getDisplayTitle().compareTo(o1.getDisplayTitle()));

                runOnUiThread(() -> {
                    videoList.clear();
                    // 정렬된 리스트를 그대로 추가합니다.
                    videoList.addAll(newVideoList);
                    videoAdapter.notifyDataSetChanged();
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    String getFormattedSize(long size) {
        if (size <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        return new java.text.DecimalFormat("#,##0.#").format(size / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
    }

    private void openExoPlayer(VideoItem clickedItem) { // 파라미터 이름을 clickedItem으로 명확하게 변경
        // ★★★ [핵심 수정 1] 새로운 리스트를 만들어 시간순(오래된 순 -> 최신순)으로 다시 정렬합니다. ★★★
        List<VideoItem> sortedList = new ArrayList<>(videoList);
        // getDisplayTitle()은 날짜/시간 문자열이므로, 문자열 오름차순 정렬을 하면 시간순 정렬이 됩니다.
        Collections.sort(sortedList, (o1, o2) -> o1.getDisplayTitle().compareTo(o2.getDisplayTitle()));

        // ★★★ [핵심 수정 2] 시간순으로 정렬된 리스트로 재생 목록을 만듭니다. ★★★
        ArrayList<String> fullPlaylist = new ArrayList<>();
        for (VideoItem item : sortedList) {
            fullPlaylist.add(item.getPath());
        }

        // ★★★ [핵심 수정 3] 사용자가 클릭한 영상이 '시간순으로 정렬된 목록'에서 몇 번째인지 찾습니다. ★★★
        int clickedIndex = sortedList.indexOf(clickedItem);

        // VideoPlayerActivity에 전체 재생 목록과 클릭된 영상의 순서를 전달합니다.
        Intent intent = new Intent(this, VideoPlayerActivity.class);
        intent.putStringArrayListExtra("video_playlist", fullPlaylist);
        intent.putExtra("current_index", clickedIndex);
        startActivity(intent);
    }

    // --- 삭제 관련 메서드들 ---
    private void showDeleteConfirmationDialog(final List<VideoItem> itemsToDelete) {
        if (itemsToDelete == null || itemsToDelete.isEmpty()) {
            Toast.makeText(this, getString(R.string.delete_no_items_selected), Toast.LENGTH_SHORT).show();
            return; // 0개면 팝업을 띄우지 않고 즉시 종료
        }

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.delete_dialog_title_multi, itemsToDelete.size()))
                .setMessage(getString(R.string.delete_dialog_message_multi))
                .setPositiveButton(getString(R.string.delete_dialog_button_delete), (dialog, which) -> deleteFilesOnServer(itemsToDelete))
                .setNegativeButton(getString(R.string.delete_dialog_button_cancel), null)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .show();
    }

    private void deleteFilesOnServer(final List<VideoItem> itemsToDelete) {
        new Thread(() -> {
            List<String> pathsToDelete = new ArrayList<>();
            for (VideoItem item : itemsToDelete) {
                pathsToDelete.add(item.getPath()); // MP4는 파일 경로만 추가
            }

            try {
                String serverIp = HomeActivity.serverIp;
                URL url = new URL("http://" + serverIp + ":5005/file/delete");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; utf-8");
                conn.setDoOutput(true);

                JSONObject jsonParam = new JSONObject();
                jsonParam.put("paths", new JSONArray(pathsToDelete));

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = jsonParam.toString().getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                if (conn.getResponseCode() == 200 || conn.getResponseCode() == 207) {
                    runOnUiThread(() -> {
                        Toast.makeText(this, getString(R.string.delete_success_multi), Toast.LENGTH_SHORT).show();
                        if (actionMode != null) {
                            actionMode.finish();
                        }
                        loadScreenRecs(); // 목록 새로고침
                    });
                } else {
                    final String errorMessage = conn.getResponseMessage();
                    runOnUiThread(() -> Toast.makeText(this, getString(R.string.delete_failed, errorMessage), Toast.LENGTH_LONG).show());
                }
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(this, getString(R.string.delete_error), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private String getResponseFromUrl(String urlString) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                return sb.toString();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}
