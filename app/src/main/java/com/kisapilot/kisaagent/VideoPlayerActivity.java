package com.kisapilot.kisaagent;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import java.io.File;
import java.util.ArrayList;

public class VideoPlayerActivity extends AppCompatActivity {

    private PlayerView playerView;
    private ExoPlayer player;

    private ArrayList<String> videoPlaylist;
    private int currentIndex;

    private String currentVideoUrl;
    private String currentFileName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        setContentView(R.layout.activity_video_player);
        playerView = findViewById(R.id.player_view);
        ImageButton btnDownload = findViewById(R.id.btn_download);
        ImageButton btnBack = findViewById(R.id.btn_back);

        hideSystemUI();
        initializePlayer(); // ExoPlayer 초기화 함수 호출

        Intent intent = getIntent();
        ArrayList<String> segmentList = intent.getStringArrayListExtra("segment_list");
        String videoFileName = intent.getStringExtra("video_filename");

        if (segmentList != null && videoFileName != null) {
            videoPlaylist = new ArrayList<>();
            for (String segmentPath : segmentList) {
                videoPlaylist.add(segmentPath + File.separator + videoFileName);
            }
        } else {
            videoPlaylist = intent.getStringArrayListExtra("video_playlist");
        }
        currentIndex = intent.getIntExtra("current_index", 0);

        if (videoPlaylist != null && !videoPlaylist.isEmpty()) {
            playVideoAtIndex(currentIndex);
            btnDownload.setOnClickListener(v -> downloadVideo());
            btnBack.setOnClickListener(v -> finish());
        }
    }

    private void initializePlayer() {
        // 가장 간단한 기본 ExoPlayer 빌더를 사용합니다.
        player = new ExoPlayer.Builder(this).build();

        playerView.setPlayer(player);
        player.setRepeatMode(Player.REPEAT_MODE_OFF); // 연속 재생을 위해 개별 반복은 끔

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_ENDED) { // 재생이 끝나면
                    currentIndex++;
                    if (currentIndex >= videoPlaylist.size()) {
                        currentIndex = 0; // 마지막이면 처음으로
                    }
                    playVideoAtIndex(currentIndex); // 다음 영상 재생
                }
            }
        });
    }

    // ★ 재생 로직은 변경할 필요 없음 (기존과 동일)
    // VideoPlayerActivity.java
    // VideoPlayerActivity.java
    private void playVideoAtIndex(int index) {
        if (videoPlaylist == null || index < 0 || index >= videoPlaylist.size()) {
            finish();return;
        }

        String videoPath = videoPlaylist.get(index);
        this.currentFileName = videoPath.substring(videoPath.lastIndexOf('/') + 1);

        String serverIp = HomeActivity.serverIp;

        // ★★★ [핵심 수정] 서버의 API 경로인 '/media/'를 사용하도록 URL 생성 로직을 수정합니다. ★★★
        if (videoPath.startsWith("/data/media/0/realdata/")) {
            // HEVC 파일의 경우
            String relativePath = videoPath.replace("/data/media/0/realdata/", "");
            // 올바른 경로: /media/realdata/파일명
            this.currentVideoUrl = "http://" + serverIp + ":5005/media/realdata/" + relativePath;

        } else if (videoPath.startsWith("/data/media/0/videos/")) {
            // MP4 파일의 경우
            String relativePath = videoPath.replace("/data/media/0/videos/", "");
            // 올바른 경로: /media/videos/파일명
            this.currentVideoUrl = "http://" + serverIp + ":5005/media/videos/" + relativePath;

        } else {
            // 예외 상황 처리
            Toast.makeText(this, getString(R.string.unknown_video_path), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // ExoPlayer를 사용하는 경우 (이하 로직은 동일)
        MediaItem mediaItem = MediaItem.fromUri(this.currentVideoUrl);
        player.setMediaItem(mediaItem);
        player.prepare();
        player.play();
    }



    // ★ 생명주기에 맞춰 플레이어 제어 (기존과 동일)
    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) {
            player.pause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (player != null) {
            player.play();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private void downloadVideo() {
        if (currentVideoUrl == null || currentFileName == null) {
            Toast.makeText(this, getString(R.string.video_download_unavailable), Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            DownloadManager downloadManager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            Uri uri = Uri.parse(currentVideoUrl);
            DownloadManager.Request request = new DownloadManager.Request(uri);
            request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI | DownloadManager.Request.NETWORK_MOBILE);
            request.setTitle(currentFileName);
            request.setDescription(getString(R.string.video_download_description));
            request.allowScanningByMediaScanner();
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, currentFileName);
            downloadManager.enqueue(request);
            Toast.makeText(this, getString(R.string.video_download_start, currentFileName), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.video_download_error, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }
    private void hideSystemUI() {
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowCompat.setDecorFitsSystemWindows(window, false);
            WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, window.getDecorView());
            controller.hide(WindowInsetsCompat.Type.systemBars());
            controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        } else {
            View decorView = window.getDecorView();
            int uiOptions = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            decorView.setSystemUiVisibility(uiOptions);
        }
    }
}
