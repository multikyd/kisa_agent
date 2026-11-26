package com.kisapilot.kisaagent;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.io.IOException;

public class StandardPidScanTest {
    @Test public void readsUnknownPidsFromEverySupportPageThroughE0() throws Exception {
        List<String> calls = new ArrayList<>();
        String text = StandardPidScan.read(command -> {
            calls.add(command);
            int pid = command.startsWith("01") ? Integer.parseInt(command.substring(2), 16) : -1;
            if (pid >= 0 && pid % 32 == 0) {
                return String.format("7E8 06 41 %02X 80 00 00 %02X", pid, pid < 224 ? 1 : 0);
            }
            if (pid >= 0) return String.format("7E8 03 41 %02X AB", pid);
            return "NO DATA";
        });
        for (int pid = 0; pid <= 224; pid += 32) {
            assertTrue(calls.contains(StandardPidScan.command(pid)));
            assertTrue(calls.contains(StandardPidScan.command(pid + 1)));
            assertEquals(1, Collections.frequency(calls, StandardPidScan.command(pid)));
        }
        assertEquals(17, calls.size());
        assertTrue(text.contains("01E1 (ECU 7E8)  해석 전 데이터 · RAW AB"));
    }
    @Test public void unionsEcusAndRetainsUndecodedValues() throws Exception {
        List<String> calls = new ArrayList<>();
        String text = StandardPidScan.read(command -> {
            calls.add(command);
            if (command.equals("0100")) return "7E8 06 41 00 00 08 00 00\r7E9 06 41 00 00 04 00 00";
            if (command.equals("010D")) return "7E8 03 41 0D 32\r7E9 03 41 0D 33";
            if (command.equals("010E")) return "7E9 03 41 0E 80";
            return "NO DATA";
        });
        assertEquals(Arrays.asList("0100", "010D", "010E", "0902"), calls);
        assertTrue(text.contains("차량 속도  50 km/h · RAW 32"));
        assertTrue(text.contains("차량 속도  51 km/h · RAW 33"));
        assertTrue(text.contains("010E (ECU 7E9)  해석 전 데이터 · RAW 80"));
    }
    @Test public void noDataAndIncompleteRepliesAreExplicit() throws Exception {
        String text = StandardPidScan.read(command -> command.equals("0100")
                ? "7E8 10 0A 41 00 80 00 00 00" : "NO DATA");
        assertTrue(text.contains("0100  정상 응답 없음"));
        assertTrue(text.contains("응답 일부 누락·해석 실패"));
        assertTrue(text.contains("지원 PID 조회 수  0개"));
    }
    @Test public void supportedUnknownReadCommandsCannotEnableWrites() {
        assertEquals("01E1", Elm327Protocol.command("01E1"));
        assertEquals("01FF", Elm327Protocol.command("01FF"));
        for (String command : new String[]{"04", "020D", "22010D", "2E010100", "01FF04"}) {
            try { Elm327Protocol.command(command); fail(command); }
            catch (IllegalArgumentException expected) {}
        }
    }
    @Test public void transportFailureStopsScan() throws Exception {
        try {
            StandardPidScan.read(command -> { throw new IOException("timeout"); });
            fail();
        } catch (IOException expected) { assertEquals("timeout", expected.getMessage()); }
    }
}
