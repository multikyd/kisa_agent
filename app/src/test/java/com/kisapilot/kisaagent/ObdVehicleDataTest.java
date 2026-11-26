package com.kisapilot.kisaagent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.junit.Test;

public class ObdVehicleDataTest {
    @Test public void decodesSignedValuesFromCompleteBatteryReplies() {
        byte[] data = new byte[59];
        data[4] = (byte) 137;
        data[10] = (byte) 0xFF;
        data[11] = (byte) 0x85;
        data[12] = 0x1D;
        data[13] = (byte) 0x85;
        data[15] = -3;
        data[23] = (byte) 197;
        data[57] = 0x0B;
        data[58] = (byte) 0xB8;
        ObdVehicleData.Battery battery = new ObdVehicleData.Battery();
        battery.add(ObdVehicleData.BATTERY_MAIN, batteryFrames(1, data));

        assertEquals("68.5 %", battery.pack.get("BMS 충전율"));
        assertEquals("-12.3 A", battery.pack.get("배터리 팩 전류"));
        assertEquals("755.7 V", battery.pack.get("배터리 팩 전압"));
        assertEquals("-3 °C", battery.temperatures.get("최저 온도"));
        assertEquals("3.94 V", battery.pack.get("최고 셀 전압"));
        assertEquals("3000 kΩ", battery.pack.get("고전압 절연 저항"));
    }

    @Test public void rejectsWrongDidIncompleteResponseAndPadding() {
        ObdVehicleData.Battery battery = new ObdVehicleData.Battery();
        byte[] data = new byte[36];
        data[4] = (byte) 195;
        data[6] = (byte) 255;
        data[7] = (byte) 194;
        String reply = batteryFrames(0x0C, data);
        battery.add("220102", reply);
        battery.add("22010C", reply.replace("7EC 22", "7EC 23"));
        assertTrue(battery.cells.isEmpty());

        battery.add("22010C", reply);
        assertEquals(2, battery.cells.size());
        assertEquals("3.9 V", battery.cells.get(161));
        assertNull(battery.cells.get(162));
        assertNull(battery.cells.get(163));
        assertEquals("3.88 V", battery.cells.get(164));
    }

    @Test public void discoversStandardPidsAndRequiresExactDataLength() {
        Set<Integer> pids = new HashSet<>();
        ObdVehicleData.supportedPids("7E8 06 41 00 80 08 00 01\r7E9 06 41 00 08 00 00 00", 0, pids);
        assertEquals(new HashSet<>(Arrays.asList(1, 5, 13, 32)), pids);
        byte[] speed = ObdVehicleData.replies("7E8 03 41 0D 00 00 00 00 00", 0x41, 0x0D).get("7E8");
        assertEquals("0 km/h", ObdVehicleData.STANDARD.get(0x0D).value(speed));
        assertNull(ObdVehicleData.STANDARD.get(0x0D).value(new byte[]{0, 0}));
        assertFalse(ObdVehicleData.allows("2E0101"));
        assertFalse(ObdVehicleData.allows("22010D"));
    }

    private static String batteryFrames(int did, byte[] data) {
        byte[] payload = new byte[data.length + 3];
        payload[0] = 0x62;
        payload[1] = 1;
        payload[2] = (byte) did;
        System.arraycopy(data, 0, payload, 3, data.length);
        StringBuilder response = new StringBuilder(String.format(Locale.ROOT, "7EC 10 %02X", payload.length));
        for (int offset = 0; offset < payload.length; offset++) {
            if (offset >= 6 && (offset - 6) % 7 == 0) {
                response.append(String.format(Locale.ROOT, "\r7EC 2%X", (offset - 6) / 7 + 1));
            }
            response.append(String.format(Locale.ROOT, " %02X", payload[offset] & 255));
        }
        return response.toString();
    }
}
