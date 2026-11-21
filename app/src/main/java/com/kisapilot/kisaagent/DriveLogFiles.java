package com.kisapilot.kisaagent;
import java.util.Locale;
final class DriveLogFiles {
    static String relative(String path) {
        String root = "/data/media/0/realdata/";
        if (!path.startsWith(root)) return null;
        String name = path.substring(root.length());
        if (name.isEmpty() || name.contains("\\")) return null;
        for (String part : name.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals("..")) return null;
        return name;
    }
    static boolean isVideo(String name) {
        return name.toLowerCase(Locale.ROOT).matches(".*\\.(hevc|h265|h264|mp4|mkv|webm|avi|mov|ts|mjpeg)$");
    }
    static String segment(String path) {
        String name = relative(path);
        if (name == null || !name.contains("/")) return null;
        return name.substring(0, name.indexOf('/'));
    }
    static boolean isLock(String path) { return path.toLowerCase(Locale.ROOT).endsWith(".lock"); }
    static java.util.Set<String> lockedSegments(Iterable<String> paths) {
        java.util.Set<String> result = new java.util.HashSet<>();
        for (String path : paths) if (isLock(path) && segment(path) != null) result.add(segment(path));
        return result;
    }
    static String exclusion(String path, java.util.Set<String> existing, java.util.Set<String> locked) {
        return exclusion(path, existing, locked, false);
    }
    static String exclusion(String path, java.util.Set<String> existing, java.util.Set<String> locked, boolean complete) {
        if (isLock(path)) return "기록용 임시 .lock 파일";
        if (complete) return null;
        if (!existing.contains(path)) return "목록 갱신 시 파일 없음";
        if (locked.contains(segment(path))) return "기록 중인 세그먼트";
        return null;
    }
}
