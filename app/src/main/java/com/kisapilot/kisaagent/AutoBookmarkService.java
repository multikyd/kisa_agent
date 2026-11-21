package com.kisapilot.kisaagent;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import androidx.core.app.NotificationCompat;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

/** Server-built ZIPs (or legacy original files), durable queue and resumable cache; no recompression. */
public final class AutoBookmarkService extends Service {
    private static final String CHANNEL="auto_bookmark";
    private static final int ID=5006;
    static volatile String status="자동 저장 대기";
    private static volatile boolean running;
    private volatile boolean stopping;
    private volatile boolean transferring;
    private volatile HttpURLConnection request;
    private final ResumableLogFile.Session transfer = new ResumableLogFile.Session();
    private Thread worker;
    private final Map<String, Integer> missingPolls = new HashMap<>();
    private long noticeTime;
    private volatile PowerManager.WakeLock wake;
    private volatile String remoteState="waiting";
    private volatile int remotePercent, remotePending;
    private volatile long savedUntil;
    private java.util.concurrent.ScheduledExecutorService reports;
    private volatile HttpURLConnection statusRequest;
    static SharedPreferences prefs(Context c){return c.getSharedPreferences("auto_bookmarks",0);}
    static void enable(Context c) {
        String server=DeviceAddress.normalize(HomeActivity.serverIp);
        prefs(c).edit().putString("server",server).putBoolean("enabled",true).commit();
        try {c.startForegroundService(new Intent(c,AutoBookmarkService.class));}
        catch(RuntimeException e){prefs(c).edit().putBoolean("enabled",false).commit();throw e;}
    }
    static void resumeIfEnabled(Context c) {
        if(!prefs(c).getBoolean("enabled",true)||running)return;
        if(prefs(c).getString("tree","").isEmpty()){status="자동 저장 ON · 북마크 화면에서 저장 폴더를 선택해주세요";return;}
        try{c.startForegroundService(new Intent(c,AutoBookmarkService.class));}
        catch(RuntimeException e){status="자동 저장 재시작 필요: "+e.getMessage();}
    }
    static void disable(Context c) {
        prefs(c).edit().putBoolean("enabled",false).commit();
        c.stopService(new Intent(c,AutoBookmarkService.class));
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onCreate(){super.onCreate();getSystemService(NotificationManager.class).createNotificationChannel(
        new NotificationChannel(CHANNEL,"북마크 자동 저장",NotificationManager.IMPORTANCE_LOW));}
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent!=null&&"stop".equals(intent.getAction())){disable(this);return START_NOT_STICKY;}
        if(running)return START_NOT_STICKY;
        if(!prefs(this).getBoolean("enabled",true)||prefs(this).getString("tree","").isEmpty()){stopSelf();return START_NOT_STICKY;}
        try {
            if(Build.VERSION.SDK_INT>=29)startForeground(ID,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(ID,notification());
            stopping=false;running=true;
            reports=java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
            reports.scheduleWithFixedDelay(this::reportStatus,0,2,java.util.concurrent.TimeUnit.SECONDS);
            worker=new Thread(this::loop,"automatic-bookmark-download");worker.start();
        }catch(Exception e){status="자동 저장 시작 실패: "+e.getMessage();running=false;stopSelf();}
        return START_NOT_STICKY;
    }
    private Notification notification(){
        PendingIntent open=PendingIntent.getActivity(this,20,new Intent(this,BookmarkLogsActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,21,new Intent(this,AutoBookmarkService.class).setAction("stop"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this,CHANNEL).setSmallIcon(transferring ? android.R.drawable.stat_sys_download : R.drawable.ic_bookmark_status)
            .setContentTitle("북마크 자동 저장").setContentText(status).setStyle(new NotificationCompat.BigTextStyle().bigText(status))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).addAction(0,"끄기",stop).build();
    }
    private void update(String text){
        status=text;long now=SystemClock.elapsedRealtime();
        if(text.startsWith("북마크 저장 완료")){remoteState="saved";remotePercent=100;savedUntil=now+8000;}
        else if(text.startsWith("연결/파일 확인")||text.startsWith("C4 원본 확인"))remoteState="error";
        else if(!text.startsWith("받는 중:")&&now>=savedUntil)remoteState="waiting";
        if(now-noticeTime<1000)return;noticeTime=now;
        try{getSystemService(NotificationManager.class).notify(ID,notification());}catch(SecurityException ignored){}
    }
    private void setTransferring(boolean value) {
        transferring=value;
        noticeTime=0; // Icon changes should not wait for the progress throttle.
        if(!value && "receiving".equals(remoteState))remoteState="waiting";
        if(stopping)return;
        update(value ? "파일 전송 준비 중" : "전송 대기 · 받은 파일 유지");
    }
    private void reportStatus(){
        if(stopping)return;
        HttpURLConnection c=null;
        try{
            c=(HttpURLConnection)new URL("http://"+server()+":5005/bookmarks/auto/status").openConnection();statusRequest=c;
            c.setConnectTimeout(1500);c.setReadTimeout(1500);c.setRequestMethod("POST");c.setDoOutput(true);
            c.setRequestProperty("Content-Type","application/json; charset=UTF-8");
            byte[] body=new JSONObject().put("state",remoteState).put("pending",Math.min(10000,remotePending)).put("percent",remotePercent).toString().getBytes(StandardCharsets.UTF_8);
            try(OutputStream output=c.getOutputStream()){output.write(body);}c.getResponseCode();
        }catch(Exception ignored){}finally{if(c!=null)c.disconnect();if(statusRequest==c)statusRequest=null;}
    }
    private void check() throws IOException {if(stopping||Thread.currentThread().isInterrupted())throw new IOException("자동 저장 중단 · 대기열/받은 파일 유지");}
    private String server(){
        try {String ip=DeviceAddress.normalize(HomeActivity.serverIp);prefs(this).edit().putString("server",ip).apply();return ip;}
        catch(IllegalArgumentException e){return DeviceAddress.normalize(prefs(this).getString("server",""));}
    }
    private JSONObject api(String server,String path,JSONObject body) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL("http://"+server+":5005"+path).openConnection();request=c;
        c.setConnectTimeout(6000);c.setReadTimeout(12000);
        try {
            if(body!=null){c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=UTF-8");
                try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}}
            int code=c.getResponseCode();if(code!=200)throw new IOException("C4 HTTP "+code+(code==404?" · C4 북마크 자동 저장 API 업데이트 필요":""));
            StringBuilder text=new StringBuilder();try(Reader in=new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8)){
                char[] buf=new char[8192];int n;while((n=in.read(buf))!=-1){check();text.append(buf,0,n);if(text.length()>16*1024*1024)throw new IOException("응답 크기 초과");}}
            return new JSONObject(text.toString());
        }finally{c.disconnect();if(request==c)request=null;}
    }
    private void saveQueue(Set<String> seen,Set<String> pending){
        if(!prefs(this).edit().putStringSet("seen",new HashSet<>(seen)).putStringSet("pending",new HashSet<>(pending)).putBoolean("initialized",true).commit())
            throw new IllegalStateException("대기열을 저장하지 못했습니다.");
    }
    private boolean cycle() throws Exception {
        String ip=server();JSONObject state=api(ip,"/bookmarks/auto",null);
        if(!"kisa-bookmark-auto-v1".equals(state.optString("format")))throw new IOException("C4 자동 저장 응답 형식 확인 필요");
        if ("sftp".equals(state.optString("transport"))) {
            update("서버 SFTP 저장 모드 · 폰 대기열 유지");
            return false;
        }
        JSONArray bookmarks=state.getJSONArray("bookmarks");Map<String,Boolean> active=new LinkedHashMap<>();Map<String,JSONObject> current=new HashMap<>();
        for(int i=0;i<bookmarks.length();i++){JSONObject b=bookmarks.getJSONObject(i);active.put(b.getString("id"),b.optBoolean("active"));current.put(b.getString("id"),b);}
        Set<String> seen=new HashSet<>(prefs(this).getStringSet("seen",Collections.emptySet()));
        Set<String> pending=new TreeSet<>(prefs(this).getStringSet("pending",Collections.emptySet()));
        AutoBookmarkPolicy.observe(seen,pending,active,prefs(this).getBoolean("initialized",false));
        Set<String> removed=AutoBookmarkPolicy.discardMissing(pending,current.keySet(),missingPolls);
        // Keep seen IDs: an expired job must not become a new download if it reappears.
        saveQueue(seen,pending);remotePending=pending.size();
        if(!removed.isEmpty())prefs(this).edit().putString("last_expired",
            "C4 원본이 목록에서 사라져 자동 대기열에서 제외: "+String.join(", ",new TreeSet<>(removed))
            +"\n폰에 저장된 파일은 유지됩니다.").commit();
        boolean onroad=state.optBoolean("onroad",true);
        if(DriveLogDownloadService.running){update("수동 다운로드 완료 대기 · 북마크 "+pending.size()+"개 보관");return false;}
        for(String id:new ArrayList<>(pending)){
            check();JSONObject b=current.get(id);
            if(b==null){update("원본 목록 재확인 중: "+id+" · "+missingPolls.getOrDefault(id,0)+"/3");continue;}
            if(!b.optBoolean("ready")){update("앞뒤 구간 기록 완료 대기: "+id);continue;}
            JSONObject plan=api(ip,"/bookmarks/export",new JSONObject().put("ids",new JSONArray().put(id)).put("adjacent",1).put("archive",true));
            JSONArray files=plan.getJSONArray("files");if(files.length()==0)throw new IOException("다운로드 파일 없음");
            // Transfer closed logs first, then video, including while driving.
            List<JSONObject> ordered=new ArrayList<>();for(int i=0;i<files.length();i++)ordered.add(files.getJSONObject(i));
            ordered.sort(Comparator.comparing(f->DriveLogFiles.isVideo(f.optString("path"))));
            boolean all=true;
            for(JSONObject f:ordered){
                check();String relative=DriveLogFiles.relative(f.getString("path"));long size=f.getLong("size");
                if(relative==null||relative.split("/",-1).length!=2||DriveLogFiles.isLock(relative))throw new IOException("잘못된 원본 경로");
                if(isComplete(relative,size))continue;
                all=false;if(!AutoBookmarkPolicy.canTransfer(relative))continue;
                receive(ip,f,relative,size,onroad);return true; // Re-poll bookmarks between files.
            }
            if(all){
                pending.remove(id);saveQueue(seen,pending);remotePending=pending.size();update("북마크 저장 완료: "+id);return true;
            }
            update("북마크 파일 전송 대기 · "+pending.size()+"개");
        }
        if(pending.isEmpty())update("새 북마크 대기 · 저장 완료된 구간은 중복 전송하지 않음");
        return false;
    }
    private String key(String relative)throws Exception{
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(relative.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();
        for(byte b:digest)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();
    }
    private boolean isComplete(String relative,long size)throws Exception{
        String saved=prefs(this).getString("file:"+key(relative),null);if(saved==null)return false;
        JSONObject value=new JSONObject(saved);if(value.getLong("size")!=size)return false;
        if(!value.getString("tree").equals(prefs(this).getString("tree","")))return false;
        try(Cursor c=getContentResolver().query(Uri.parse(value.getString("uri")),new String[]{DocumentsContract.Document.COLUMN_SIZE},null,null,null)){
            return c!=null&&c.moveToFirst()&&(c.isNull(0)||c.getLong(0)==size);
        }
    }
    private void receive(String ip,JSONObject f,String relative,long size,boolean onroad)throws Exception{
        if(size<0)throw new IOException("잘못된 파일 크기");
        File cache=new File(getFilesDir(),"auto-bookmark-parts");if(!cache.isDirectory()&&!cache.mkdirs())throw new IOException("임시 폴더 생성 실패");
        File part=new File(cache,key(relative)+".part");
        if(new StatFs(cache.getPath()).getAvailableBytes()<Math.max(0,size-part.length())+64*1024*1024L)throw new IOException("휴대폰 공간 부족 · 받은 파일 유지");
        PowerManager.WakeLock lock=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"KisaAgent:AutoBookmark");wake=lock;lock.acquire(15*60*1000L);
        try{
            setTransferring(true);
            URL url=new URL("http://"+ip+":5005/file/explorer_download?path="+URLEncoder.encode(f.getString("path"),"UTF-8"));
            final long[] first={-1};final long started=SystemClock.elapsedRealtime();
            try{ResumableLogFile.fetch(url,part,size,()->stopping||DriveLogDownloadService.running,n->{
                if(first[0]<0)first[0]=n;
                if(onroad){long wait=(n-first[0])*1000/(1024*1024)-(SystemClock.elapsedRealtime()-started);
                    if(wait>0)java.util.concurrent.locks.LockSupport.parkNanos(Math.min(wait,1000)*1000000L);}
                remotePercent=(int)(size==0?100:n*100/size);remoteState="receiving";
                update("받는 중: "+relative+" · "+remotePercent+"%");
            },transfer);}
            catch(ResumableLogFile.SourceChanged e){Files.deleteIfExists(part.toPath());Files.deleteIfExists(new File(part+".etag").toPath());throw e;}
            check();String[] names=relative.split("/");Uri parent=child(treeRoot(),names[0],true),target=child(parent,names[1],false);
            try(InputStream in=new FileInputStream(part);OutputStream output=getContentResolver().openOutputStream(target,"wt")){
                if(output==null)throw new IOException("저장 폴더 쓰기 실패");byte[] buf=new byte[65536];long copied=0;int n;
                while((n=in.read(buf))!=-1){check();output.write(buf,0,n);copied+=n;}if(copied!=size)throw new IOException("저장 크기 불일치");output.flush();
            }
            JSONObject saved=new JSONObject().put("uri",target.toString()).put("tree",prefs(this).getString("tree","")).put("size",size);
            if(!prefs(this).edit().putString("file:"+key(relative),saved.toString()).commit())throw new IOException("완료 상태 저장 실패");
            Files.deleteIfExists(part.toPath());Files.deleteIfExists(new File(part+".etag").toPath());
        }finally{setTransferring(false);synchronized(lock){if(lock.isHeld())lock.release();}if(wake==lock)wake=null;}
    }
    private Uri treeRoot(){Uri tree=Uri.parse(prefs(this).getString("tree",""));return DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));}
    private Uri child(Uri parent,String name,boolean directory)throws Exception{
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(parent,DocumentsContract.getDocumentId(parent));
        try(Cursor c=getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE},null,null,null)){
            if(c==null)throw new IOException("저장 폴더 접근 실패");while(c.moveToNext())if(name.equals(c.getString(1))){
                boolean actual=DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2));if(actual!=directory)throw new IOException("파일/폴더 충돌: "+name);
                return DocumentsContract.buildDocumentUriUsingTree(parent,c.getString(0));}}
        Uri result=DocumentsContract.createDocument(getContentResolver(),parent,directory?DocumentsContract.Document.MIME_TYPE_DIR:"application/octet-stream",name);
        if(result==null)throw new IOException("파일 생성 실패: "+name);return result;
    }
    private void loop(){
        long start=SystemClock.elapsedRealtime();
        try{
            while(!stopping){
                // Bound a driving session; Android also imposes its own shared dataSync limit.
                if(SystemClock.elapsedRealtime()-start>4*60*60*1000L){update("4시간 세션 종료 · 앱을 열면 이어받기");break;}
                boolean progress=false;
                try{progress=cycle();}catch(Exception e){if(stopping)break;update("연결/파일 확인 대기 · 받은 파일 유지: "+e.getMessage());}
                try{Thread.sleep(progress?1000:15000);}catch(InterruptedException e){break;}
            }
        }finally{if(reports!=null)reports.shutdownNow();running=false;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
    }
    @Override public void onTimeout(int startId,int fgsType){status="Android 자동 저장 시간 제한 · 앱을 열면 이어받기";stopSelf();}
    @Override public void onDestroy(){
        stopping=true;if(worker!=null)worker.interrupt();transfer.interrupt();HttpURLConnection c=request;if(c!=null)c.disconnect();
        if(reports!=null)reports.shutdownNow();c=statusRequest;if(c!=null)c.disconnect();
        PowerManager.WakeLock lock=wake;if(lock!=null)synchronized(lock){if(lock.isHeld())lock.release();}super.onDestroy();
    }
}
