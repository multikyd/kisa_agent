package com.kisapilot.kisaagent;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.regex.*;

/** A durable partial file; never append a full response to an HTTP range request. */
final class ResumableLogFile {
    private static volatile HttpURLConnection active;
    static void interrupt() {
        HttpURLConnection c = active;
        if (c != null) c.disconnect();
    }
    static final class Session {
        volatile HttpURLConnection connection;
        void interrupt() { HttpURLConnection c=connection; if(c!=null)c.disconnect(); }
    }
    static class SourceChanged extends IOException {
        SourceChanged(String message) { super(message); }
    }
    static void validateRange(String header, long offset, long expected) throws IOException {
        Matcher range = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)").matcher(String.valueOf(header));
        try {
            if (range.matches() && Long.parseLong(range.group(1)) == offset
                    && Long.parseLong(range.group(2)) == expected - 1
                    && Long.parseLong(range.group(3)) == expected) return;
        } catch (NumberFormatException ignored) { }
        throw new SourceChanged("이어받기 응답의 파일 크기/위치가 달라졌습니다.");
    }
    static void fetch(URL url, File part, long expected, BooleanSupplier paused,
                      LongConsumer progress) throws IOException {
        fetch(url, part, expected, paused, progress, null);
    }
    static void fetch(URL url, File part, long expected, BooleanSupplier paused,
                      LongConsumer progress, Session session) throws IOException {
        if (expected < 0) throw new IOException("잘못된 파일 크기");
        File validator = new File(part.getPath() + ".etag");
        long offset = part.length();
        if (offset > expected) throw new SourceChanged("임시 파일 크기가 달라졌습니다.");
        if (part.isFile() && offset == expected) return;
        String etag = validator.isFile() ? new String(Files.readAllBytes(validator.toPath()), StandardCharsets.UTF_8) : "";
        // Without a validator, a partial file cannot safely be joined to a new response.
        if (offset > 0 && etag.isEmpty()) offset = 0;
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        if (session == null) active = c; else session.connection = c;
        c.setConnectTimeout(15000);
        c.setReadTimeout(120000);
        c.setRequestProperty("Accept-Encoding", "identity");
        if (offset > 0) {
            c.setRequestProperty("Range", "bytes=" + offset + "-");
            c.setRequestProperty("If-Range", etag);
        }
        try {
            int code = c.getResponseCode();
            String current = c.getHeaderField("ETag");
            if (code == 206) {
                validateRange(c.getHeaderField("Content-Range"), offset, expected);
                if (!etag.isEmpty() && current != null && !etag.equals(current))
                    throw new SourceChanged("C4 원본 파일이 변경되었습니다. 주행 종료 후 다시 선택해주세요.");
            } else if (code == 200) {
                offset = 0; // Server ignored Range or If-Range detected a changed source.
                if (c.getContentLengthLong() >= 0 && c.getContentLengthLong() != expected)
                    throw new SourceChanged("C4 원본 파일 크기가 변경되었습니다. 목록을 다시 불러와주세요.");
            } else if (code == 404) {
                throw new SourceChanged("C4에서 원본 파일을 찾을 수 없습니다. (HTTP 404)");
            } else if (code == 416) {
                throw new SourceChanged("C4 원본 파일의 이어받기 범위가 변경되었습니다. (HTTP 416)");
            } else {
                throw new IOException("HTTP " + code);
            }
            try (RandomAccessFile output = new RandomAccessFile(part, "rw")) {
                output.setLength(offset);
                output.seek(offset);
                Files.write(validator.toPath(), (current != null && !current.startsWith("W/") ? current : "").getBytes(StandardCharsets.UTF_8));
                long received = offset;
                progress.accept(received);
                try (InputStream input = c.getInputStream()) {
                    byte[] buffer = new byte[65536];
                    while (received < expected) {
                        if (paused.getAsBoolean()) throw new IOException("일시정지됨");
                        int n = input.read(buffer, 0, (int)Math.min(buffer.length, expected - received));
                        if (n < 0) throw new EOFException("전송 연결이 끊겼습니다.");
                        output.write(buffer, 0, n);
                        received += n;
                        progress.accept(received);
                    }
                    if (input.read() != -1) {
                        output.setLength(0);
                        throw new SourceChanged("C4 원본 파일이 기록 중입니다. 주행 종료 후 다시 선택해주세요.");
                    }
                } finally { output.getFD().sync(); }
            }
        } finally {
            c.disconnect();
            if (session == null) { if (active == c) active = null; }
            else if (session.connection == c) session.connection = null;
        }
    }
}
