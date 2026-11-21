package com.kisapilot.kisaagent;

import android.content.Context;
import android.graphics.*;
import com.google.mediapipe.framework.image.*;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.objectdetector.*;
import com.google.mediapipe.tasks.components.containers.*;
import java.util.*;

/** Local vehicle/signal localization plus experimental stop-line candidates. */
final class RoadObjectDetector implements AutoCloseable {
    static final String MODEL="efficientdet-lite0-coco+kr-signal-yolov8n+mlsd-tiny";
    static final class Box {
        final float[] roi; final String label; final float score;
        Box(float[] roi,String label,float score){this.roi=roi;this.label=label;this.score=score;}
    }
    static final class Result {
        final List<Box> boxes; final float[] vehicle,signal; final StopLineGeometry.Candidate stopLine;
        String signalText(){StringBuilder text=new StringBuilder();int count=0;for(Box box:boxes)if(box.label.startsWith("traffic light:")){if(count++>0)text.append(" / ");text.append(TrafficSignalDecoder.korean(box.label.substring(14))).append(" ").append(Math.round(box.score*100)).append("% 후보");if(count>=5)break;}return count==0?"후보 없음 · 판단 불가":text.toString();}
        Result(List<Box> boxes,float[] vehicle,float[] signal){this(boxes,vehicle,signal,null);}
        Result(List<Box> boxes,float[] vehicle,float[] signal,StopLineGeometry.Candidate stopLine){this.boxes=Collections.unmodifiableList(boxes);this.vehicle=vehicle;this.signal=signal;this.stopLine=stopLine;}
    }
    private final ObjectDetector detector;
    private final KoreanSignalDetector signals;
    private final StopLineDetector stops;
    RoadObjectDetector(Context context) throws Exception {
        detector=ObjectDetector.createFromOptions(context,ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("efficientdet_lite0.tflite").build())
            .setRunningMode(RunningMode.IMAGE).setMaxResults(20).setScoreThreshold(.35f)
            .setCategoryAllowlist(Arrays.asList("car","truck","bus","motorcycle")).build());
        try{signals=new KoreanSignalDetector(context);}catch(Exception|LinkageError e){detector.close();throw e;}
        try{stops=new StopLineDetector(context);}catch(Exception|LinkageError e){signals.close();detector.close();throw e;}
    }
    Result detect(Bitmap bitmap) throws Exception {
        // MPImage.close() recycles its bitmap. Keep the displayed/saved frame independent.
        MPImage image=new BitmapImageBuilder(bitmap.copy(Bitmap.Config.ARGB_8888,false)).build();
        try {
            List<Box> boxes=new ArrayList<>();
            for(Detection d:detector.detect(image).detections()){
                if(d.categories().isEmpty())continue;
                Category c=d.categories().get(0);RectF r=d.boundingBox();
                float[] roi={Math.max(0,r.left/bitmap.getWidth()),Math.max(0,r.top/bitmap.getHeight()),Math.min(1,r.right/bitmap.getWidth()),Math.min(1,r.bottom/bitmap.getHeight())};
                if(roi[2]<=roi[0] || roi[3]<=roi[1])continue;
                boxes.add(new Box(roi,c.categoryName(),c.score()));
            }
            boxes.addAll(signals.detect(bitmap));
            Result selected=select(boxes);
            return new Result(selected.boxes,selected.vehicle,selected.signal,stops.detect(bitmap));
        }finally{image.close();}
    }
    static Result select(List<Box> boxes){
        Box vehicle=null,signal=null;double vehicleRank=-1,signalRank=-1;
        for(Box box:boxes){
            float[] r=box.roi;float cx=(r[0]+r[2])/2,cy=(r[1]+r[3])/2;
            double rank=(r[2]-r[0])*(r[3]-r[1])*box.score/(1+4*Math.abs(cx-.5));
            if(("traffic light".equals(box.label)||box.label.startsWith("traffic light:"))){if(rank>signalRank){signal=box;signalRank=rank;}}
            else if(("car".equals(box.label)||"truck".equals(box.label)||"bus".equals(box.label)||"motorcycle".equals(box.label))
                    && cx>.15 && cx<.85 && cy>.2 && rank>vehicleRank){vehicle=box;vehicleRank=rank;}
        }
        return new Result(new ArrayList<>(boxes),vehicle==null?null:vehicle.roi.clone(),signal==null?null:signal.roi.clone());
    }
    @Override public void close(){stops.close();signals.close();detector.close();}
}
