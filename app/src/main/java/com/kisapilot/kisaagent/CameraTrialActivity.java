package com.kisapilot.kisaagent;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.view.*;
import java.util.*;

/** Large automatic-only camera view; all readouts stay over the lower camera area. */
public class CameraTrialActivity extends AppCompatActivity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private Preview preview;
    private LinearLayout panel;
    private TextView status,signal,brake,stop,distance;
    private Button start;
    private ActivityResultLauncher<String[]> permissions;
    private final Runnable previewTick=new Runnable(){public void run(){preview.invalidate();handler.postDelayed(this,33);}};
    private final Runnable tick=new Runnable(){public void run(){updateReadouts();handler.postDelayed(this,250);}};
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        if(getSupportActionBar()!=null)getSupportActionBar().hide();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        CameraTrialService.cameraHeightCm=getSharedPreferences("camera_trial",MODE_PRIVATE).getInt("height_cm",150);
        permissions=registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),result->{
            if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)startCamera();
            else toast("카메라 권한이 필요합니다.");
        });
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(Color.BLACK);
        ViewCompat.setOnApplyWindowInsetsListener(root,(v,insets)->{
            androidx.core.graphics.Insets b=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout());
            v.setPadding(b.left,b.top,b.right,b.bottom);return insets;
        });
        preview=new Preview();root.addView(preview,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout top=new LinearLayout(this);top.setOrientation(LinearLayout.VERTICAL);top.setBackgroundColor(0xB8000000);
        LinearLayout controls=new LinearLayout(this);top.addView(controls);
        button(controls,"뒤로",this::finish);
        start=button(controls,"시작",()->{
            if(CameraTrialService.running)stopService(new Intent(this,CameraTrialService.class));
            else permissions.launch(Build.VERSION.SDK_INT>=33?new String[]{Manifest.permission.CAMERA,Manifest.permission.POST_NOTIFICATIONS}:new String[]{Manifest.permission.CAMERA});
        });
        button(controls,"배경",()->{if(CameraTrialService.running)moveTaskToBack(true);else toast("먼저 카메라를 시작해주세요.");});
        button(controls,"높이",this::heightSettings);
        status=text(14,Color.LTGRAY);status.setMaxLines(1);top.addView(status);
        status.setOnClickListener(v->new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("자동 검출 시험")
            .setMessage(CameraTrialService.status+"\n\n신호등은 적용 차선을 판별하지 않습니다.\n브레이크등 ON/OFF 모델은 아직 없습니다.\n정지선은 M-LSD 직선과 흰 띠 조건으로 고른 후보이며, 횡단보도·대시보드를 오인할 수 있습니다.\n높이 입력 후 거리는 카메라 정보·자세 센서와 평탄한 도로 가정으로 추정합니다. 카메라 바로 아래 지점 기준 거리이며 범퍼 기준이 아닙니다.\nC4 전송 없음")
            .setPositiveButton("확인",null).show());
        root.addView(top,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
        panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setBackgroundColor(0xC9000000);panel.setPadding(dp(10),dp(5),dp(10),dp(6));
        signal=text(26,Color.WHITE);signal.setMaxLines(2);
        brake=text(22,Color.LTGRAY);
        stop=text(22,Color.YELLOW);
        distance=text(28,Color.WHITE);
        if(getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE){
            LinearLayout first=new LinearLayout(this),second=new LinearLayout(this);
            first.addView(signal,new LinearLayout.LayoutParams(0,-2,1));first.addView(brake,new LinearLayout.LayoutParams(0,-2,1));
            second.addView(stop,new LinearLayout.LayoutParams(0,-2,1));second.addView(distance,new LinearLayout.LayoutParams(0,-2,1));
            panel.addView(first);panel.addView(second);
        }else{panel.addView(signal);panel.addView(brake);panel.addView(stop);panel.addView(distance);}
        distance.setOnClickListener(v->heightSettings());
        root.addView(panel,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        setContentView(root);ViewCompat.requestApplyInsets(root);updateReadouts();
    }
    private TextView text(int size,int color){TextView v=new TextView(this);v.setTextSize(size);v.setTextColor(color);v.setIncludeFontPadding(false);v.setTypeface(null,android.graphics.Typeface.BOLD);return v;}
    private Button button(LinearLayout row,String label,Runnable action){
        Button b=new Button(this);b.setText(label);b.setTextSize(16);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(2),0,dp(2),0);
        row.addView(b,new LinearLayout.LayoutParams(0,dp(48),1));b.setOnClickListener(v->action.run());return b;
    }
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    private void startCamera(){try{startForegroundService(new Intent(this,CameraTrialService.class));}catch(Exception e){toast("시작 실패: "+e.getMessage());}}
    private void heightSettings(){
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(20),dp(8),dp(20),dp(8));
        TextView note=new TextView(this);note.setText("기본값 150 cm입니다. 실제 측정한 값은 아닙니다.\n지면에서 후면 카메라 렌즈까지 높이를 cm로 입력하세요.\n높이를 모르면 미설정으로 두세요. 거치 높이가 바뀌면 다시 입력하세요.\n기울기는 자세 센서로 읽습니다. 경사·진동·렌즈 오차가 있는 시험용 추정 거리입니다.");content.addView(note);
        EditText input=new EditText(this);input.setInputType(InputType.TYPE_CLASS_NUMBER);input.setHint("높이 설정 필요 (30~300 cm)");input.setTextSize(22);
        if(CameraTrialService.cameraHeightCm>=30)input.setText(String.valueOf(CameraTrialService.cameraHeightCm));content.addView(input);
        androidx.appcompat.app.AlertDialog dialog=new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("카메라 지면 높이")
            .setView(content).setPositiveButton("저장",null).setNeutralButton("미설정",(d,w)->saveHeight(0)).setNegativeButton("취소",null).create();
        dialog.setOnShowListener(d->dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener(v->{
            int height;try{height=Integer.parseInt(input.getText().toString().trim());}catch(NumberFormatException e){input.setError("cm 단위 숫자를 입력하세요.");return;}
            if(height<30 || height>300){input.setError("30~300 cm 범위로 입력하세요.");return;}saveHeight(height);dialog.dismiss();
        }));dialog.show();
    }
    private void saveHeight(int cm){
        CameraTrialService.cameraHeightCm=cm;getSharedPreferences("camera_trial",MODE_PRIVATE).edit().putInt("height_cm",cm).apply();
        CameraTrialService.latest=null;updateReadouts();
    }
    private boolean fresh(CameraTrialService.Frame f){return f!=null && CameraTrialService.running && f.rotation==CameraTrialService.displayRotation && SystemClock.elapsedRealtime()-f.elapsed<=1000;}
    private void updateReadouts(){
        start.setText(CameraTrialService.running?"중지":"시작");status.setText(CameraTrialService.status);
        CameraTrialService.Frame f=CameraTrialService.latest;
        if(!fresh(f)){
            signal.setText("신호등 · 판단 대기");signal.setTextColor(Color.WHITE);
            brake.setText("브레이크등 · ON/OFF 미지원");
            stop.setText("정지선 · 판단 대기");
            distance.setText(CameraTrialService.cameraHeightCm<30?"거리 · 높이 설정 필요":"거리 · 판단 대기");return;
        }
        LinkedHashSet<String> states=new LinkedHashSet<>();int color=Color.WHITE;
        for(RoadObjectDetector.Box b:f.boxes)if(b.label.startsWith("traffic light:")){
            String state=b.label.substring(14);states.add(TrafficSignalDecoder.korean(state));
            if(state.equals("red"))color=Color.RED;else if(state.equals("yellow") && color!=Color.RED)color=Color.YELLOW;else if(color==Color.WHITE)color=Color.GREEN;
        }
        signal.setText(states.isEmpty()?"신호등 · 후보 없음":"신호등 후보 · "+android.text.TextUtils.join(" / ",states));signal.setTextColor(color);
        brake.setText("브레이크등 · ON/OFF 미지원");
        stop.setText(f.stopLine==null?"정지선 · 후보 없음":"정지선 · 흰 가로선 후보");
        distance.setText("거리 · "+f.distance);
    }
    @Override protected void onResume(){
        super.onResume();int rotation=getWindowManager().getDefaultDisplay().getRotation();
        if(rotation!=CameraTrialService.displayRotation){CameraTrialService.latest=null;CameraTrialService.previewFrame=null;}
        CameraTrialService.displayRotation=rotation;handler.post(tick);handler.post(previewTick);
        new WindowInsetsControllerCompat(getWindow(),getWindow().getDecorView()).hide(WindowInsetsCompat.Type.statusBars());
    }
    @Override protected void onPause(){handler.removeCallbacksAndMessages(null);super.onPause();}
    private final class Preview extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        private final RectF fit=new RectF();
        Preview(){super(CameraTrialActivity.this);setBackgroundColor(Color.BLACK);}
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);CameraTrialService.PreviewFrame live=CameraTrialService.previewFrame;
            if(live==null || live.rotation!=CameraTrialService.displayRotation)return;
            Bitmap bitmap=live.bitmap;float scale=Math.min((float)getWidth()/bitmap.getWidth(),(float)getHeight()/bitmap.getHeight());
            float w=bitmap.getWidth()*scale,h=bitmap.getHeight()*scale;fit.set((getWidth()-w)/2,(getHeight()-h)/2,(getWidth()+w)/2,(getHeight()+h)/2);
            canvas.drawBitmap(bitmap,null,fit,paint);
            // Position readouts at the image bottom, over the dashboard rather than a separate footer.
            float translation=-(getHeight()-fit.bottom);if(Math.abs(panel.getTranslationY()-translation)>1)panel.setTranslationY(translation);
            CameraTrialService.Frame f=CameraTrialService.latest;if(!fresh(f))return;
            for(RoadObjectDetector.Box b:f.boxes){
                String label=b.label;int color=Color.rgb(100,190,255);
                if(label.startsWith("traffic light:")){
                    String state=label.substring(14);color=state.equals("red")?Color.RED:state.equals("yellow")?Color.YELLOW:state.equals("left")?Color.CYAN:Color.GREEN;
                    label=TrafficSignalDecoder.korean(state)+" 후보 "+Math.round(b.score*100)+"%";
                }else label="차량 후보";
                box(canvas,b.roi,color);label(canvas,label,fit.left+b.roi[0]*fit.width(),fit.top+b.roi[1]*fit.height(),color);
            }
            if(f.stopLine!=null){float[] line=f.stopLine.line;
                float x1=fit.left+line[0]*fit.width(),y1=fit.top+line[1]*fit.height(),x2=fit.left+line[2]*fit.width(),y2=fit.top+line[3]*fit.height();
                paint.setStyle(Paint.Style.STROKE);paint.setColor(Color.BLACK);paint.setStrokeWidth(dp(8));canvas.drawLine(x1,y1,x2,y2,paint);
                paint.setColor(Color.YELLOW);paint.setStrokeWidth(dp(4));canvas.drawLine(x1,y1,x2,y2,paint);
                label(canvas,"정지선 후보",x1,Math.min(y1,y2)-dp(5),Color.YELLOW);
            }
        }
        private void box(Canvas c,float[] roi,int color){paint.setStyle(Paint.Style.STROKE);paint.setColor(color);paint.setStrokeWidth(dp(2));c.drawRect(fit.left+roi[0]*fit.width(),fit.top+roi[1]*fit.height(),fit.left+roi[2]*fit.width(),fit.top+roi[3]*fit.height(),paint);}
        private void label(Canvas c,String text,float x,float y,int color){
            paint.setTypeface(Typeface.DEFAULT_BOLD);paint.setTextSize(18*getResources().getDisplayMetrics().scaledDensity);paint.setStyle(Paint.Style.FILL);
            float width=paint.measureText(text),height=paint.getFontSpacing();x=Math.max(0,Math.min(x,getWidth()-width-dp(8)));y=Math.max(height,Math.min(y,getHeight()-dp(4)));
            paint.setColor(0xD9000000);c.drawRect(x,y-height,x+width+dp(6),y+dp(3),paint);paint.setColor(color);c.drawText(text,x+dp(3),y,paint);
        }
    }
}
