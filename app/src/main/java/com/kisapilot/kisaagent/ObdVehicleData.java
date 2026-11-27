package com.kisapilot.kisaagent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Translates positive vehicle replies into displayable diagnostic fields. */
final class ObdVehicleData {
    static final String BATTERY_MAIN = "220101";
    static final String BATTERY_EXTRA = "220105";
    static final String VIN = "0902";
    static final int MONITOR_STATUS = 0x01;
    static final Map<String, Integer> CELL_BLOCKS;
    static final Map<Integer, Signal> STANDARD;

    static {
        Map<String, Integer> cells = new LinkedHashMap<>();
        cells.put("220102", 1);
        cells.put("220103", 33);
        cells.put("220104", 65);
        cells.put("22010A", 97);
        cells.put("22010B", 129);
        cells.put("22010C", 161);
        CELL_BLOCKS = Collections.unmodifiableMap(cells);

        Map<Integer, Signal> pids = new LinkedHashMap<>();
        pids.put(0x04, new Signal("계산 엔진 부하", "%", 1, 100.0 / 255));
        pids.put(0x05, new Signal("냉각수 온도", "°C", 1, 1, -40));
        pids.put(0x0C, new Signal("엔진 회전수", "rpm", 2, 0.25));
        pids.put(0x0D, new Signal("차량 속도", "km/h", 1, 1));
        pids.put(0x0F, new Signal("흡기 온도", "°C", 1, 1, -40));
        pids.put(0x11, new Signal("스로틀 위치", "%", 1, 100.0 / 255));
        pids.put(0x1F, new Signal("시동 후 경과 시간", "s", 2, 1));
        // Definitions: https://github.com/OBDb/SAEJ1979/blob/main/signalsets/v3/default.json
        pids.put(0x21, new Signal("고장 표시등 점등 중 주행거리", "km", 2, 1));
        pids.put(0x30, new Signal("고장 코드 삭제 후 웜업 횟수", "회", 1, 1));
        pids.put(0x31, new Signal("고장 코드 삭제 후 주행거리", "km", 2, 1));
        pids.put(0x2F, new Signal("연료 잔량", "%", 1, 100.0 / 255));
        pids.put(0x42, new Signal("제어기 전압", "V", 2, 0.001));
        pids.put(0x46, new Signal("외기 온도", "°C", 1, 1, -40));
        pids.put(0x5B, new Signal("구동 배터리 잔량", "%", 1, 100.0 / 255));
        pids.put(0x5C, new Signal("엔진 오일 온도", "°C", 1, 1, -40));
        pids.put(0xA6, new Signal("누적 주행거리", "km", 4, 0.1));
        STANDARD = Collections.unmodifiableMap(pids);
    }

    static boolean allows(String command) {
        if (BATTERY_MAIN.equals(command) || BATTERY_EXTRA.equals(command)
                || VIN.equals(command) || CELL_BLOCKS.containsKey(command)) return true;
        if (!command.matches("01[0-9A-F]{2}")) return false;
        // Service 01 only reads current data. Unknown supported PIDs are retained as RAW.
        return true;
    }

    static Map<String, byte[]> replies(String raw, int service, int... identifier) {
        Map<String, byte[]> result = new LinkedHashMap<>();
        Elm327Protocol.readPayloads(raw, service, (ecu, payload) -> {
            if (payload.length <= identifier.length + 1 || unsigned(payload, 0, 1) != service) return;
            for (int i = 0; i < identifier.length; i++) {
                if (unsigned(payload, i + 1, 1) != identifier[i]) return;
            }
            result.put(ecu, Arrays.copyOfRange(payload, identifier.length + 1, payload.length));
        });
        return result;
    }

    static void supportedPids(String raw, int base, Set<Integer> supported) {
        for (byte[] data : replies(raw, 0x41, base).values()) {
            if (data.length != 4) continue;
            long bitmap = unsigned(data, 0, 4);
            for (int bit = 0; bit < 32; bit++) {
                if ((bitmap & (1L << (31 - bit))) != 0) supported.add(base + bit + 1);
            }
        }
    }

    static String vin(byte[] data) {
        if (data.length != 18 || data[0] != 1) return null;
        String vin = new String(data, 1, 17, StandardCharsets.US_ASCII);
        return vin.matches("[A-HJ-NPR-Z0-9]{17}") ? vin : null;
    }

    static final class Signal {
        final String label;
        private final String unit;
        private final int size;
        private final double scale, offset;

        Signal(String label, String unit, int size, double scale) {
            this(label, unit, size, scale, 0);
        }

        Signal(String label, String unit, int size, double scale, double offset) {
            this.label = label;
            this.unit = unit;
            this.size = size;
            this.scale = scale;
            this.offset = offset;
        }

        String value(byte[] data) {
            return data.length == size ? valueWithUnit(unsigned(data, 0, size) * scale + offset, unit) : null;
        }
    }

    static final class Battery {
        final Map<String, String> pack = new LinkedHashMap<>();
        final Map<String, String> temperatures = new LinkedHashMap<>();
        final Map<String, String> totals = new LinkedHashMap<>();
        final Map<Integer, String> cells = new TreeMap<>();
        private final Map<Integer, Integer> cellSteps = new TreeMap<>();
        final Map<String, String> status = new LinkedHashMap<>();

        // BMS DID offsets start after the positive service and two DID bytes.
        // Reference: https://github.com/OBDb/Hyundai-IONIQ-5/blob/main/signalsets/v3/default.json
        void add(String command, String raw) {
            if (!BATTERY_MAIN.equals(command) && !BATTERY_EXTRA.equals(command)
                    && !CELL_BLOCKS.containsKey(command)) return;
            int did = Integer.parseInt(command.substring(4), 16);
            byte[] data = replies(raw, 0x62, 1, did).get("7EC");
            if (data == null) return;
            if (BATTERY_MAIN.equals(command)) {
                decodeMain(data);
            } else if (BATTERY_EXTRA.equals(command)) {
                decodeExtra(data);
            } else {
                decodeCells(CELL_BLOCKS.get(command), data);
            }
        }

        private void decodeMain(byte[] data) {
            measurement(pack, "BMS 충전율", "%", data, 4, 1, 0.5, 0, 100);
            if (data.length >= 12) put(pack, "배터리 팩 전류", (short) unsigned(data, 10, 2) / 10.0, "A");
            measurement(pack, "배터리 팩 전압", "V", data, 12, 2, 0.1, 0, 1000);
            if (data.length >= 14 && unsigned(data, 12, 2) != 0xFFFF) {
                put(pack, "배터리 팩 전력 (전압×전류)",
                        unsigned(data, 12, 2) * 0.1 * (short) unsigned(data, 10, 2) / 10000.0, "kW");
            }
            if (data.length > 9 && (data[9] & 255) != 255) {
                int flags = data[9] & 255;
                status.put("BMS 충전 상태", (flags & 0x80) != 0 ? "충전 중" : "충전 중 아님");
                status.put("급속 충전 포트 신호", (flags & 0x40) != 0 ? "켜짐" : "꺼짐");
                status.put("완속 충전 포트 신호", (flags & 0x20) != 0 ? "켜짐" : "꺼짐");
                status.put("BMS 메인 릴레이 신호", (flags & 1) != 0 ? "켜짐" : "꺼짐");
            }
            measurement(status, "배터리 팬 상태 코드", "", data, 27, 1, 1, 0, 9);
            measurement(status, "배터리 팬 피드백", "Hz", data, 28, 1, 1, 0, 120);
            if (data.length > 22 && (data[22] & 255) != 255) {
                put(temperatures, "배터리 유입 온도", (data[22] & 255) - 40, "°C");
            }
            temperature("최고 온도", data, 14);
            temperature("최저 온도", data, 15);
            for (int i = 0; i < 5; i++) temperature("온도 센서 " + (i + 1), data, 16 + i);
            measurement(pack, "최고 셀 전압", "V", data, 23, 1, 0.02, 0.02, 5.1);
            measurement(pack, "최저 셀 전압", "V", data, 25, 1, 0.02, 0.02, 5.1);
            measurement(pack, "최고 전압 셀 번호", "", data, 24, 1, 1, 1, 254);
            measurement(pack, "최저 전압 셀 번호", "", data, 26, 1, 1, 1, 254);
            measurement(pack, "12 V 배터리 전압", "V", data, 29, 1, 0.1, 0, 25);
            measurement(pack, "고전압 절연 저항", "kΩ", data, 57, 2, 1, 0, 3000);
            measurement(totals, "누적 충전량", "Ah", data, 30, 4, 0.1, 0, Double.MAX_VALUE);
            measurement(totals, "누적 방전량", "Ah", data, 34, 4, 0.1, 0, Double.MAX_VALUE);
            measurement(totals, "누적 충전 에너지", "kWh", data, 38, 4, 0.1, 0, Double.MAX_VALUE);
            measurement(totals, "누적 방전 에너지", "kWh", data, 42, 4, 0.1, 0, Double.MAX_VALUE);
            measurement(totals, "누적 작동 시간", "h", data, 46, 4, 1.0 / 3600, 0, Double.MAX_VALUE);
        }

        private void decodeExtra(byte[] data) {
            measurement(pack, "배터리 상태 (SOH)", "%", data, 25, 2, 0.1, 0, 100);
            measurement(pack, "배터리 잔여 에너지", "kWh", data, 28, 2, 0.002, 0, 131.07);
            measurement(pack, "계기판 충전율", "%", data, 31, 1, 0.5, 0, 100);
            for (int i = 0; i < 7; i++) temperature("온도 센서 " + (i + 6), data, 9 + i);
            for (int i = 0; i < 4; i++) temperature("온도 센서 " + (i + 13), data, 39 + i);
            temperature("배터리 히터 온도", data, 23);
        }

        private void decodeCells(int firstCell, byte[] data) {
            // Each DID contains a four-byte bitmap followed by exactly 32 cell values.
            // Reject a short block rather than presenting a partial block as complete.
            if (data.length != 36) return;
            for (int i = 0; i < 32; i++) {
                int number = firstCell + i;
                cells.remove(number);
                cellSteps.remove(number);
                int raw = data[i + 4] & 255;
                if (raw != 0 && raw != 255) {
                    cells.put(number, valueWithUnit(raw / 50.0, "V"));
                    cellSteps.put(number, raw);
                }
            }
        }

        Map<String, String> cellSummary() {
            Map<String, String> summary = new LinkedHashMap<>();
            if (cellSteps.isEmpty()) return summary;
            int min = Collections.min(cellSteps.values()), max = Collections.max(cellSteps.values());
            int sum = 0;
            for (int value : cellSteps.values()) sum += value;
            summary.put("수신 셀 수", cellSteps.size() + "개");
            put(summary, "수신 셀 최저 전압", min / 50.0, "V");
            put(summary, "수신 셀 최고 전압", max / 50.0, "V");
            put(summary, "수신 셀 평균 전압", sum / (50.0 * cellSteps.size()), "V");
            put(summary, "수신 셀 전압 차이", (max - min) * 20, "mV");
            summary.put("셀 전압 보고 단위", "20 mV (표시 차이 0도 실제 전압이 완전히 같다는 뜻은 아님)");
            return summary;
        }

        private void temperature(String label, byte[] data, int index) {
            if (index < data.length && data[index] >= -40 && data[index] <= 100) {
                put(temperatures, label, data[index], "°C");
            }
        }
    }

    private static void measurement(Map<String, String> values, String label, String unit, byte[] data,
                                    int index, int size, double scale, double min, double max) {
        if (index + size > data.length) return;
        long raw = unsigned(data, index, size);
        if (raw == (1L << (8 * size)) - 1) return;
        double value = raw * scale;
        if (value >= min && value <= max) put(values, label, value, unit);
    }

    private static long unsigned(byte[] data, int start, int count) {
        long value = 0;
        for (int i = start; i < start + count; i++) value = (value << 8) | (data[i] & 255);
        return value;
    }

    private static void put(Map<String, String> values, String label, double value, String unit) {
        values.put(label, valueWithUnit(value, unit));
    }

    private static String valueWithUnit(double value, String unit) {
        String number = BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
        return unit.isEmpty() ? number : number + " " + unit;
    }

    private ObdVehicleData() {}
}
