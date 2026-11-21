package com.kisapilot.kisaagent;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.*;
import android.hardware.camera2.*;
import android.hardware.*;
import android.media.*;
import android.os.*;
import android.util.Size;
import android.view.Surface;
import androidx.core.app.NotificationCompat;
import java.nio.ByteBuffer;
import java.util.*;

/** Automatic local camera trial. No network or vehicle-control output. */
public final class CameraTrialService extends Service {
    static volatile Frame latest;
    static volatile PreviewFrame previewFrame;
    // Published bitmaps are immutable and shared by preview and the single analysis task.
    static final class Pose {
        final float[] gravity;final long elapsed;
        Pose(float[] gravity){this.gravity=gravity.clone();elapsed=SystemClock.elapsedRealtime();}
    }
    static final class PreviewFrame {
        final Bitmap bitmap;final long elapsed;final int rotation,degrees,sensor;
        final Pose pose;final GroundDistance.Lens lens;
        PreviewFrame(Bitmap bitmap,int rotation,int sensor,Pose pose,GroundDistance.Lens lens){
            this.bitmap=bitmap;elapsed=SystemClock.elapsedRealtime();this.rotation=rotation;this.sensor=sensor;
            degrees=(sensor-rotation*90+360)%360;this.pose=pose;this.lens=lens;
        }
    }
    static volatile boolean running;
    static volatile String status="중지됨";
    static volatile int displayRotation;
    static volatile int cameraHeightCm=150;
    static final class Frame {
        final long elapsed;final int rotation;final String signal,distance;
        final float[] brakeBox,signalBox;final List<RoadObjectDetector.Box> boxes;
        final StopLineGeometry.Candidate stopLine;
        Frame(PreviewFrame source,RoadObjectDetector.Result objects){
            elapsed=source.elapsed;rotation=source.rotation;boxes=objects.boxes;
            brakeBox=objects.vehicle;signalBox=objects.signal;signal=objects.signalText();stopLine=objects.stopLine;
            if(cameraHeightCm<30)distance="높이 설정 필요";
            else if(stopLine==null)distance="정지선 후보 없음";
            else if(source.pose==null || Math.abs(source.elapsed-source.pose.elapsed)>500)distance="자세 정보 대기";
            else if(source.lens==null)distance="렌즈 정보 없음";
            else{
                float[] line=stopLine.line;
                // Use the candidate at the optical center column to reduce lateral distance bias.
                double y=line[1]+(line[3]-line[1])*(.5-line[0])/(line[2]-line[0]);
                double metres=GroundDistance.metres(.5,y,source.degrees,source.sensor,source.lens,source.pose.gravity,cameraHeightCm/100.0);
                distance=Double.isFinite(metres)?String.format(java.util.Locale.KOREA,"약 %.1f m · 추정",metres):"추정 불가";
            }
        }
    }
    private SensorManager sensorManager;
    private volatile Pose pose;
    private GroundDistance.Lens lens;
    private CameraCharacteristics characteristics;
    private double outputAspect;
    private final SensorEventListener gravityListener=new SensorEventListener(){
        @Override public void onSensorChanged(SensorEvent event){pose=new Pose(event.values);}
        @Override public void onAccuracyChanged(Sensor sensor,int accuracy){}
    };
    private HandlerThread thread;
    private Handler worker;
    private HandlerThread analysisThread;
    private Handler analysisWorker;
    private final java.util.concurrent.atomic.AtomicBoolean analyzing=new java.util.concurrent.atomic.AtomicBoolean();
    private long lastAnalysis;
    private RoadObjectDetector detector;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private volatile boolean stopping;
    private int sensor;
    private long lastFrame;
    @Override public IBinder onBind(Intent i){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent!=null && "stop".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if(running)return START_NOT_STICKY;
        if(checkSelfPermission(android.Manifest.permission.CAMERA)!=android.content.pm.PackageManager.PERMISSION_GRANTED){status="카메라 권한 필요";stopSelf();return START_NOT_STICKY;}
        stopping=false;running=true;latest=null;previewFrame=null;status="카메라 연결 중";
        cameraHeightCm=getSharedPreferences("camera_trial",MODE_PRIVATE).getInt("height_cm",150);
        try {
            getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("camera_trial","카메라 시험",NotificationManager.IMPORTANCE_LOW));
            PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,CameraTrialActivity.class),PendingIntent.FLAG_IMMUTABLE);
            PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,CameraTrialService.class).setAction("stop"),PendingIntent.FLAG_IMMUTABLE);
            Notification notice=new NotificationCompat.Builder(this,"camera_trial").setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("카메라 시험 실행 중").setContentText("신호등·정지선 후보 자동 검출 · C4 전송 없음")
                .setContentIntent(open).addAction(0,"카메라 중지",stop).setOngoing(true).build();
            if(Build.VERSION.SDK_INT>=29)startForeground(5010,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);else startForeground(5010,notice);
            analysisThread=new HandlerThread("camera-analysis",android.os.Process.THREAD_PRIORITY_BACKGROUND);
            analysisThread.start();analysisWorker=new Handler(analysisThread.getLooper());
            thread=new HandlerThread("camera-trial");thread.start();worker=new Handler(thread.getLooper());
            sensorManager=getSystemService(SensorManager.class);
            Sensor gravity=sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
            if(gravity!=null)sensorManager.registerListener(gravityListener,gravity,SensorManager.SENSOR_DELAY_GAME,worker);
            worker.post(this::open);
        } catch(Exception e){fail(e.getMessage());}
        return START_NOT_STICKY;
    }
    private void fail(String error){status="카메라 중단: "+error;stopSelf();}
    private void open(){
        try {
            CameraManager manager=getSystemService(CameraManager.class);
            String id=null; CameraCharacteristics chars=null;
            for(String candidate:manager.getCameraIdList()){
                CameraCharacteristics c=manager.getCameraCharacteristics(candidate);
                if(Integer.valueOf(CameraCharacteristics.LENS_FACING_BACK).equals(c.get(CameraCharacteristics.LENS_FACING))){id=candidate;chars=c;break;}
            }
            if(id==null)throw new IllegalStateException("후면 카메라 없음");
            sensor=chars.get(CameraCharacteristics.SENSOR_ORIENTATION);characteristics=chars;
            Size[] sizes=chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.YUV_420_888);
            if(sizes==null || sizes.length==0)throw new IllegalStateException("YUV 카메라 출력 지원 안 함");
            Size size=Collections.min(Arrays.asList(sizes),Comparator.comparingLong(s->Math.abs((long)s.getWidth()*s.getHeight()-640L*480)));
            outputAspect=(double)size.getWidth()/size.getHeight();
            reader=ImageReader.newInstance(size.getWidth(),size.getHeight(),ImageFormat.YUV_420_888,3);
            reader.setOnImageAvailableListener(r->{
                try(Image image=r.acquireLatestImage()){
                    if(image==null || stopping || SystemClock.elapsedRealtime()-lastFrame<33)return;
                    lastFrame=SystemClock.elapsedRealtime();
                    int rotation=displayRotation;
                    PreviewFrame frame=new PreviewFrame(convert(image,rotation),rotation,sensor,pose,lens);
                    if(stopping)return;
                    previewFrame=frame;
                    // No backlog: while inference is busy, only the preview receives new images.
                    if(frame.elapsed-lastAnalysis>=200 && analyzing.compareAndSet(false,true)){
                        lastAnalysis=frame.elapsed;
                        if(!analysisWorker.post(()->analyze(frame)))analyzing.set(false);
                    }
                } catch(LinkageError e){if(!stopping)fail("이 기기에서 자동 검출 라이브러리를 실행할 수 없습니다.");}
                catch(Exception e){if(!stopping)fail(e.getMessage());}
            },worker);
            if(checkSelfPermission(android.Manifest.permission.CAMERA)!=android.content.pm.PackageManager.PERMISSION_GRANTED)throw new SecurityException("카메라 권한이 해제되었습니다.");
            manager.openCamera(id,new CameraDevice.StateCallback(){
                @Override public void onOpened(CameraDevice c){if(stopping){c.close();return;}camera=c;configure();}
                @Override public void onDisconnected(CameraDevice c){c.close();if(!stopping)fail("다른 앱이 카메라를 사용하거나 장치 연결이 끊겼습니다.");}
                @Override public void onError(CameraDevice c,int error){c.close();if(!stopping)fail("장치 오류 "+error);}
            },worker);
        }catch(Exception e){fail(e.getMessage());}
    }
    private void analyze(PreviewFrame frame){
        try{
            if(stopping || frame.rotation!=displayRotation)return;
            if(detector==null)detector=new RoadObjectDetector(this);
            RoadObjectDetector.Result objects=detector.detect(frame.bitmap);
            if(!stopping && frame.rotation==displayRotation){
                latest=new Frame(frame,objects);
                status="자동 검출 시험 · 내 차선 판별 아님";
            }
        }catch(LinkageError e){if(!stopping)fail("이 기기에서 자동 검출 라이브러리를 실행할 수 없습니다.");}
        catch(Exception e){if(!stopping)fail(e.getMessage());}
        finally{analyzing.set(false);}
    }
    private void configure(){
        try {
            camera.createCaptureSession(Collections.singletonList(reader.getSurface()),new CameraCaptureSession.StateCallback(){
                @Override public void onConfigured(CameraCaptureSession s){
                    if(stopping){s.close();return;}session=s;
                    try {
                        CaptureRequest.Builder request=camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        request.addTarget(reader.getSurface());request.set(CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_AUTO);
                        request.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
                        s.setRepeatingRequest(request.build(),new CameraCaptureSession.CaptureCallback(){
                            @Override public void onCaptureCompleted(CameraCaptureSession session,CaptureRequest request,TotalCaptureResult result){
                                Float focal=result.get(CaptureResult.LENS_FOCAL_LENGTH);
                                Rect crop=result.get(CaptureResult.SCALER_CROP_REGION);
                                android.util.SizeF physical=characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
                                Size pixels=characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
                                if(focal!=null && crop!=null && physical!=null && pixels!=null)
                                    lens=GroundDistance.lens(focal,physical.getWidth(),physical.getHeight(),pixels.getWidth(),pixels.getHeight(),
                                        crop.left,crop.top,crop.right,crop.bottom,outputAspect);
                            }
                        },worker);
                    }catch(Exception e){fail(e.getMessage());}
                }
                @Override public void onConfigureFailed(CameraCaptureSession s){fail("카메라 세션 구성 실패");}
            },worker);
        }catch(Exception e){fail(e.getMessage());}
    }
    private Bitmap convert(Image image,int rotation){
        int w=Math.min(640,image.getWidth()),h=Math.max(1,image.getHeight()*w/image.getWidth());
        int[] rgb=new int[w*h]; Image.Plane[] planes=image.getPlanes();
        ByteBuffer y=planes[0].getBuffer(),u=planes[1].getBuffer(),v=planes[2].getBuffer();
        for(int row=0;row<h;row++)for(int col=0;col<w;col++){
            int sx=col*image.getWidth()/w,sy=row*image.getHeight()/h;
            int yy=(y.get(y.position()+sy*planes[0].getRowStride()+sx*planes[0].getPixelStride())&255)-16;
            int uu=(u.get(u.position()+(sy/2)*planes[1].getRowStride()+(sx/2)*planes[1].getPixelStride())&255)-128;
            int vv=(v.get(v.position()+(sy/2)*planes[2].getRowStride()+(sx/2)*planes[2].getPixelStride())&255)-128;
            int r=(298*Math.max(0,yy)+409*vv+128)>>8,g=(298*Math.max(0,yy)-100*uu-208*vv+128)>>8,b=(298*Math.max(0,yy)+516*uu+128)>>8;
            rgb[row*w+col]=Color.rgb(Math.max(0,Math.min(255,r)),Math.max(0,Math.min(255,g)),Math.max(0,Math.min(255,b)));
        }
        Bitmap base=Bitmap.createBitmap(rgb,w,h,Bitmap.Config.ARGB_8888);
        int degrees=(sensor-rotation*90+360)%360;
        if(degrees==0)return base;
        Matrix rotate=new Matrix();rotate.postRotate(degrees);
        Bitmap result=Bitmap.createBitmap(base,0,0,w,h,rotate,true);if(result!=base)base.recycle();return result;
    }
    @Override public void onDestroy(){
        stopping=true;running=false;latest=null;previewFrame=null;pose=null;
        if(sensorManager!=null)sensorManager.unregisterListener(gravityListener);
        if(!status.startsWith("카메라 중단"))status="중지됨";
        if(worker!=null)worker.post(()->{
            if(session!=null)session.close();if(camera!=null)camera.close();if(reader!=null)reader.close();
            if(thread!=null)thread.quitSafely();
        });
        if(analysisWorker!=null)analysisWorker.post(()->{
            if(detector!=null)detector.close();if(analysisThread!=null)analysisThread.quitSafely();
        });
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
}
