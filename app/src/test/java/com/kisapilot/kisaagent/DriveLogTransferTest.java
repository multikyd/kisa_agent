package com.kisapilot.kisaagent;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.util.zip.*;
public class DriveLogTransferTest {
    @Test public void zipPreservesLargeFileAndSegmentPaths() throws Exception {
        byte[] data = new byte[262147];
        for (int i = 0; i < data.length; i++) data[i] = (byte)(i * 31);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final long[] count = {0};
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.setLevel(0);
            zip.putNextEntry(new ZipEntry("2026-10-10--12-00-00--0/rlog.zst"));
            DriveLogTransfer.copy(new ByteArrayInputStream(data), zip, data.length, () -> false, n -> count[0] += n);
            zip.closeEntry();
        }
        assertEquals(data.length, count[0]);
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertEquals("2026-10-10--12-00-00--0/rlog.zst", zip.getNextEntry().getName());
            ByteArrayOutputStream restored = new ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n;
            while ((n = zip.read(buf)) != -1) restored.write(buf, 0, n);
            assertArrayEquals(data, restored.toByteArray());
            assertNull(zip.getNextEntry());
        }
    }
    @Test(expected = IOException.class) public void truncatedTransferFails() throws Exception {
        DriveLogTransfer.copy(new ByteArrayInputStream(new byte[3]), new ByteArrayOutputStream(), 4, () -> false, n -> {});
    }
    @Test(expected = IOException.class) public void growingActiveLogFails() throws Exception {
        DriveLogTransfer.copy(new ByteArrayInputStream(new byte[5]), new ByteArrayOutputStream(), 4, () -> false, n -> {});
    }
    @Test(expected = IOException.class) public void cancellationInterruptsTransfer() throws Exception {
        DriveLogTransfer.copy(new ByteArrayInputStream(new byte[10]), new ByteArrayOutputStream(), 10, () -> true, n -> {});
    }
}
