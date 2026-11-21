package com.kisapilot.kisaagent;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** The parameter screen's bookmark destination/configuration editor. Secrets are never persisted on the phone. */
public class BookmarkServerActivity extends AppCompatActivity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private LinearLayout content,serverFields;
    private Spinner destination,auth;
    private EditText host,port,user,directory,rate,password;
    private TextView status,keyLabel,phoneLabel;
    private String keyName="",connectedHost="";
    private JSONObject loaded=new JSONObject();
    private boolean busy=false,closing=false;
    private final boolean korean="ko".equals(Locale.getDefault().getLanguage());
    private String t(String ko,String en){return korean?ko:en;}
    private final ActivityResultLauncher<String[]> keyPicker=registerForActivityResult(new ActivityResultContracts.OpenDocument(),uri->{if(uri!=null)readKey(uri);});
    private final ActivityResultLauncher<Uri> folderPicker=registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(),uri->{
        if(uri==null)return;
        try {
            getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            AutoBookmarkService.prefs(this).edit().putString("tree",uri.toString()).commit();
            phoneLabel.setText(uri.toString());
            selectDestination("phone");
        }catch(Exception e){showError("PHONE_FOLDER_PERMISSION");}
    });
    private final ActivityResultLauncher<String> notifications=registerForActivityResult(new ActivityResultContracts.RequestPermission(),granted->folderPicker.launch(null));

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        try{connectedHost=DeviceAddress.normalize(HomeActivity.serverIp);}catch(Exception e){Toast.makeText(this,R.string.msg_connect_first,Toast.LENGTH_LONG).show();finish();return;}
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(20, 16, 20, 30);
        scroll.addView(content);
        ScreenInsets.setContentWithToolbar(this, scroll, t("북마크 저장 설정", "Bookmark storage settings"));
        label(content,t("저장 대상 · 둘 중 하나만 사용","Save destination · one at a time"));
        destination=spinner(content,new String[]{t("폰에 저장 (기존 방식)","Save on phone (existing mode)"),t("서버 SFTP 저장","Save to SFTP server")});
        button(content,t("선택한 저장 방식 적용","Apply selected destination"),()->selectDestination(destination.getSelectedItemPosition()==0?"phone":"server"));
        phoneLabel=label(content,AutoBookmarkService.prefs(this).getString("tree",t("폰 저장 폴더 미선택","No phone folder selected")));
        button(content,t("폰 저장 폴더 선택 / 변경","Choose / change phone folder"),()->{if(Build.VERSION.SDK_INT>=33)notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS);else folderPicker.launch(null);});
        button(content,t("폰 자동 저장 중지","Pause phone auto-save"),()->{AutoBookmarkService.disable(this);status.setText(t("폰 자동 저장 중지 · 받은 파일과 대기열 유지","Phone auto-save paused · files and queue retained"));});
        serverFields=new LinearLayout(this);serverFields.setOrientation(LinearLayout.VERTICAL);content.addView(serverFields);
        label(serverFields,t("서버 SFTP 설정 (C4에 저장)","SFTP configuration (stored on C4)"));
        host=field(serverFields,t("서버 주소","Server host"),"",false);port=field(serverFields,t("포트","Port"),"22",true);
        user=field(serverFields,t("사용자 이름","Username"),"",false);directory=field(serverFields,t("서버 저장 폴더","Remote folder"),"/home/kyd/realdata",false);
        rate=field(serverFields,t("전송 상한 (Kbit/s)","Rate limit (Kbit/s)"),"4096",true);
        label(serverFields,t("인증 방식 · 기본은 키파일","Authentication · key file by default"));
        auth=spinner(serverFields,new String[]{t("키파일 (기본)","Key file (default)"),t("서버 비밀번호","Server password")});
        keyLabel=label(serverFields,t("등록된 키 없음","No registered key"));
        button(serverFields,t("키파일 가져오기 · PPK / OpenSSH / PEM","Import key · PPK / OpenSSH / PEM"),()->keyPicker.launch(new String[]{"*/*"}));
        button(serverFields,t("C4에 등록된 키 선택","Select an existing C4 key"),this::chooseKey);
        password=field(serverFields,t("서버 비밀번호 · 비워두면 기존 값 유지","Server password · leave blank to keep"),"",false);
        password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);password.setSaveEnabled(false);
        label(serverFields,t("PPK 암호는 파일을 가져올 때 별도로 입력합니다. 비밀번호와 개인키 내용은 조회 화면으로 돌려받지 않습니다.","PPK passphrases are entered during import. Password and private-key contents are never read back."));
        button(serverFields,t("서버 설정 저장","Save server configuration"),()->task(()->{saveConfig(false);uploadPasswordIfEntered();return t("설정 저장 완료 · 지문 확인 후 연결시험을 실행하세요","Saved · verify the host fingerprint, then test connection");}));
        button(serverFields,t("서버 지문 확인 / 등록","Verify / register host fingerprint"),this::verifyHost);
        button(serverFields,t("연결시험 · 폴더 생성 및 쓰기 확인","Test connection · create folder and test writing"),this::testConnection);
        button(serverFields,t("서버 자동 업로드 켜기","Enable automatic server uploads"),()->task(()->{uploadPasswordAfterSave();saveConfig(true);runOnUiThread(()->{AutoBookmarkService.disable(this);destination.setSelection(1);});return t("SFTP 자동 업로드 켜짐 · 미완료 대기열을 이어 보냅니다","SFTP uploads enabled · pending jobs resume");}));
        button(serverFields,t("서버 자동 업로드 중지","Pause server uploads"),()->selectDestination("server"));
        destination.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onItemSelected(android.widget.AdapterView<?> parent,android.view.View view,int position,long id){serverFields.setVisibility(position==1?android.view.View.VISIBLE:android.view.View.GONE);}
            public void onNothingSelected(android.widget.AdapterView<?> parent){}
        });
        button(serverFields,t("전송 현황 새로고침","Refresh upload status"),()->task(()->{JSONObject report=api("/bookmarks/server/status",null);return t("서버 전송 현황: ","Server upload status: ")+report.toString();}));
        status=label(content,t("C4 설정 조회 중…","Loading C4 configuration…"));
        task(()->{JSONObject data=api("/bookmarks/server/config",null);if(!data.has("auth_modes")||!data.has("save_target"))throw new IOException("C4_UPDATE_REQUIRED");loaded=data;runOnUiThread(()->bind(data));return t("C4 설정을 불러왔습니다","C4 configuration loaded");});
    }
    private TextView label(LinearLayout parent,String text){TextView v=new TextView(this);v.setText(text);v.setTextSize(16);v.setPadding(2,12,2,8);parent.addView(v);return v;}
    private EditText field(LinearLayout parent,String title,String value,boolean numeric){label(parent,title);EditText v=new EditText(this);v.setText(value);v.setSingleLine(true);v.setTextSize(18);v.setInputType(numeric?InputType.TYPE_CLASS_NUMBER:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);parent.addView(v);return v;}
    private Spinner spinner(LinearLayout parent,String[] values){Spinner v=new Spinner(this);ArrayAdapter<String> a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,values);v.setAdapter(a);parent.addView(v);return v;}
    private void button(LinearLayout parent,String text,Runnable action){Button b=new Button(this);b.setText(text);b.setTextSize(16);b.setAllCaps(false);parent.addView(b);b.setOnClickListener(v->{if(!busy)action.run();});}
    private void bind(JSONObject d){host.setText(d.optString("host"));port.setText(d.optString("port","22"));user.setText(d.optString("user"));directory.setText(d.optString("remote_dir").isEmpty()?"/home/kyd/realdata":d.optString("remote_dir"));rate.setText(d.optString("rate_kbps","4096"));auth.setSelection("password".equals(d.optString("auth_mode"))?1:0);keyName=d.optString("key_name");keyLabel.setText(keyName.isEmpty()?t("등록된 키 없음","No registered key"):keyName);password.setHint(d.optBoolean("password_configured")?t("C4에 비밀번호가 저장돼 있습니다","Password is already stored on C4"):t("비밀번호 미등록","Password not registered"));destination.setSelection("server".equals(d.optString("save_target"))?1:0);}
    private JSONObject form(boolean enabled)throws Exception{return new JSONObject().put("host",host.getText().toString().trim()).put("port",port.getText().toString().trim()).put("user",user.getText().toString().trim()).put("remote_dir",directory.getText().toString().trim()).put("rate_kbps",rate.getText().toString().trim()).put("key_name",keyName).put("auth_mode",auth.getSelectedItemPosition()==0?"key":"password").put("enabled",enabled);}
    private JSONObject draft;
    private byte[] enteredPassword;
    private void task(Work work){
        if(busy||closing)return;
        try{draft=form(false);enteredPassword=password.getText().toString().getBytes(StandardCharsets.UTF_8);}catch(Exception e){showError("INVALID_CONFIG");return;}
        busy=true;status.setText(t("처리 중…","Working…"));
        io.execute(()->{try{String message=work.run();runOnUiThread(()->{if(!closing)status.setText(message);});}catch(Exception e){runOnUiThread(()->{if(!closing)showError(e instanceof NumberFormatException?"INVALID_CONFIG":e.getMessage());});}finally{if(enteredPassword!=null)Arrays.fill(enteredPassword,(byte)0);enteredPassword=null;runOnUiThread(()->{busy=false;if(!closing)password.setText("");});}});
    }
    private interface Work {String run()throws Exception;}
    private void saveConfig(boolean enabled)throws Exception {JSONObject config=new JSONObject(draft.toString());config.put("enabled",enabled);config.put("key_name",keyName);config.put("port",Integer.parseInt(config.getString("port")));config.put("rate_kbps",Integer.parseInt(config.getString("rate_kbps")));api("/bookmarks/server/config",config);}
    private void uploadPasswordAfterSave()throws Exception{saveConfig(false);uploadPasswordIfEntered();}
    private void uploadPasswordIfEntered()throws Exception{if(enteredPassword!=null&&enteredPassword.length>0){JSONObject envelope=encrypted(enteredPassword,"password");api("/bookmarks/server/password",envelope);}}
    private void selectDestination(String target){
        task(()->{JSONObject result=api("/bookmarks/server/target",new JSONObject().put("target",target));
            if(target.equals("server")){runOnUiThread(()->AutoBookmarkService.disable(this));}
            else {
                Set<String> pending=new HashSet<>(AutoBookmarkService.prefs(this).getStringSet("pending",Collections.emptySet()));
                JSONArray ids=result.optJSONArray("pending_bookmarks");if(ids!=null)for(int i=0;i<ids.length();i++)pending.add(ids.getString(i));
                AutoBookmarkService.prefs(this).edit().putStringSet("pending",pending).commit();
                runOnUiThread(()->{if(closing)return;try{if(!AutoBookmarkService.prefs(this).getString("tree","").isEmpty())AutoBookmarkService.enable(this);}catch(Exception e){showError("PHONE_SERVICE_START_FAILED");}});
            }
            return target.equals("phone")?t("폰 저장 선택 · 폴더가 없으면 위에서 선택해주세요","Phone storage selected · choose a phone folder if needed"):t("SFTP 선택 · 연결시험 후 자동 업로드를 켜주세요","SFTP selected · test, then enable uploads");});
    }
    private void chooseKey(){task(()->{JSONArray keys=api("/bookmarks/server/keys",null).getJSONArray("keys");String[] names=new String[keys.length()];for(int i=0;i<names.length;i++)names[i]=keys.getJSONObject(i).getString("name");runOnUiThread(()->{if(!closing)new AlertDialog.Builder(this).setTitle(t("C4 키 선택","Select C4 key")).setItems(names,(d,w)->{keyName=names[w];keyLabel.setText(keyName);}).setNegativeButton(android.R.string.cancel,null).show();});return t("키 목록 조회 완료","Key list loaded");});}
    private void readKey(Uri uri){
        try(InputStream in=getContentResolver().openInputStream(uri);ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            if(in==null)throw new IOException("KEY_READ_FAILED");byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){bytes.write(b,0,n);if(bytes.size()>16384)throw new IOException("KEY_TOO_LARGE");}
            byte[] key=bytes.toByteArray();EditText pass=new EditText(this);pass.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);pass.setSaveEnabled(false);pass.setHint(t("PPK 암호 · 없으면 비워두기","PPK passphrase · blank if none"));
            new AlertDialog.Builder(this).setTitle(t("키파일 가져오기","Import key")).setView(pass)
                .setPositiveButton(t("등록","Import"),(d,w)->{char[] phrase=pass.getText().toString().toCharArray();pass.setText("");if(busy||closing){Arrays.fill(key,(byte)0);Arrays.fill(phrase,'\0');return;}task(()->{byte[] converted=null;try{saveConfig(false);converted=PpkKeyConverter.convert(key,phrase);JSONObject body=encrypted(converted,"key").put("name","server-key.openssh");String name=api("/bookmarks/server/key-upload",body).getString("name");keyName=name;draft.put("key_name",name);saveConfig(false);runOnUiThread(()->keyLabel.setText(name));return t("키 등록 완료 · 원본 파일은 변경하지 않았습니다","Key imported · original file unchanged");}finally{Arrays.fill(key,(byte)0);Arrays.fill(phrase,'\0');if(converted!=null)Arrays.fill(converted,(byte)0);}});})
                .setNegativeButton(android.R.string.cancel,(d,w)->Arrays.fill(key,(byte)0)).setOnCancelListener(d->Arrays.fill(key,(byte)0)).show();
        }catch(Exception e){showError("KEY_READ_FAILED");}
    }
    private JSONObject encrypted(byte[] plain,String purpose)throws Exception {
        JSONObject transport=api("/bookmarks/server/key-upload/public?purpose="+purpose,null);
        PublicKey key=KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(transport.getString("public_key"))));
        Cipher cipher=Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        cipher.init(Cipher.ENCRYPT_MODE,key,new OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,PSource.PSpecified.DEFAULT));
        JSONArray chunks=new JSONArray();for(int start=0;start<plain.length;start+=190)chunks.put(Base64.getEncoder().encodeToString(cipher.doFinal(plain,start,Math.min(190,plain.length-start))));
        return new JSONObject().put("nonce",transport.getString("nonce")).put("chunks",chunks);
    }
    private void verifyHost(){task(()->{uploadPasswordAfterSave();JSONObject result=api("/bookmarks/server/host-key",new JSONObject());runOnUiThread(()->{if(!closing)new AlertDialog.Builder(this).setTitle(t("서버 SSH 지문 확인","Verify server SSH fingerprint")).setMessage(draft.optString("host")+":"+draft.optInt("port")+"\n"+result.optString("algorithm")+"\n"+result.optString("fingerprint")+"\n\n"+t("서버의 지문과 일치할 때 등록하세요.","Register only if this matches your server fingerprint."))
                .setPositiveButton(t("지문 등록","Trust fingerprint"),(d,w)->task(()->{api("/bookmarks/server/trust-host",new JSONObject().put("challenge",result.getString("challenge")));return t("서버 지문 등록 완료","Host fingerprint registered");})).setNegativeButton(android.R.string.cancel,null).show();});return t("서버 지문을 확인해주세요","Verify the displayed fingerprint");});}
    private void testConnection(){task(()->{uploadPasswordAfterSave();api("/bookmarks/server/test",new JSONObject());for(int i=0;i<45;i++){Thread.sleep(1500);JSONObject report=api("/bookmarks/server/status",null).getJSONObject("connection_test");String state=report.optString("state");if(state.equals("success"))return t("연결·폴더 생성/확인·쓰기·삭제 시험 성공","Connection, folder preparation, write and delete test succeeded");if(state.equals("error"))throw new IOException(report.optString("error","SFTP_FAILED"));}throw new IOException("CONNECTION_TEST_TIMEOUT");});}
    private JSONObject api(String path,JSONObject body)throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL("http://"+connectedHost+":5005"+path).openConnection();
        c.setConnectTimeout(5000);c.setReadTimeout(15000);
        try {if(body!=null){c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}}
            int code=c.getResponseCode();InputStream stream=code>=400?c.getErrorStream():c.getInputStream();StringBuilder text=new StringBuilder();
            if(stream!=null)try(Reader in=new InputStreamReader(stream,StandardCharsets.UTF_8)){char[] buffer=new char[4096];int n;while((n=in.read(buffer))!=-1){text.append(buffer,0,n);if(text.length()>1024*1024)throw new IOException("RESPONSE_TOO_LARGE");}}
            JSONObject result=new JSONObject(text.toString());if(code>=400)throw new IOException(result.optString("error","C4_HTTP_"+code));return result;
        }finally{c.disconnect();}
    }
    private void showError(String code){status.setText(t("실패: ","Failed: ")+code);}
    @Override protected void onDestroy(){closing=true;io.shutdownNow();if(enteredPassword!=null)Arrays.fill(enteredPassword,(byte)0);super.onDestroy();}
}
