package com.kisapilot.kisaagent;

import android.bluetooth.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;

/** Classic Bluetooth SPP transport. Closing the socket cancels connect and read. */
final class Elm327Client implements Closeable {
    private volatile BluetoothSocket socket;
    private volatile boolean closed;
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
    void connect(BluetoothDevice device)throws IOException {
        if(closed)throw new IOException("연결이 취소됐습니다.");
        BluetoothSocket candidate=device.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"));
        socket=candidate;
        if(closed){candidate.close();throw new IOException("연결이 취소됐습니다.");}
        ScheduledFuture<?> deadline=timer.schedule(()->closeSocket(candidate),12,TimeUnit.SECONDS);
        try{candidate.connect();if(closed)throw new IOException("연결이 취소됐습니다.");}finally{deadline.cancel(false);}
    }
    String query(String input)throws IOException {
        String command=Elm327Protocol.command(input);BluetoothSocket current=socket;
        if(closed||current==null||!current.isConnected())throw new IOException("OBD 연결이 없습니다.");
        ScheduledFuture<?> deadline=timer.schedule(()->closeSocket(current),7,TimeUnit.SECONDS);
        try{
            OutputStream out=current.getOutputStream();InputStream in=current.getInputStream();
            out.write((command+"\r").getBytes(StandardCharsets.US_ASCII));out.flush();
            ByteArrayOutputStream response=new ByteArrayOutputStream();
            long until=android.os.SystemClock.elapsedRealtime()+6500;
            while(!closed&&android.os.SystemClock.elapsedRealtime()<until){
                if(in.available()>0){int b=in.read();if(b<0)throw new IOException("OBD 연결이 종료됐습니다.");if(b=='>')return response.toString("US-ASCII");response.write(b);if(response.size()>65536)throw new IOException("OBD 응답이 너무 큽니다.");}
                else try{Thread.sleep(10);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("진단이 중단됐습니다.",e);}
            }
            close();throw new IOException("OBD 응답 시간 초과");
        }finally{deadline.cancel(false);}
    }
    boolean connected(){BluetoothSocket s=socket;return !closed&&s!=null&&s.isConnected();}
    private static void closeSocket(BluetoothSocket s){try{s.close();}catch(IOException ignored){}}
    @Override public void close(){closed=true;BluetoothSocket s=socket;if(s!=null)closeSocket(s);timer.shutdownNow();}
}
