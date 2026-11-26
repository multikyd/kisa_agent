package com.kisapilot.kisaagent;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.Locale;

/** Collect every advertised Mode 01 PID, including replies without a decoder. */
final class StandardPidScan {
    interface Reader { String query(String command) throws IOException; }

    static String read(Reader reader) throws IOException {
        Set<Integer> supported = new TreeSet<>();
        StringBuilder result = new StringBuilder("지원 PID 탐색\n");
        for (int base = 0; base <= 0xE0; base += 0x20) {
            if (base != 0 && !supported.contains(base)) break;
            String raw = reader.query(command(base));
            ObdVehicleData.supportedPids(raw, base, supported);
            appendReplies(result, base, raw);
        }
        int requested = 0;
        result.append("\n차량 PID 데이터\n");
        for (int pid : supported) {
            if (pid % 32 == 0) continue; // Already read as support bitmaps.
            requested++;
            appendReplies(result, pid, reader.query(command(pid)));
        }
        result.append("\n지원 PID 조회 수  ").append(requested).append("개\n");
        result.append("\n차량 정보\n");
        Map<String, byte[]> vins = ObdVehicleData.replies(reader.query(ObdVehicleData.VIN), 0x49, 2);
        if (vins.isEmpty()) result.append("차대번호  정상 응답 없음\n");
        for (Map.Entry<String, byte[]> reply : vins.entrySet()) {
            String vin = ObdVehicleData.vin(reply.getValue());
            result.append("차대번호 (ECU ").append(reply.getKey()).append(")  ")
                    .append(vin == null ? "해석 전 RAW " + hex(reply.getValue()) : vin).append('\n');
        }
        return result.toString().trim();
    }

    private static void appendReplies(StringBuilder result, int pid, String raw) {
        Map<String, byte[]> replies = ObdVehicleData.replies(raw, 0x41, pid);
        String command = command(pid);
        if (replies.isEmpty()) result.append(command).append("  정상 응답 없음 · RAW에서 확인\n");
        for (Map.Entry<String, byte[]> reply : replies.entrySet()) {
            byte[] bytes = reply.getValue();
            result.append(command).append(" (ECU ").append(reply.getKey()).append(")  ");
            ObdVehicleData.Signal signal = ObdVehicleData.STANDARD.get(pid);
            String value = signal == null ? null : signal.value(bytes);
            if (pid == ObdVehicleData.MONITOR_STATUS && bytes.length == 4) {
                result.append("고장 표시등 ").append((bytes[0] & 0x80) == 0 ? "꺼짐" : "켜짐")
                        .append(" · 보고된 고장 코드 수 ").append(bytes[0] & 0x7F);
            } else if (value != null) {
                result.append(signal.label).append("  ").append(value);
            } else {
                result.append(pid % 32 == 0 ? "지원 비트맵" : "해석 전 데이터");
            }
            result.append(" · RAW ").append(hex(bytes)).append('\n');
        }
        if (Elm327Protocol.readPayloads(raw, 0x41, (ecu, payload) -> {})) {
            result.append(command).append("  응답 일부 누락·해석 실패 · RAW에서 확인\n");
        }
    }

    static String command(int pid) { return String.format(Locale.ROOT, "01%02X", pid); }
    private static String hex(byte[] data) {
        StringBuilder result = new StringBuilder();
        for (byte b : data) {
            if (result.length() > 0) result.append(' ');
            result.append(String.format(Locale.ROOT, "%02X", b & 255));
        }
        return result.toString();
    }
    private StandardPidScan() {}
}
