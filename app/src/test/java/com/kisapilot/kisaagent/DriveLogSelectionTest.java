package com.kisapilot.kisaagent;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
public class DriveLogSelectionTest {
    private final String root = "/data/media/0/realdata/";
    @Test public void anyLockExcludesOnlyItsRecordingSegment() {
        Set<String> existing = new HashSet<>(Arrays.asList(root+"route--0/rlog.zst", root+"route--1/rlog.zst", root+"route--1/fcamera.hevc.lock"));
        Set<String> locked = DriveLogFiles.lockedSegments(existing);
        assertNull(DriveLogFiles.exclusion(root+"route--0/rlog.zst", existing, locked));
        assertEquals("기록 중인 세그먼트", DriveLogFiles.exclusion(root+"route--1/rlog.zst", existing, locked));
        assertEquals("기록용 임시 .lock 파일", DriveLogFiles.exclusion(root+"route--1/fcamera.hevc.lock", existing, locked));
    }
    @Test public void missingUnfinishedFileIsOmittedButCompletedCopySurvives() {
        Set<String> empty = Collections.emptySet();
        assertEquals("목록 갱신 시 파일 없음", DriveLogFiles.exclusion(root+"old--0/rlog.zst", empty, empty, false));
        assertNull(DriveLogFiles.exclusion(root+"old--0/rlog.zst", empty, empty, true));
        assertNotNull(DriveLogFiles.exclusion(root+"old--0/rlog.lock", empty, empty, true));
    }
    @Test public void completedFilesInActiveSegmentRemainUsable() {
        Set<String> existing = new HashSet<>(Arrays.asList(root+"route--1/rlog.zst", root+"route--1/rlog.lock"));
        assertNull(DriveLogFiles.exclusion(root+"route--1/rlog.zst", existing, DriveLogFiles.lockedSegments(existing), true));
    }
    @Test public void lockRemovalAndNewSegmentDoNotChangeOriginalSelection() {
        String original = root+"route--0/rlog.zst";
        Set<String> fresh = new HashSet<>(Arrays.asList(original, root+"route--1/rlog.zst", root+"route--1/rlog.lock"));
        assertNull(DriveLogFiles.exclusion(original, fresh, DriveLogFiles.lockedSegments(fresh)));
        assertNotNull(DriveLogFiles.exclusion(root+"route--0/rlog.lock", fresh, DriveLogFiles.lockedSegments(fresh)));
    }
}
