package com.kisapilot.kisaagent;
import org.junit.Test;
import static org.junit.Assert.*;
public class DriveLogFilesTest {
    @Test public void archivePathsStayInsideRealdata() {
        assertEquals("route--0/rlog.zst", DriveLogFiles.relative("/data/media/0/realdata/route--0/rlog.zst"));
        assertNull(DriveLogFiles.relative("/data/media/0/realdata/../secret"));
        assertNull(DriveLogFiles.relative("/data/media/0/realdata/route/../../secret"));
        assertNull(DriveLogFiles.relative("/data/media/0/realdata-other/file"));
        assertNull(DriveLogFiles.relative("/data/media/0/realdata/route\\secret"));
    }
    @Test public void videoOptionSeparatesCompressedLogsFromAllCameras() {
        assertFalse(DriveLogFiles.isVideo("rlog.zst"));
        assertFalse(DriveLogFiles.isVideo("qlog.bz2"));
        assertTrue(DriveLogFiles.isVideo("fcamera.hevc"));
        assertTrue(DriveLogFiles.isVideo("dcamera.HEVC"));
        assertTrue(DriveLogFiles.isVideo("qcamera.ts"));
        assertTrue(DriveLogFiles.isVideo("ecamera.mp4"));
    }
}
