package com.kisapilot.kisaagent;

import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.provider.Settings;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Passive C4 monitor plus user-triggered, bounded read-only ELM327 diagnostics. */
public final class VehicleDiagnosticsActivity extends AppCompatActivity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService c4Worker=Executors.newSingleThreadExecutor(),obdWorker=Executors.newSingleThreadExecutor();
    private final StringBuilder trace=new StringBuilder();
    private TextView c4Text,adapterText,dtcText,bmsText;
    private Button connect,dtc,bms;
    private volatile boolean active,c4Busy,obdBusy;
    private volatile Elm327Client elm;
    private int generation;
    private String c4Report="C4 미연결";
    private final Runnable poll=new Runnable(){public void run(){if(!active)return;refreshC4();main.postDelayed(this,2000);}};

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);ScrollView scroll=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(1);
        int p=dp(16);root.setPadding(p,p,p,p);scroll.addView(root);setContentView(scroll);
        ScreenInsets.apply(scroll);
        button(root,"‹ 돌아가기",()->finish());text(root,"아이오닉 5 진단",25);
        text(root,"2024 더 뉴 아이오닉 5 · 84kWh · 후륜 싱글모터",18);
        text(root,"조회 전용 · 고장코드 삭제, ECU 초기화·코딩·차량 제어 기능은 포함하지 않습니다.",15);
        text(root,"C4 차량·통신 상태",22);c4Text=text(root,"C4 연결 확인 중…",16);
        text(root,"ELM327 블루투스 진단",22);
        text(root,"일반 블루투스(SPP) V1.5 어댑터용입니다. 폰 설정에서 먼저 페어링하세요. 다른 OBD 앱의 연결은 종료해 주세요.",15);
        button(root,"블루투스 설정",()->startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        connect=button(root,"어댑터 연결·기본 조회",this::chooseAdapter);
        button(root,"연결 종료 / 조회 중단",this::disconnect);
        adapterText=text(root,"연결 안 됨",16);
        dtc=button(root,"고장코드 조회 (저장·대기·영구)",()->runObd(false));dtc.setEnabled(false);
        dtcText=text(root,"미조회 · NO DATA는 고장 없음이라는 뜻이 아닙니다.",16);
        bms=button(root,"배터리 ECU 응답 수집",()->runObd(true));bms.setEnabled(false);
        bmsText=text(root,"84kWh 차량의 BMS 해석은 실차 응답 검증 전입니다. SOC·SOH·셀 전압을 임의 계산하지 않고 조회 원문을 수집합니다.",16);
        text(root,"OBD 조회는 정차 중 사용하세요. 조회 결과는 응답한 ECU 범위이며, 차량 전체 정비 진단을 대신하지 않습니다.",15);
        button(root,"진단 결과 복사",()->((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Kisa Ioniq5 diagnostics",report())));
        button(root,"진단 결과 파일 저장",this::saveReport);
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(LinearLayout parent,String value,int size){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setPadding(0,dp(7),0,dp(7));parent.addView(t);return t;}
    private Button button(LinearLayout parent,String value,Runnable action){Button b=new Button(this);b.setText(value);parent.addView(b);b.setOnClickListener(v->action.run());return b;}
    @Override protected void onResume(){super.onResume();active=true;main.removeCallbacks(poll);main.post(poll);}
    @Override protected void onPause(){active=false;main.removeCallbacks(poll);disconnect();super.onPause();}
    @Override protected void onDestroy(){c4Worker.shutdownNow();obdWorker.shutdownNow();super.onDestroy();}

    private void refreshC4(){
        if(c4Busy)return;String host;try{host=DeviceAddress.normalize(HomeActivity.serverIp);}catch(Exception e){c4Report="C4 미연결 · OBD 진단은 별도로 사용할 수 있습니다.";c4Text.setText(c4Report);return;}
        c4Busy=true;c4Worker.execute(()->{
            String message;HttpURLConnection c=null;
            try{c=(HttpURLConnection)new URL("http://"+host+":5005/diagnostics/vehicle").openConnection();c.setConnectTimeout(2500);c.setReadTimeout(3000);int code=c.getResponseCode();
                if(code==404)message="C4 진단 API 없음 · C4 코드 업데이트가 필요합니다.";
                else if(code!=200)message="C4 차량 데이터 대기 (HTTP "+code+")";
                else try(InputStream in=c.getInputStream()){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1){if(out.size()+n>131072)throw new IOException("진단 응답 초과");out.write(buf,0,n);}JSONObject j=new JSONObject(out.toString("UTF-8"));message=formatC4(j);}
            }catch(Exception e){message="C4 조회 실패 · "+e.getClass().getSimpleName();}finally{if(c!=null)c.disconnect();c4Busy=false;}
            String result=message;main.post(()->{if(active&&host.equals(HomeActivity.serverIp)){c4Report=result;c4Text.setText(result);}});
        });
    }
    private static String show(JSONObject j,String key){return j==null||j.isNull(key)||!j.has(key)?"미수신":String.valueOf(j.opt(key));}
    private static String formatC4(JSONObject j)throws JSONException{
        StringBuilder s=new StringBuilder();JSONObject detected=j.optJSONObject("detected"),v=j.optJSONObject("vehicle");
        s.append("인식 차량: ").append(show(detected,"fingerprint")).append("\n차종 일치: ").append(show(detected,"expected_model"));
        if(v==null)s.append("\n차량 신호 미수신·오래됨 (정상 판정 불가)");
        else s.append("\n속도: ").append(show(v,"speed_kph")).append(" km/h · 기어: ").append(show(v,"gear"))
            .append("\nCAN 유효: ").append(show(v,"canValid")).append(" · 타임아웃: ").append(show(v,"canTimeout"))
            .append("\nCAN 오류 카운터: ").append(show(v,"canErrorCounter"))
            .append("\n브레이크: ").append(show(v,"brakePressed")).append(" · 가속페달: ").append(show(v,"gasPressed"))
            .append("\n핸들각: ").append(show(v,"steeringAngleDeg")).append("°")
            .append("\n조향 임시/영구 오류: ").append(show(v,"steerFaultTemporary")).append(" / ").append(show(v,"steerFaultPermanent"))
            .append("\nACC 오류: ").append(show(v,"accFaulted"));
        JSONObject streams=j.optJSONObject("streams");if(streams!=null)for(String name:new String[]{"carState","pandaStates","carControl"}){JSONObject h=streams.optJSONObject(name);s.append("\n").append(name).append(": ").append(h!=null&&h.optBoolean("fresh")?"수신 중":h!=null&&h.optBoolean("alive")&&!h.optBoolean("valid")?"유효성 검사 실패":"미수신·오래됨");}
        JSONArray pandas=j.optJSONArray("pandas");if(pandas!=null)for(int i=0;i<pandas.length();i++){JSONObject p=pandas.getJSONObject(i);s.append("\nPanda ").append(i).append(": ").append(show(p,"type")).append(" · 공급전압 ").append(show(p,"supply_voltage_v")).append(" V")
            .append("\n송신 차단 누적: ").append(show(p,"safetyTxBlocked")).append(" · Panda 오류: ").append(show(p,"faults"));
            JSONArray buses=p.optJSONArray("buses");if(buses!=null)for(int b=0;b<buses.length();b++){JSONObject bus=buses.getJSONObject(b);s.append("\n  CAN ").append(show(bus,"index")).append(" · busOff ").append(show(bus,"busOff")).append(" · 오류 ").append(show(bus,"totalErrorCnt"));}}
        s.append("\n※ C4/Panda 상태이며 차량 ECU 고장코드(DTC) 조회 결과가 아닙니다.");return s.toString();
    }

    private void chooseAdapter(){
        if(obdBusy)return;
        if(Build.VERSION.SDK_INT>=31&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},701);return;}
        try{BluetoothManager manager=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);BluetoothAdapter adapter=manager==null?null:manager.getAdapter();if(adapter==null||!adapter.isEnabled()){adapterText.setText("블루투스를 켠 뒤 어댑터를 페어링해 주세요.");return;}
            ArrayList<BluetoothDevice> devices=new ArrayList<>();for(BluetoothDevice d:adapter.getBondedDevices())if(d.getType()!=BluetoothDevice.DEVICE_TYPE_LE)devices.add(d);
            if(devices.isEmpty()){adapterText.setText("일반 블루투스 페어링 장치가 없습니다. BLE 전용 장치는 이 연결 방식으로 사용할 수 없습니다.");return;}
            String[] names=new String[devices.size()];for(int i=0;i<names.length;i++){BluetoothDevice d=devices.get(i);String address=d.getAddress();names[i]=(d.getName()==null?"이름 없음":d.getName())+" · "+address.substring(Math.max(0,address.length()-5));}
            new AlertDialog.Builder(this).setTitle("ELM327 / OBD 어댑터 선택").setItems(names,(dialog,index)->connect(devices.get(index))).setNegativeButton("취소",null).show();
        }catch(SecurityException e){adapterText.setText("근처 기기 연결 권한이 필요합니다.");}
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==701&&results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)chooseAdapter();}
    private void connect(BluetoothDevice device){
        disconnect();Elm327Client client=new Elm327Client();elm=client;int token=generation;obdBusy=true;buttons();adapterText.setText("연결·필수 명령 확인 중…");
        obdWorker.execute(()->{try{
            client.connect(device);String version=query(client,"ATZ");
            for(String command:new String[]{"ATE0","ATL0","ATH1","ATS1","ATD0","ATSP6","ATCAF1","ATCFC1","ATSTFF"}){
                String response=query(client,command);if(!response.toUpperCase(Locale.ROOT).contains("OK"))throw new IOException(command+" 명령 미지원: "+response.trim());
            }
            version=query(client,"ATI");String protocol=query(client,"ATDP");Double volts=Elm327Protocol.adapterVoltage(query(client,"ATRV"));
            setup(client,"ATCRA");setup(client,"ATSH7DF");String vehicle=query(client,"0100");
            String label="블루투스·어댑터 응답: 연결 성공\n표준 차량 ECU: "+(Elm327Protocol.standardEcuReply(vehicle)?"응답 확인":"미응답·미지원 여부 확인 필요")+"\n어댑터: "+version.trim()+"\n프로토콜: "+protocol.trim()+"\nOBD 어댑터 공급전압: "+(volts==null?"미수신":volts+" V")+"\n고전압 배터리 전압·상태와 구분하세요.";
            done(token,()->adapterText.setText(label));
        }catch(Exception e){client.close();done(token,()->adapterText.setText("연결 실패 · "+e.getMessage()));}finally{finishObd(token);}});
    }
    private String query(Elm327Client client,String command)throws IOException{
        String reply=client.query(command);String row="["+command+"]\n"+reply.trim()+"\n";synchronized(trace){if(trace.length()+row.length()>131072)trace.delete(0,Math.min(trace.length(),row.length()+8192));trace.append(row);}return reply;
    }
    private void setup(Elm327Client client,String command)throws IOException{String response=query(client,command);if(!response.toUpperCase(Locale.ROOT).contains("OK"))throw new IOException(command+" 명령 미지원");}
    private void runObd(boolean battery){
        Elm327Client client=elm;if(obdBusy||client==null||!client.connected())return;int token=generation;obdBusy=true;buttons();
        (battery?bmsText:dtcText).setText("조회 중…");obdWorker.execute(()->{try{
            StringBuilder result=new StringBuilder();
            if(battery){setup(client,"ATSH7E4");setup(client,"ATCRA7EC");for(String command:new String[]{"220101","220105"})result.append(command).append("\n").append(query(client,command).trim()).append("\n");result.append("\n84kWh 해석 미검증 · 원문을 저장해 차량별 항목 검증에 사용할 수 있습니다.");}
            else{setup(client,"ATCRA");setup(client,"ATSH7DF");String[] commands={"03","07","0A"},labels={"저장 코드","대기 코드","영구 코드"};
                for(int i=0;i<commands.length;i++){Elm327Protocol.Result decoded=Elm327Protocol.dtcs(query(client,commands[i]),Integer.parseInt(commands[i],16));result.append(labels[i]).append(": ").append(decoded.status()).append("\n");for(Map.Entry<String,List<String>> entry:decoded.codes.entrySet())result.append("  ECU ").append(entry.getKey()).append(": ").append(entry.getValue().isEmpty()?"보고된 코드 없음":entry.getValue()).append("\n");}
                result.append("\n응답한 ECU의 표준 OBD 코드만 표시합니다. 미응답 ECU의 고장 유무는 알 수 없습니다.");}
            done(token,()->(battery?bmsText:dtcText).setText(result.toString()));
        }catch(Exception e){client.close();done(token,()->(battery?bmsText:dtcText).setText("조회 실패 · "+e.getMessage()));}finally{finishObd(token);}});
    }
    private void done(int token,Runnable action){main.post(()->{if(active&&generation==token)action.run();});}
    private void finishObd(int token){main.post(()->{if(generation==token){obdBusy=false;buttons();}});}
    private void buttons(){connect.setEnabled(!obdBusy);boolean ready=!obdBusy&&elm!=null&&elm.connected();dtc.setEnabled(ready);bms.setEnabled(ready);}
    private void disconnect(){generation++;Elm327Client client=elm;elm=null;if(client!=null)client.close();obdBusy=false;if(connect!=null)buttons();}
    private String report(){String raw;synchronized(trace){raw=trace.toString();}return "Kisa vehicle diagnostics\n2024 IONIQ5 PE / 84kWh / RWD / single motor\n"+c4Report+"\n\n"+adapterText.getText()+"\n"+dtcText.getText()+"\n"+bmsText.getText()+"\n\nELM raw responses\n"+raw;}
    private void saveReport(){try{File folder=new File(getExternalFilesDir(null),"kisa_vehicle_diagnostics");if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("폴더 생성 실패");File file=new File(folder,"diagnostics-"+System.currentTimeMillis()+".txt");try(OutputStream out=new FileOutputStream(file)){out.write(report().getBytes(StandardCharsets.UTF_8));}Toast.makeText(this,"저장: "+file.getAbsolutePath(),Toast.LENGTH_LONG).show();}catch(Exception e){Toast.makeText(this,"저장 실패: "+e.getMessage(),Toast.LENGTH_LONG).show();}}
}
