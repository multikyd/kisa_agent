package com.kisapilot.kisaagent;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class AutoBookmarkPolicyTest {
    @Test public void firstRunSkipsHistoryButQueuesActiveBookmark() {
        Set<String> seen=new HashSet<>(), pending=new HashSet<>();
        Map<String,Boolean> items=new LinkedHashMap<>();items.put("abc--0",false);items.put("abc--1",true);
        AutoBookmarkPolicy.observe(seen,pending,items,false);
        assertEquals(Collections.singleton("abc--1"),pending);
    }
    @Test public void secondBookmarkQueuesWithoutReplacingFirst() {
        Set<String> seen=new HashSet<>(),pending=new HashSet<>();
        AutoBookmarkPolicy.observe(seen,pending,Collections.singletonMap("abc--1",true),true);
        AutoBookmarkPolicy.observe(seen,pending,Collections.singletonMap("abc--2",true),true);
        AutoBookmarkPolicy.observe(seen,pending,Collections.singletonMap("abc--1",false),true);
        assertEquals(2,pending.size());
    }
    @Test public void restoredQueueDoesNotLoseOrDuplicateJobs() {
        Set<String> seen=new HashSet<>(Arrays.asList("abc--1","abc--2"));
        Set<String> pending=new HashSet<>(Collections.singleton("abc--2"));
        AutoBookmarkPolicy.observe(seen,pending,Collections.singletonMap("abc--1",false),true);
        assertEquals(Collections.singleton("abc--2"),pending);
    }
    @Test public void unsafeIdsAndUnfinishedFilesAreExcluded() {
        Set<String> pending=new HashSet<>();
        AutoBookmarkPolicy.observe(new HashSet<>(),pending,Collections.singletonMap("../bad--1",true),true);
        assertTrue(pending.isEmpty());assertFalse(AutoBookmarkPolicy.canTransfer("abc--1/rlog.lock"));
    }
    @Test public void missingSourceExpiresOnlyAfterThreeSuccessfulLists() {
        Set<String> pending=new HashSet<>(Collections.singleton("abc--1"));
        Map<String,Integer> misses=new HashMap<>();
        assertTrue(AutoBookmarkPolicy.discardMissing(pending,Collections.emptySet(),misses).isEmpty());
        assertTrue(AutoBookmarkPolicy.discardMissing(pending,Collections.emptySet(),misses).isEmpty());
        assertTrue(pending.contains("abc--1"));
        assertEquals(Collections.singleton("abc--1"),AutoBookmarkPolicy.discardMissing(pending,Collections.emptySet(),misses));
        assertTrue(pending.isEmpty());assertTrue(misses.isEmpty());
    }
    @Test public void reappearingSourceResetsMissingCount() {
        Set<String> pending=new HashSet<>(Collections.singleton("abc--1"));
        Map<String,Integer> misses=new HashMap<>();
        AutoBookmarkPolicy.discardMissing(pending,Collections.emptySet(),misses);
        AutoBookmarkPolicy.discardMissing(pending,Collections.singleton("abc--1"),misses);
        assertTrue(misses.isEmpty());
        AutoBookmarkPolicy.discardMissing(pending,Collections.emptySet(),misses);
        AutoBookmarkPolicy.discardMissing(pending,Collections.emptySet(),misses);
        assertTrue(pending.contains("abc--1"));
    }
    @Test public void expiredSourceIsNotDownloadedAgainAndOtherJobsRemain() {
        Set<String> seen=new HashSet<>(Arrays.asList("abc--1","abc--2"));
        Set<String> pending=new HashSet<>(seen);
        Map<String,Integer> misses=new HashMap<>();
        for(int i=0;i<3;i++)AutoBookmarkPolicy.discardMissing(pending,Collections.singleton("abc--2"),misses);
        Map<String,Boolean> available=new HashMap<>();available.put("abc--1",false);available.put("abc--2",false);
        AutoBookmarkPolicy.observe(seen,pending,available,true);
        assertEquals(Collections.singleton("abc--2"),pending);
        assertTrue(seen.contains("abc--1"));
    }
    @Test public void newBookmarkStillQueuesAfterOldSourceExpires() {
        Set<String> seen=new HashSet<>(Collections.singleton("abc--1"));
        Set<String> pending=new HashSet<>(seen);Map<String,Integer> misses=new HashMap<>();
        for(int i=0;i<3;i++)AutoBookmarkPolicy.discardMissing(pending,Collections.emptySet(),misses);
        AutoBookmarkPolicy.observe(seen,pending,Collections.singletonMap("abc--2",false),true);
        assertEquals(Collections.singleton("abc--2"),pending);
    }
    @Test public void closedLogsAndAllVideoTransferWithoutWaitingForIgnitionOff() {
        for(String name: Arrays.asList("rlog.zst","qlog.zst","fcamera.hevc","ecamera.hevc","qcamera.ts"))
            assertTrue(AutoBookmarkPolicy.canTransfer("abc--1/"+name));
        assertFalse(AutoBookmarkPolicy.canTransfer(null));
        assertFalse(AutoBookmarkPolicy.canTransfer("abc--1/fcamera.hevc.lock"));
    }
}
