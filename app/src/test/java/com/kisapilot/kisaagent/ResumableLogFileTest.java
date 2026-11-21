package com.kisapilot.kisaagent;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class ResumableLogFileTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static class Reply extends HttpURLConnection {
        final int code; final String data, range, etag; final long size;
        Reply(int code, String data, String range, String etag, long size) throws Exception {
            super(new URL("http://device/log")); this.code = code; this.data = data;
            this.range = range; this.etag = etag; this.size = size;
        }
        @Override public int getResponseCode() { return code; }
        @Override public long getContentLengthLong() { return size; }
        @Override public String getHeaderField(String name) { return "ETag".equals(name) ? etag : "Content-Range".equals(name) ? range : null; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)); }
        @Override public void connect() { }
        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
    }
    private URL url(Reply reply) throws Exception {
        return new URL(null, "http://device/log", new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL u) { return reply; }
        });
    }
    private File partial(String text, String etag) throws Exception {
        File file = temp.newFile();
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        Files.write(new File(file.getPath()+".etag").toPath(), etag.getBytes(StandardCharsets.UTF_8));
        return file;
    }
    @Test public void truncatedConnectionKeepsBytesThenResumesExactly() throws Exception {
        File file = temp.newFile();
        Reply first = new Reply(200, "abc", null, "\"v1\"", 6);
        try { ResumableLogFile.fetch(url(first), file, 6, () -> false, n -> {}); fail(); }
        catch (EOFException expected) { }
        assertEquals(3, file.length());
        Reply second = new Reply(206, "def", "bytes 3-5/6", "\"v1\"", 3);
        ResumableLogFile.fetch(url(second), file, 6, () -> false, n -> {});
        assertEquals("bytes=3-", second.getRequestProperty("Range"));
        assertEquals("\"v1\"", second.getRequestProperty("If-Range"));
        assertEquals("abcdef", new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }
    @Test public void ignoredRangeRestartsFileInsteadOfAppending() throws Exception {
        File file = partial("abc", "\"v1\"");
        ResumableLogFile.fetch(url(new Reply(200, "UVWXYZ", null, "\"v2\"", 6)), file, 6, () -> false, n -> {});
        assertEquals("UVWXYZ", new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }
    @Test public void changedETagCannotJoinTwoVersions() throws Exception {
        File file = partial("abc", "\"v1\"");
        try { ResumableLogFile.fetch(url(new Reply(206, "def", "bytes 3-5/6", "\"v2\"", 3)), file, 6, () -> false, n -> {}); fail(); }
        catch (ResumableLogFile.SourceChanged expected) { }
        assertEquals("abc", new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }
    @Test public void incorrectRangeIsRejectedBeforeAppending() throws Exception {
        File file = partial("abc", "\"v1\"");
        try { ResumableLogFile.fetch(url(new Reply(206, "def", "bytes 2-4/6", "\"v1\"", 3)), file, 6, () -> false, n -> {}); fail(); }
        catch (ResumableLogFile.SourceChanged expected) { }
        assertEquals(3, file.length());
    }
    @Test public void completedFileDoesNotContactServerAgain() throws Exception {
        File file = partial("abcdef", "\"v1\"");
        ResumableLogFile.fetch(url(new Reply(500, "", null, null, 0)), file, 6, () -> false, n -> fail());
        assertEquals(6, file.length());
    }
    @Test public void noValidatorRestartsRatherThanCombiningUnknownFiles() throws Exception {
        File file = partial("abc", "");
        Reply reply = new Reply(200, "UVWXYZ", null, "\"v1\"", 6);
        ResumableLogFile.fetch(url(reply), file, 6, () -> false, n -> {});
        assertNull(reply.getRequestProperty("Range"));
        assertEquals("UVWXYZ", new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }
    @Test public void rangeOffsetsBeyondFourGiBAreNotTruncated() throws Exception {
        ResumableLogFile.validateRange("bytes 4294967297-5368709119/5368709120", 4294967297L, 5368709120L);
    }
    @Test public void pausePreservesAlreadyDownloadedPart() throws Exception {
        File file = partial("abc", "\"v1\"");
        try { ResumableLogFile.fetch(url(new Reply(206, "def", "bytes 3-5/6", "\"v1\"", 3)), file, 6, () -> true, n -> {}); fail(); }
        catch (IOException expected) { }
        assertEquals(3, file.length());
    }
    @Test public void realHttpConnectionRecoversAfterServerDropsConnection() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> secondRequest = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> serverError = new java.util.concurrent.atomic.AtomicReference<>();
        try (ServerSocket server = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(5000);
            Thread thread = new Thread(() -> {
                try {
                    for (int attempt = 0; attempt < 2; attempt++) {
                        try (Socket socket = server.accept()) {
                            socket.setSoTimeout(5000);
                            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                            StringBuilder request = new StringBuilder();
                            String line;
                            while ((line = reader.readLine()) != null && !line.isEmpty()) request.append(line).append('\n');
                            if (attempt == 1) secondRequest.set(request.toString());
                            String response = attempt == 0
                                    ? "HTTP/1.1 200 OK\r\nContent-Length: 6\r\nETag: \"v1\"\r\nConnection: close\r\n\r\nabc"
                                    : "HTTP/1.1 206 Partial Content\r\nContent-Length: 3\r\nContent-Range: bytes 3-5/6\r\nETag: \"v1\"\r\nConnection: close\r\n\r\ndef";
                            socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                            socket.getOutputStream().flush();
                        }
                    }
                } catch (Throwable e) { serverError.set(e); }
            });
            thread.start();
            File part = temp.newFile();
            URL endpoint = new URL("http://127.0.0.1:" + server.getLocalPort() + "/log");
            try { ResumableLogFile.fetch(endpoint, part, 6, () -> false, n -> {}); fail(); }
            catch (IOException expected) { }
            assertEquals(3, part.length());
            ResumableLogFile.fetch(endpoint, part, 6, () -> false, n -> {});
            thread.join(6000);
            assertFalse(thread.isAlive());
            assertNull(serverError.get());
            assertTrue(secondRequest.get().contains("Range: bytes=3-"));
            assertTrue(secondRequest.get().contains("If-Range: \"v1\""));
            assertEquals("abcdef", new String(Files.readAllBytes(part.toPath()), StandardCharsets.UTF_8));
        }
    }
}
