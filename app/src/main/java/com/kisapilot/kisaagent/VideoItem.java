package com.kisapilot.kisaagent;

import java.util.ArrayList;
import java.util.List;

public class VideoItem {
    // 비디오 타입을 구분하기 위한 상수
    public static final int TYPE_DRIVE_LOG = 0;
    public static final int TYPE_SCREEN_REC = 1;

    // --- 필드(변수) 선언 ---
    private String displayTitle;     // 화면 표시용 제목 (날짜/시간)
    private String routeName;        // 그룹화 ID 또는 원본 파일명
    private int segmentCount;
    private String thumbnailUrl;
    private int type;
    private String path;
    private long size;
    private List<String> segmentPaths = new ArrayList<>();

    private boolean selected = false;

    // ★★★ 생성자에 'path' 인수를 추가합니다. ★★★
    public VideoItem(String routeName, String displayTitle, int segmentCount, String thumbnailUrl, int type, String path, Long size) {
        this.displayTitle = displayTitle;
        this.routeName = routeName;
        this.segmentCount = segmentCount;
        this.thumbnailUrl = thumbnailUrl;
        this.type = type;
        this.path = path;
        this.size = size;
    }

    // --- Getter 메서드들 ---
    public String getDisplayTitle() { return displayTitle; }
    public String getRouteName() { return routeName; }
    public int getSegmentCount() { return segmentCount; }
    public String getThumbnailUrl() { return thumbnailUrl; }
    public int getType() { return type; }
    public List<String> getSegmentPaths() { return segmentPaths; }

    // ★★★ path 필드에 대한 Getter 메서드를 추가합니다. ★★★
    public String getPath() { return path; }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    // --- Setter 메서드 ---
    public void addSegmentPath(String path) {
        this.segmentPaths.add(path);
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }
}
