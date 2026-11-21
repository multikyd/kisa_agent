package com.kisapilot.kisaagent;

import android.content.Intent;
import android.net.Uri;
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
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class VideoListActivity extends AppCompatActivity implements VideoAdapter.OnItemClickListener {

    private DriveLogDownloader logDownloader;
    private JSONArray logManifest = new JSONArray();
    private RecyclerView rvVideos;
    private VideoAdapter videoAdapter;
    private List<VideoItem> videoList = new ArrayList<>();
    private ActionMode actionMode;
    private ActionMode.Callback actionModeCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_list);
        logDownloader = new DriveLogDownloader(this, savedInstanceState);

        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        rvVideos = findViewById(R.id.rv_videos);
        rvVideos.setLayoutManager(new GridLayoutManager(this, 2));

        videoAdapter = new VideoAdapter(this, videoList, this);
        rvVideos.setAdapter(videoAdapter);

        setupActionModeCallback();

        String serverIp = HomeActivity.serverIp;
        if (serverIp == null || serverIp.equals("Not connected")) {
            Toast.makeText(this, "서버에 연결되지 않았습니다.", Toast.LENGTH_LONG).show();
        } else {
            loadDriveLogsGroupedByRoute();
        }
    }

    @Override
    public void onItemClick(VideoItem item, int position) {
        if (videoAdapter.isMultiSelectMode()) {
            toggleSelection(position);
        } else {
            new AlertDialog.Builder(this).setTitle(item.getDisplayTitle())
                .setItems(new String[]{"주행 로그 다운로드", "영상 보기"}, (dialog, which) -> {
                    if (which == 0) logDownloader.choose(Collections.singletonList(item), logManifest);
                    else showCameraSelectionDialog(item);
                }).show();
        }
    }

    @Override
    public void onItemLongClick(VideoItem item, int position) {
        if (!videoAdapter.isMultiSelectMode()) {
            actionMode = startSupportActionMode(actionModeCallback);
            toggleSelection(position);
        }
    }

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
                menu.add(0, 9876, 0, "다운로드").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
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
                if (itemId == 9876) {
                    logDownloader.choose(new ArrayList<>(videoAdapter.getSelectedItems()), logManifest);
                    return true;
                } else if (itemId == R.id.action_delete) {
                    // ★★★ 1. 여기서 한번 더 확인하는 로직으로 변경 ★★★
                    showDeleteConfirmationDialog(videoAdapter.getSelectedItems());
                    return true;
                } else if (itemId == R.id.action_select_all) {
                    videoAdapter.selectAll();
                    int count = videoAdapter.getSelectedItems().size();
                    mode.setTitle(getString(R.string.items_selected, count));
                    mode.invalidate(); // ★★★ 2. invalidate() 호출을 추가하여 메뉴 텍스트 즉시 갱신 ★★★
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


    private void loadDriveLogsGroupedByRoute() {
        new Thread(() -> {
            try {
                String serverIp = HomeActivity.serverIp;
                String urlStr = "http://" + serverIp + ":5005/realdata_files";
                String jsonResponse = getResponseFromUrl(urlStr);
                if (jsonResponse == null) throw new java.io.IOException("서버 응답 없음");

                // ★★★ [핵심 수정 1] 서버가 보낸 전체 목록을 그대로 사용합니다. ★★★
                JSONArray allItemsFromServer = new JSONObject(jsonResponse).getJSONArray("items");

                // --- 1. 폴더만 필터링하여 주행 기록 그룹(routes) 만들기 ---
                Map<String, List<JSONObject>> routes = new LinkedHashMap<>();
                for (int i = 0; i < allItemsFromServer.length(); i++) {
                    JSONObject itemJson = allItemsFromServer.getJSONObject(i);
                    if (itemJson.getBoolean("is_dir")) { // 폴더인 경우에만
                        String segName = itemJson.getString("name");
                        // 폴더 이름에 "--"가 포함되어 있는지 확인하여 안정성 추가
                        if (segName.matches(".+--.+--[0-9]+")) {
                            String routeName = segName.split("--")[0] + "--" + segName.split("--")[1];
                            routes.computeIfAbsent(routeName, k -> new ArrayList<>()).add(itemJson);
                        }
                    }
                }

                // --- 2. 그룹화된 routes를 기반으로 최종 VideoItem 리스트 만들기 ---
                List<VideoItem> newVideoList = new ArrayList<>();
                for (Map.Entry<String, List<JSONObject>> entry : routes.entrySet()) {
                    String routeName = entry.getKey();
                    List<JSONObject> segmentsInRoute = entry.getValue();
                    segmentsInRoute.sort(Comparator.comparingInt(a -> Integer.parseInt(a.optString("name", "0--0").split("--")[2])));

                    JSONObject firstSegment = segmentsInRoute.get(0);
                    String firstSegmentName = firstSegment.getString("name");
                    String thumbUrl = "http://" + serverIp + ":5005/media/realdata/" + firstSegmentName + "/preview.jpg";
                    int segmentCount = segmentsInRoute.size();

                    long totalSize = 0;

                    // ★★★ [핵심 수정 2] 'allItemsFromServer'(전체 목록)에서 fcamera.hevc 파일 정보를 찾습니다. ★★★
                    String displayTitle = "";
                    for (int i = 0; i < allItemsFromServer.length(); i++) {
                        JSONObject fileJson = allItemsFromServer.getJSONObject(i);
                        // 파일이고, 경로가 정확히 일치하는지 확인
                        if (!fileJson.getBoolean("is_dir") && fileJson.getString("path").endsWith(firstSegmentName + "/fcamera.hevc")) {
                            totalSize += fileJson.optLong("size", 0);
                            displayTitle = logTimeTitle(fileJson, routeName);
                            break; // 찾았으면 루프 종료
                        }
                    }

                    // 예외 처리: 만약 fcamera.hevc 정보를 못 찾았을 경우
                    if (displayTitle.isEmpty()) {
                        displayTitle = logTimeTitle(firstSegment, routeName);
                    }

                    // 시간 형식 맞추기


                    // VideoItem 생성
                    VideoItem routeItem = new VideoItem(routeName, displayTitle, segmentCount, thumbUrl, VideoItem.TYPE_DRIVE_LOG, routeName, totalSize);
                    for (JSONObject seg : segmentsInRoute) {
                        routeItem.addSegmentPath(seg.getString("path"));
                    }
                    newVideoList.add(routeItem);
                }

                // 최종 정렬 및 UI 업데이트
                Collections.sort(newVideoList, (o1, o2) -> o2.getDisplayTitle().compareTo(o1.getDisplayTitle()));

                runOnUiThread(() -> {
                    logManifest = allItemsFromServer;
                    if (isFinishing() || isDestroyed()) return;
                    videoList.clear();
                    videoList.addAll(newVideoList);
                    videoAdapter.notifyDataSetChanged();
                });

            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "주행 로그 목록을 불러오지 못했습니다.", Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private String logTimeTitle(JSONObject item, String fallback) {
        long epoch=item.optLong("modified_unix",0);
        if(epoch>0)return LogTime.kst(epoch)+" · 파일 수정";
        String value=item.optString("modified",fallback);
        if("Asia/Seoul".equals(item.optString("modified_timezone")))return value+" KST · 파일 수정";
        return value+" · 시간대 미확인 (C4 에이전트 업데이트 필요)";
    }
    private void showCameraSelectionDialog(VideoItem routeItem) {
        final CharSequence[] cameraOptions = {
                getString(R.string.camera_fcamera),
                getString(R.string.camera_ecamera),
                getString(R.string.camera_dcamera)
        };
        final String[] cameraFiles = {"fcamera.hevc", "ecamera.hevc", "dcamera.hevc"};
        new AlertDialog.Builder(this)
                .setTitle(String.format(getString(R.string.camera_selection_dialog_title), routeItem.getDisplayTitle()))
                .setItems(cameraOptions, (dialog, which) -> {
                    checkFileExistsAndPlay(routeItem, cameraFiles[which]);
                })
                .show();
    }

    private void openWebPlayer(VideoItem routeItem, String videoFileName) {
        String serverIp = HomeActivity.serverIp;
        String firstSegmentPath = routeItem.getSegmentPaths().get(0);
        String firstSegmentName = new File(firstSegmentPath).getName();
        String relativePath = "realdata/" + firstSegmentName + "/" + videoFileName;
        String urlToOpen = "http://" + serverIp + ":5005/playlist/" + relativePath;
        runOnUiThread(() -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(urlToOpen))));
    }

    private String getResponseFromUrl(String urlString) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlString).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            if (conn.getResponseCode() != 200) return null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder text = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) text.append(line);
                return text.toString();
            }
        } catch (Exception e) { return null; }
        finally { if (conn != null) conn.disconnect(); }
    }

    // ★★★ [핵심] 비어있던 헬퍼 메서드들의 실제 코드를 모두 채워 넣습니다. ★★★
    private void checkFileExistsAndPlay(VideoItem routeItem, String videoFileName) {
        new Thread(() -> {
            try {
                String serverIp = HomeActivity.serverIp;
                String firstSegmentPath = routeItem.getSegmentPaths().get(0);
                String fullPathToCheck = firstSegmentPath + "/" + videoFileName;
                String encodedPath = URLEncoder.encode(fullPathToCheck, "UTF-8");
                String urlStr = "http://" + serverIp + ":5005/file/exists?path=" + encodedPath;

                String jsonResponse = getResponseFromUrl(urlStr);
                if (jsonResponse == null) {
                    runOnUiThread(() -> Toast.makeText(this, getString(R.string.server_no_response), Toast.LENGTH_SHORT).show());
                    return;
                }

                JSONObject response = new JSONObject(jsonResponse);
                if (response.getBoolean("exists")) {
                    openWebPlayer(routeItem, videoFileName);
                } else {
                    runOnUiThread(() -> Toast.makeText(this, String.format(getString(R.string.video_not_found), videoFileName), Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(this, String.format(getString(R.string.error_occurred), e.getMessage()), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // ★★★ 3. 삭제 팝업 메서드에 최종 방어 로직 추가 ★★★
    private void showDeleteConfirmationDialog(final List<VideoItem> itemsToDelete) {
        if (itemsToDelete == null || itemsToDelete.isEmpty()) {
            Toast.makeText(this, getString(R.string.delete_no_items_selected), Toast.LENGTH_SHORT).show();
            return; // 0개면 팝업을 띄우지 않고 즉시 종료
        }

        new AlertDialog.Builder(this)
                .setTitle(String.format(getString(R.string.delete_dialog_title_multi), itemsToDelete.size()))
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
                if (item.getType() == VideoItem.TYPE_DRIVE_LOG) {
                    pathsToDelete.addAll(item.getSegmentPaths());
                } else {
                    pathsToDelete.add(item.getPath());
                }
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

                int responseCode = conn.getResponseCode();
                if (responseCode == 200 || responseCode == 207) {
                    runOnUiThread(() -> {
                        Toast.makeText(this, getString(R.string.delete_success_multi), Toast.LENGTH_SHORT).show();
                        if (actionMode != null) {
                            actionMode.finish();
                        }
                        loadDriveLogsGroupedByRoute();
                    });
                } else {
                    final String errorMessage = conn.getResponseMessage();
                    runOnUiThread(() -> Toast.makeText(this, String.format(getString(R.string.delete_failed), errorMessage), Toast.LENGTH_LONG).show());
                }
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(this, getString(R.string.delete_error), Toast.LENGTH_LONG).show());
            }
        }).start();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        logDownloader.save(out);
    }
    @Override protected void onDestroy() {
        logDownloader.destroy();
        super.onDestroy();
    }

}
