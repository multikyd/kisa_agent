package com.kisapilot.kisaagent;

import android.os.Bundle;
import android.os.Build;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.IOException;
import java.util.List;
import java.io.File;
import java.util.ArrayList;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;import java.net.HttpURLConnection;
import java.net.URL;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.appcompat.app.AlertDialog;
import android.widget.ScrollView;
import android.content.Intent;
import android.net.Uri;
import androidx.activity.OnBackPressedCallback;
import android.widget.ImageButton;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.InputStream;
import java.io.OutputStream;

public class FileExplorerActivity extends AppCompatActivity {

    private FileItem pendingDownload;
    private final ActivityResultLauncher<String> downloadDestination = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/octet-stream"), uri -> {
                FileItem item = pendingDownload;
                pendingDownload = null;
                if (uri != null && item != null) downloadFile(item, uri);
            });

    private TextView tvCurrentPath;
    private RecyclerView rvFiles;
    private FileAdapter fileAdapter;
    private List<FileItem> fileList = new ArrayList<>();
    private String currentPath = "/data/openpilot";

    private String clipboardPath = null; // 복사할 파일/폴더의 경로를 저장
    private LinearLayout pasteLayout;    // 붙여넣기/취소 버튼을 담을 레이아웃
    private Button btnPaste;             // 붙여넣기 버튼
    private Button btnCancelPaste;       // 취소 버튼

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null && savedInstanceState.containsKey("download_path")) {
            pendingDownload = new FileItem(savedInstanceState.getString("download_name"),
                    savedInstanceState.getString("download_path"), false, 0, "");
        }
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
        setContentView(R.layout.activity_file_explorer);

        // Edge-to-edge 디스플레이 활성화 (시스템 바와 겹치지 않도록 처리)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            // 시스템 바(상태 바, 네비게이션 바)가 차지하는 영역을 가져옴
            int top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
            int bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;

            // 레이아웃의 패딩을 시스템 바의 크기만큼 설정
            v.setPadding(v.getPaddingLeft(), top, v.getPaddingRight(), bottom);

            // 처리된 인셋 정보를 반환
            return insets;
        });

        tvCurrentPath = findViewById(R.id.tv_current_path);
        rvFiles = findViewById(R.id.rv_files);

        // RecyclerView 설정
        rvFiles.setLayoutManager(new LinearLayoutManager(this));
        fileAdapter = new FileAdapter(fileList, item -> {
            if (item.isDir()) {
                loadPath(item.getPath());
            } else {
                String fileName = item.getName();
                String filePath = item.getPath();

                // ★★★ [핵심] HEVC 파일인 경우, 웹 브라우저를 엽니다. ★★★
                if (fileName.equals("ecamera.hevc") || fileName.equals("fcamera.hevc") || fileName.equals("dcamera.hevc")) {
                    String serverIp = HomeActivity.serverIp;
                    if (serverIp == null || serverIp.equals("Not connected")) {
                        Toast.makeText(this, getString(R.string.server_not_connected), Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // 1. '/data/media/0/' 부분을 제거하여 상대 경로를 만듭니다.
                    String relativePath = filePath.replace("/data/media/0/", "");
                    // 예: "realdata/00...--0/ecamera.hevc"

                    // 2. 새로운 /playlist/ API를 호출하는 URL을 생성합니다.
                    String urlToOpen = "http://" + serverIp + ":5005/playlist/" + relativePath;

                    // 3. ACTION_VIEW 인텐트를 사용하여 웹 브라우저를 실행합니다.
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(urlToOpen));
                    startActivity(browserIntent);
                }
                // ★★★ MP4 파일은 기존처럼 앱 내 플레이어로 재생합니다. ★★★
                else if (fileName.toLowerCase().endsWith(".mp4")) {
                    ArrayList<String> singleVideoList = new ArrayList<>();
                    singleVideoList.add(filePath);
                    Intent intent = new Intent(this, VideoPlayerActivity.class);
                    // VideoPlayerActivity가 요구하는 형식으로 데이터를 전달
                    intent.putStringArrayListExtra("video_playlist", singleVideoList);
                    intent.putExtra("current_index", 0);
                    startActivity(intent);
                }
                // ★★★ [핵심 수정 1] 이미지 파일인지 확인하는 로직 추가 ★★★
                else if (isImageViewable(fileName)) {
                    openInBrowser(filePath);
                }
                // 텍스트 파일 처리 (기존과 동일)
                else if (isTextViewable(fileName)) {
                    showFileContent(item);
                }
                // 그 외 모든 파일 처리 (기존과 동일)
                else {
                    Toast.makeText(this, fileName + getString(R.string.file_not_supported), Toast.LENGTH_SHORT).show();
                }
            }
        });

        fileAdapter.setOnItemLongClickListener(item -> {
            // ".." 상위 폴더 항목은 롱클릭 이벤트를 무시합니다.
            if (item.getName().equals("..")) {
                return;
            }
            // 팝업 메뉴를 띄우는 메서드를 호출합니다.
            showFileActionDialog(item);
        });

        rvFiles.setAdapter(fileAdapter);

        String startPath = getIntent().getStringExtra("start_path");
        if (startPath != null && !startPath.isEmpty()) {
            // 전달받은 경로가 있으면 그 경로로 시작
            currentPath = startPath;
        }

        pasteLayout = findViewById(R.id.layout_paste); // activity_file_explorer.xml에 추가해야 함
        btnPaste = findViewById(R.id.btn_paste);
        btnCancelPaste = findViewById(R.id.btn_cancel_paste);

        // 붙여넣기 버튼 클릭 리스너
        btnPaste.setOnClickListener(v -> {
            if (clipboardPath != null) {
                requestCopyFile(clipboardPath, currentPath); // 복사 실행
            }
        });

        // 취소 버튼 클릭 리스너
        btnCancelPaste.setOnClickListener(v -> {
            clearClipboard(); // 복사 상태 취소
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // 현재 경로가 최상위('/')가 아닌지 확인
                File currentFile = new File(currentPath);
                String parentPath = currentFile.getParent();

                if (parentPath != null && !currentPath.equals("/")) {
                    // 상위 폴더가 있으면, 상위 폴더로 이동
                    loadPath(parentPath);
                } else {
                    // 최상위 폴더이면, 종료 확인 팝업을 띄움
                    showExitConfirmationDialog();
                }
            }
        });

        // 초기 경로 로드
        loadPath(currentPath);

        ImageButton btnClose = findViewById(R.id.btn_close_explorer);
        btnClose.setOnClickListener(v -> {
            finish();
        });
    }

    private void showFileActionDialog(final FileItem item) {
        // 팝업 메뉴에 표시될 옵션 목록
        final CharSequence[] options;
        if (item.isDir()) {
            // 폴더일 경우
            options = new CharSequence[]{
                    getString(R.string.action_rename),
                    getString(R.string.action_delete),
                    getString(R.string.action_copy),
                    getString(R.string.action_cancel)
            };
        } else {
            // 파일일 경우
            options = new CharSequence[]{
                    getString(R.string.action_rename),
                    getString(R.string.action_delete),
                    getString(R.string.action_copy),
                    getString(R.string.action_download), // ★★★ 다운로드 옵션 추가
                    getString(R.string.action_cancel)
            };
        }

        // AlertDialog 빌더를 사용하여 팝업 메뉴를 구성합니다.
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(String.format(getString(R.string.action_dialog_title), item.getName()));

        // 각 옵션을 눌렀을 때 어떤 동작을 할지 설정합니다.
        builder.setItems(options, (dialog, which) -> {
            if (options[which].equals(getString(R.string.action_rename))) {
                // TODO: 이름 바꾸기 다이얼로그 띄우기 (다음 단계에서 구현)
                showRenameDialog(item); // 이 부분도 미리 수정
            } else if (options[which].equals(getString(R.string.action_delete))) {
                // ★★★ [수정] Toast 메시지 대신, 삭제 확인 다이얼로그를 띄우는 메서드를 호출합니다. ★★★
                showDeleteConfirmationDialog(item);
            } else if (options[which].equals(getString(R.string.action_copy))) {
                // ★★★ [신설] 복사 기능 호출 ★★★
                copyToClipboard(item);
            } else if (options[which].equals(getString(R.string.action_download))) {
                // ★★★ 다운로드 메서드 호출 ★★★
                requestDownloadFile(item);
            } else if (options[which].equals(getString(R.string.action_cancel))) {
                dialog.dismiss();
            }
        });

        // 만들어진 팝업 메뉴를 화면에 보여줍니다.
        builder.show();
    }

    // ★★★ [신설] 삭제 확인 다이얼로그를 보여주는 메서드 ★★★
    private void showDeleteConfirmationDialog(final FileItem item) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.delete_dialog_title))
                .setMessage(String.format(getString(R.string.delete_dialog_message), item.getName()))
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setPositiveButton(getString(R.string.delete_dialog_delete), (dialog, which) -> requestDeleteFileOnServer(item.getPath()))
                .setNegativeButton(getString(R.string.action_cancel), null)
                .show();
    }

    // ★★★ [신설] 서버에 삭제 요청을 보내는 메서드 ★★★
    private void requestDeleteFileOnServer(String path) {
        // HomeActivity.serverIp를 직접 참조하는 대신, Intent로 전달받은 멤버 변수를 사용하는 것이 더 좋습니다.
        String serverIp = HomeActivity.serverIp;
        if (serverIp == null || serverIp.equals("Not connected")) {
            Toast.makeText(this, getString(R.string.server_not_connected), Toast.LENGTH_SHORT).show();
            return;
        }

        new Thread(() -> {
            try {
                String urlStr = "http://" + serverIp + ":5005/file/explorer_delete";
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; utf-8");
                conn.setDoOutput(true);

                JSONObject jsonParam = new JSONObject();
                jsonParam.put("path", path);

                try (java.io.OutputStream os = conn.getOutputStream()) {
                    byte[] input = jsonParam.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                if (conn.getResponseCode() == 200) {
                    runOnUiThread(() -> {
                        Toast.makeText(this, getString(R.string.delete_success), Toast.LENGTH_SHORT).show();
                        loadPath(currentPath); // ★ 목록 새로고침
                    });
                } else {
                    // TODO: 서버 에러 메시지를 사용자에게 보여주는 로직
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    // ★★★ [신설] 이름 바꾸기 입력을 받는 다이얼로그를 보여주는 메서드 ★★★
    private void showRenameDialog(final FileItem item) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.rename_dialog_title));

        // 다이얼로그에 EditText(텍스트 입력창)을 추가합니다.
        final EditText input = new EditText(this);
        input.setText(item.getName()); // 현재 파일/폴더 이름을 기본값으로 설정
        builder.setView(input);

        // '확인' 버튼을 눌렀을 때의 동작
        builder.setPositiveButton(getString(R.string.rename_dialog_ok), (dialog, which) -> {
            String newName = input.getText().toString().trim();
            if (!newName.isEmpty() && !newName.equals(item.getName())) {
                renameFile(item.getPath(), newName);
            }
        });
        // '취소' 버튼을 눌렀을 때의 동작
        builder.setNegativeButton(getString(R.string.rename_dialog_cancel), (dialog, which) -> dialog.cancel());

        builder.show();
    }

    // ★★★ [신설] 서버에 이름 바꾸기 요청을 보내는 메서드 ★★★
    private void renameFile(String oldPath, String newName) {
        String serverIp = HomeActivity.serverIp;
        if (serverIp == null || serverIp.equals("Not connected")) {
            Toast.makeText(this, getString(R.string.server_not_connected), Toast.LENGTH_SHORT).show();
            return;
        }

        new Thread(() -> {
            try {
                String urlStr = "http://" + serverIp + ":5005/file/explorer_rename";
                URL url = new URL(urlStr);
                // ... (requestDeleteFileOnServer과 유사하게 POST 요청 로직 구현) ...
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; utf-8");
                conn.setDoOutput(true);JSONObject jsonParam = new JSONObject();
                jsonParam.put("old_path", oldPath);
                jsonParam.put("new_name", newName);

                try (java.io.OutputStream os = conn.getOutputStream()) {
                    byte[] input = jsonParam.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                if (conn.getResponseCode() == 200) {
                    runOnUiThread(() -> {
                        Toast.makeText(this, getString(R.string.rename_success), Toast.LENGTH_SHORT).show();
                        loadPath(currentPath); // ★ 목록 새로고침
                    });
                } else {
                    // TODO: 서버 에러 메시지를 사용자에게 보여주는 로직 (예: Toast)
                    // 예를 들어, conn.getErrorStream()을 읽어서 메시지를 표시할 수 있습니다.
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    // ★★★ [신설] 파일을 클립보드(복사 대기 상태)에 저장하는 메서드 ★★★
    private void copyToClipboard(FileItem item) {
        this.clipboardPath = item.getPath();
        updatePasteUI();
    }

    // ★★★ [신설] 클립보드를 비우고 붙여넣기 UI를 숨기는 메서드 ★★★
    private void clearClipboard() {
        this.clipboardPath = null;
        updatePasteUI(); // updatePasteUI가 내부적으로 visibility를 GONE으로 설정합니다.

        // 🔥 핵심: 버튼의 상태를 항상 활성화된 초기 상태로 되돌립니다.
        btnPaste.setEnabled(true);
        btnCancelPaste.setEnabled(true);
        // 기본 텍스트로 되돌릴 수도 있습니다. (선택 사항)
        btnPaste.setText(getString(R.string.paste_button_default));
    }

    // ★★★ [신설] 붙여넣기 UI의 보이기/숨기기 상태를 업데이트하는 메서드 ★★★
    private void updatePasteUI() {
        // 1. 복사 대기 중인 파일이 없으면 UI를 숨기고 종료합니다.
        if (clipboardPath == null) {
            pasteLayout.setVisibility(View.GONE);
            return;
        }
        // 2. UI를 화면에 표시합니다.
        pasteLayout.setVisibility(View.VISIBLE);

        // 3. 복사할 파일이 있는 폴더 경로를 구합니다.
        java.io.File sourceFile = new java.io.File(clipboardPath);
        String sourceParentPath = sourceFile.getParent();
        if (sourceParentPath == null) {
            sourceParentPath = "/";
        }

        // 🔥 핵심: 현재 경로와 복사할 파일의 부모 경로가 같은지 비교합니다.
        if (currentPath.equals(sourceParentPath)) {
            // 같은 폴더인 경우
            btnPaste.setText(getString(R.string.paste_button_create_copy));
        } else {
            // 다른 폴더인 경우
            btnPaste.setText(getString(R.string.paste_button_default));
        }
    }

    // ★★★ [신설] 서버에 복사 요청을 보내는 메서드 ★★★
    private void requestCopyFile(String sourcePath, String destinationPath) {
        String serverIp = HomeActivity.serverIp;
        if (serverIp == null || serverIp.equals("Not connected")) {
            Toast.makeText(this, getString(R.string.server_not_connected), Toast.LENGTH_SHORT).show();
            return;
        }

        runOnUiThread(() -> {
            // 붙여넣기 레이아웃을 숨기거나,
            // updatePasteUIVisibility(false);

            // 또는 버튼을 비활성화하여 중복 클릭을 막습니다. (더 좋은 방법)
            btnPaste.setEnabled(false);
            btnCancelPaste.setEnabled(false);
            btnPaste.setText(getString(R.string.paste_button_pasting));
        });

        new Thread(() -> {
            boolean success = false;
            try {
                String urlStr = "http://" + serverIp + ":5005/file/explorer_copy";
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; utf-8");
                conn.setDoOutput(true);

                JSONObject jsonParam = new JSONObject();
                jsonParam.put("source_path", sourcePath);
                jsonParam.put("destination_path", destinationPath);

                try (java.io.OutputStream os = conn.getOutputStream()) {
                    byte[] input = jsonParam.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                if (conn.getResponseCode() == 200) {
                    success = true; // 성공 플래그 설정
                    runOnUiThread(() -> {
                        Toast.makeText(this, getString(R.string.paste_success), Toast.LENGTH_SHORT).show();
                        //clearClipboard(); // 작업 완료 후 클립보드 비우기
                        //loadPath(currentPath); // 목록 새로고침
                    });
                } else {
                    runOnUiThread(() -> Toast.makeText(this, getString(R.string.paste_failed), Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(this, getString(R.string.error_occurred) + e.getMessage(), Toast.LENGTH_SHORT).show());
            } finally {
                // 🔥 핵심: 작업이 성공하든 실패하든, 마지막에 항상 UI 상태를 다시 계산합니다.
                boolean finalSuccess = success;
                runOnUiThread(() -> {
                    if (finalSuccess) {
                        // 성공 시에는 클립보드를 비우고 목록을 새로고침합니다.
                        clearClipboard(); // clearClipboard()가 내부적으로 updatePasteUI()를 호출하여 UI를 숨깁니다.
                        loadPath(currentPath);
                    } else {
                        // 실패 시에는 버튼을 다시 활성화하고, 현재 상태에 맞는 텍스트로 복원합니다.
                        btnPaste.setEnabled(true);
                        btnCancelPaste.setEnabled(true);
                        // updatePasteUI()를 호출하여 "여기에 복사본 만들기" 또는 "여기에 붙여넣기"로 올바르게 복원합니다.
                        updatePasteUI();
                    }
                });
            }
        }).start();
    }

    // ★★★ [신설] 서버에 파일 다운로드를 요청하고 기기에 저장하는 메서드 ★★★
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (pendingDownload != null) {
            outState.putString("download_name", pendingDownload.getName());
            outState.putString("download_path", pendingDownload.getPath());
        }
    }

    private void requestDownloadFile(FileItem item) {
        if (HomeActivity.serverIp == null || HomeActivity.serverIp.equals("Not connected")) {
            Toast.makeText(this, getString(R.string.server_not_connected), Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // The system picker grants write access without storage permissions.
            pendingDownload = item;
            downloadDestination.launch(item.getName());
        } else {
            downloadFile(item, null);
        }
    }

    private void downloadFile(FileItem item, Uri destination) {
        String serverIp = HomeActivity.serverIp;
        if (serverIp == null || serverIp.equals("Not connected")) {
            Toast.makeText(this, getString(R.string.server_not_connected), Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, getString(R.string.download_start), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            HttpURLConnection conn = null;
            ContentResolver resolver = getContentResolver();
            Uri fileUri = destination;
            boolean completed = false;
            try {
                Uri downloadUri = Uri.parse("http://" + serverIp + ":5005/file/explorer_download")
                        .buildUpon().appendQueryParameter("path", item.getPath()).build();
                conn = (HttpURLConnection) new URL(downloadUri.toString()).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(30000);
                if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    throw new IOException("HTTP " + conn.getResponseCode());
                }
                if (fileUri == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, item.getName());
                    values.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                    fileUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                }
                if (fileUri == null) throw new IOException("Unable to create download file");
                try (InputStream input = conn.getInputStream();
                     OutputStream output = resolver.openOutputStream(fileUri, "w")) {
                    if (output == null) throw new IOException("Unable to open download file");
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = input.read(buffer)) != -1) {
                        output.write(buffer, 0, bytesRead);
                    }
                }
                if (destination == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    if (resolver.update(fileUri, values, null, null) == 0) {
                        throw new IOException("Unable to publish download");
                    }
                }
                completed = true;
                runOnUiThread(() -> Toast.makeText(this, getString(R.string.download_success), Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(this, getString(R.string.download_failed), Toast.LENGTH_SHORT).show());
            } finally {
                if (conn != null) conn.disconnect();
                if (!completed && fileUri != null) {
                    try {
                        if (destination == null) resolver.delete(fileUri, null, null);
                        else android.provider.DocumentsContract.deleteDocument(resolver, fileUri);
                    } catch (Exception cleanupError) {
                        cleanupError.printStackTrace();
                    }
                }
            }
        }).start();
    }

    private void showExitConfirmationDialog() {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.exit_explorer_title))
                .setMessage(getString(R.string.exit_explorer_message))
                .setPositiveButton(getString(R.string.action_exit), (dialog, which) -> {
                    // '종료' 버튼을 누르면 액티비티를 종료 (원래의 뒤로가기 동작 수행)
                    finish();
                })
                .setNegativeButton(getString(R.string.action_cancel), null)
                .show();
    }

    private boolean isImageViewable(String fileName) {
        String lowerCaseFileName = fileName.toLowerCase();
        return lowerCaseFileName.endsWith(".jpg") ||
                lowerCaseFileName.endsWith(".jpeg") ||
                lowerCaseFileName.endsWith(".png") ||
                lowerCaseFileName.endsWith(".gif") ||
                lowerCaseFileName.endsWith(".bmp");
    }

    // 텍스트로 볼 수 있는 파일 확장자인지 확인하는 헬퍼 메서드
    // 텍스트로 볼 수 있는 파일인지 확인하는 헬퍼 메서드
    // 텍스트로 볼 수 있는 파일인지 확인하는 헬퍼 메서드
    private boolean isTextViewable(String fileName) {String lowerCaseFileName = fileName.toLowerCase();

        // 1. 제외할 '포함 단어' 목록을 정의합니다.
        String[] excludedKeywords = {
                "_driving_"
                // 여기에 다른 제외할 단어를 추가할 수 있습니다.
        };

        // 2. 제외할 '확장자' 목록을 정의합니다.
        String[] excludedExtensions = {
                ".mp4", ".hevc", ".onnx",
                ".jpg", ".jpeg", ".png", ".gif", ".bmp", "zst", "o", "a" // 이미지 파일 확장자
        };

        // 3. 파일 이름에 제외할 '포함 단어'가 있는지 확인합니다.
        for (String keyword : excludedKeywords) {
            if (lowerCaseFileName.contains(keyword)) {
                // 제외 키워드가 포함되면, 텍스트로 볼 수 없으므로 false를 반환합니다.
                return false;
            }
        }

        // 4. 파일 이름이 제외할 '확장자'로 끝나는지 확인합니다.
        for (String ext : excludedExtensions) {
            if (lowerCaseFileName.endsWith(ext)) {
                // 제외 확장자로 끝나면, 텍스트로 볼 수 없으므로 false를 반환합니다.
                return false;
            }
        }

        // 5. 위 두 조건에 모두 걸러지지 않았다면, 텍스트로 볼 수 있는 파일로 간주합니다.
        return true;
    }



    private void loadPath(String path) {
        // 서버에서 파일 목록 가져오기
        new Thread(() -> {
            try {
                String serverIp = HomeActivity.serverIp;
                // ★ 서버 IP가 없는 경우를 먼저 체크
                if (serverIp == null || serverIp.equals("Not connected")) {
                    runOnUiThread(() -> {
                        Toast.makeText(FileExplorerActivity.this, "서버에 연결되지 않았습니다.", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                String encodedPath = java.net.URLEncoder.encode(path, "UTF-8");
                String urlStr = "http://" + serverIp + ":5005/files?path=" + encodedPath;

                String jsonResponse = getResponseFromUrl(urlStr);

                // ★ 서버 응답이 없는 경우 처리
                if (jsonResponse == null) {
                    runOnUiThread(() -> {
                        Toast.makeText(FileExplorerActivity.this, "서버에서 응답이 없습니다.", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                JSONObject responseJson = new JSONObject(jsonResponse);
                // ★ 서버에서 에러를 보낸 경우 처리
                if (responseJson.has("error")) {
                    String errorMessage = responseJson.getString("error");
                    runOnUiThread(() -> {
                        Toast.makeText(FileExplorerActivity.this, "오류: " + errorMessage, Toast.LENGTH_LONG).show();
                    });
                    return;
                }

                String fetchedPath = responseJson.getString("path");
                JSONArray itemsArray = responseJson.getJSONArray("items");

                // ★ 임시 리스트를 사용하여 백그라운드에서 데이터 처리
                List<FileItem> newFileList = new ArrayList<>();

                // 상위 폴더로 이동 ('..') 아이템 추가 (루트 디렉토리 제외)
                if (!fetchedPath.equals("/") && new File(fetchedPath).getParent() != null) {
                    newFileList.add(new FileItem("..", new File(fetchedPath).getParent(), true, -1, ""));
                }

                for (int i = 0; i < itemsArray.length(); i++) {
                    JSONObject itemJson = itemsArray.getJSONObject(i);
                    newFileList.add(new FileItem(
                            itemJson.getString("name"),
                            itemJson.getString("path"),
                            itemJson.getBoolean("is_dir"),
                            itemJson.getLong("size"),
                            itemJson.getString("modified")
                    ));
                }

                // ★ 모든 데이터 처리가 끝난 후, UI 스레드에서 한 번에 업데이트
                runOnUiThread(() -> {
                    currentPath = fetchedPath;
                    tvCurrentPath.setText(currentPath);
                    fileList.clear();
                    fileList.addAll(newFileList);
                    fileAdapter.notifyDataSetChanged();
                    rvFiles.scrollToPosition(0);
                    updatePasteUI();
                });

            } catch (Exception e) {
                e.printStackTrace();
                // ★ 예외 발생 시 사용자에게 Toast 메시지로 알림
                runOnUiThread(() -> {
                    Toast.makeText(FileExplorerActivity.this, "경로를 불러오는 중 오류 발생: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void openInBrowser(String filePath) {
        String serverIp = HomeActivity.serverIp;
        if (serverIp == null || serverIp.equals("Not connected")) {
            Toast.makeText(this, getString(R.string.server_not_connected), Toast.LENGTH_SHORT).show();
            return;
        }

        String encodedPath;
        try {
            encodedPath = java.net.URLEncoder.encode(filePath, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            e.printStackTrace();
            return;
        }

        // 서버의 /file/image API를 호출하는 URL
        String urlToOpen = "http://" + serverIp + ":5005/file/image?path=" + encodedPath;
        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(urlToOpen));
        startActivity(browserIntent);
    }

    private void showFileContent(FileItem fileItem) {
        //Toast.makeText(this, fileItem.getName() + " 파일을 읽는 중...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            try {
                String serverIp = HomeActivity.serverIp;
                String encodedPath = java.net.URLEncoder.encode(fileItem.getPath(), "UTF-8");
                String urlStr = "http://" + serverIp + ":5005/file/content?path=" + encodedPath;

                String fileContent = getResponseFromUrl(urlStr);

                runOnUiThread(() -> {
                    if (fileContent != null) {
                        showContentDialog(fileItem.getName(), fileContent);
                    } else {
                        Toast.makeText(FileExplorerActivity.this, "파일 내용을 읽을 수 없습니다.", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(FileExplorerActivity.this, "오류: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // 텍스트 내용을 스크롤 가능한 다이얼로그로 보여주는 메서드
    private void showContentDialog(String fileName, String content) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(fileName); // 다이얼로그 제목을 파일 이름으로 설정

        // 스크롤이 가능한 뷰 생성
        ScrollView scrollView = new ScrollView(this);
        TextView textView = new TextView(this);
        textView.setText(content);
        textView.setPadding(50, 40, 50, 40); // 넉넉한 여백
        textView.setTextIsSelectable(true); // 텍스트를 길게 눌러 복사할 수 있게 함

        scrollView.addView(textView);
        builder.setView(scrollView);

        builder.setPositiveButton(getString(R.string.dialog_btn_close), null);
        builder.show();
    }

    private String getResponseFromUrl(String urlString) throws java.io.IOException {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(5000); // 5초 연결 타임아웃
        conn.setReadTimeout(10000);   // 10초 읽기 타임아웃
        conn.setRequestMethod("GET");

        try {
            if (conn.getResponseCode() == 200) {
                // 성공적으로 응답을 받았을 때
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                    sb.append("\n");
                }
                reader.close();
                return sb.toString();
            } else {
                // 서버에서 200 OK가 아닌 다른 응답 코드를 받았을 때
                return null;
            }
        } finally {
            conn.disconnect();
        }
    }
}