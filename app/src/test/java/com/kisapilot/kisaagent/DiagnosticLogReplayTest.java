package com.kisapilot.kisaagent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Real vehicle replies with VIN and other identifying responses removed. */
@RunWith(Parameterized.class)
public class DiagnosticLogReplayTest {
    @Parameterized.Parameters(name = "{0}") public static Collection<Object[]> cases() {
        return Arrays.asList(new Object[][] {
            {"1795650423059", "755.7 V", "69.5 %", "48.656 kWh", 0, null, null, "0.302 kW", "11 °C"},
            {"1795683646988", "749.1 V", "65 %", "45.438 kWh", 192, "3.88 V", "3.9 V", "0.375 kW", "14 °C"},
            {"1795684849906", "746.9 V", "64.5 %", "44.958 kWh", 192, "3.88 V", "3.88 V", "0.448 kW", "13 °C"},
            {"1795684864977", "746.9 V", "64.5 %", "44.958 kWh", 192, "3.88 V", "3.88 V", "0.448 kW", "13 °C"},
            {"1795735426521", "741.8 V", "62 %", "43.19 kWh", 192, "3.86 V", "3.86 V", "0.297 kW", "14 °C"},
            {"1795735444472", "741.8 V", "62 %", "43.19 kWh", 192, "3.86 V", "3.86 V", "0.297 kW", "14 °C"},
            {"1795735456210", "741.8 V", "62 %", "43.19 kWh", 192, "3.86 V", "3.86 V", "0.297 kW", "14 °C"},
            {"1795735512080", "741.8 V", "62 %", "43.19 kWh", 192, "3.86 V", "3.86 V", "0.297 kW", "14 °C"}
        });
    }
    private final String id, voltage, soc, energy, min, max, power, inlet;
    private final int cellCount;
    public DiagnosticLogReplayTest(String id, String voltage, String soc, String energy,
            int cellCount, String min, String max, String power, String inlet) {
        this.id=id; this.voltage=voltage; this.soc=soc; this.energy=energy;
        this.cellCount=cellCount; this.min=min; this.max=max; this.power=power; this.inlet=inlet;
    }
    @Test public void replaysRealBatteryCellsPidsAndTroubleCodes() throws Exception {
        String raw;
        try (InputStream stream = getClass().getResourceAsStream("/diagnostics/"+id+".txt")) {
            assertNotNull(stream);
            raw = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher match = Pattern.compile("(?m)^\\[([A-Z0-9]+)\\][ \\t]*\\r?$").matcher(raw);
        List<String> commands = new ArrayList<>();
        List<Integer> starts = new ArrayList<>(), ends = new ArrayList<>();
        while (match.find()) { commands.add(match.group(1)); starts.add(match.end()); ends.add(match.start()); }
        Map<String,String> last = new LinkedHashMap<>();
        ObdVehicleData.Battery battery = new ObdVehicleData.Battery();
        for (int i=0; i<commands.size(); i++) {
            String command=commands.get(i);
            String response=raw.substring(starts.get(i), i+1<commands.size()?ends.get(i+1):raw.length());
            last.put(command,response);
            assertFalse(Elm327Protocol.readPayloads(response, command.startsWith("22")?0x62:0x41, (ecu,payload)->{}));
            if (command.startsWith("22")) battery.add(command,response);
            if (Arrays.asList("03","07","0A").contains(command)) {
                Elm327Protocol.Result dtcs=Elm327Protocol.dtcs(response,Integer.parseInt(command,16));
                assertTrue(dtcs.responding); assertFalse(dtcs.incomplete);
                assertEquals(2,dtcs.codes.size());
                for (List<String> codes:dtcs.codes.values()) assertTrue(codes.isEmpty());
            }
        }
        assertEquals(voltage,battery.pack.get("배터리 팩 전압"));
        assertEquals(soc,battery.pack.get("계기판 충전율"));
        assertEquals(energy,battery.pack.get("배터리 잔여 에너지"));
        assertEquals("89 %",battery.pack.get("배터리 상태 (SOH)"));
        assertEquals(power,battery.pack.get("배터리 팩 전력 (전압×전류)"));
        assertEquals(inlet,battery.temperatures.get("배터리 유입 온도"));
        assertEquals("충전 중 아님",battery.status.get("BMS 충전 상태"));
        assertEquals(cellCount,battery.cells.size());
        if (cellCount>0) {
            assertEquals(min,battery.cellSummary().get("수신 셀 최저 전압"));
            assertEquals(max,battery.cellSummary().get("수신 셀 최고 전압"));
            assertEquals(min.equals(max)?"0 mV":"20 mV",battery.cellSummary().get("수신 셀 전압 차이"));
            for (int number=1; number<=192; number++) assertNotNull(battery.cells.get(number));
            Set<Integer> supported=new TreeSet<>();
            ObdVehicleData.supportedPids(last.get("0100"),0,supported);
            ObdVehicleData.supportedPids(last.get("0120"),32,supported);
            assertEquals(new TreeSet<>(Arrays.asList(1,32,33,48,49)),supported);
            String decoded=StandardPidScan.read(command -> last.getOrDefault(command,"NO DATA"));
            assertTrue(decoded.contains("고장 표시등 점등 중 주행거리  0 km"));
            assertTrue(decoded.contains("고장 코드 삭제 후 웜업 횟수  0 회"));
            assertTrue(decoded.contains("고장 코드 삭제 후 주행거리  0 km"));
            assertTrue(decoded.contains("지원 PID 조회 수  4개"));
            assertFalse(decoded.contains("해석 전 데이터"));
        } else assertTrue(battery.cellSummary().isEmpty());
    }
}
