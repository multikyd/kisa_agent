package com.kisapilot.kisaagent;
import java.io.*;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
final class DriveLogTransfer {
    static void copy(InputStream input, OutputStream output, long expected,
                     BooleanSupplier cancelled, LongConsumer progress) throws IOException {
        byte[] buffer = new byte[65536];
        long received = 0;
        int n;
        while ((n = input.read(buffer)) != -1) {
            if (cancelled.getAsBoolean()) throw new IOException("취소됨");
            output.write(buffer, 0, n);
            received += n;
            progress.accept(n);
        }
        if (cancelled.getAsBoolean()) throw new IOException("취소됨");
        if (received != expected) throw new IOException("파일이 변경되었거나 전송이 끊겼습니다. 주행 종료 후 다시 다운로드해주세요.");
    }
}
