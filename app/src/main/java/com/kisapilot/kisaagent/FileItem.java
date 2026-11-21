package com.kisapilot.kisaagent;

public class FileItem {
    private String name;
    private String path;
    private boolean isDir;    private long size;
    private String modified;

    public FileItem(String name, String path, boolean isDir, long size, String modified) {
        this.name = name;
        this.path = path;
        this.isDir = isDir;
        this.size = size;
        this.modified = modified;
    }

    // --- Getter methods ---
    public String getName() {
        return name;
    }

    public String getPath() {
        return path;
    }

    public boolean isDir() {
        return isDir;
    }

    public long getSize() {
        return size;
    }

    public String getModified() {
        return modified;
    }
}
