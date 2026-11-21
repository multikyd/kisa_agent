package com.kisapilot.kisaagent;

import android.content.Context;
import android.graphics.*;
import ai.onnxruntime.*;
import java.io.*;
import java.nio.FloatBuffer;
import java.util.*;

/** M-LSD generic line model, with experimental white-band candidate selection. */
final class StopLineDetector implements AutoCloseable {
    private final OrtEnvironment env=OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final Bitmap scaled=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888);
    private final int[] pixels=new int[320*320];
    private final float[] input=new float[320*320*4];
    private StopLineGeometry.Candidate previous;
    private int stable;
    StopLineDetector(Context context)throws Exception{
        byte[] bytes;
        try(InputStream in=context.getAssets().open("mlsd_320_tiny.onnx");ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);bytes=out.toByteArray();
        }
        try(OrtSession.SessionOptions options=new OrtSession.SessionOptions()){
            options.setIntraOpNumThreads(2);options.setInterOpNumThreads(1);session=env.createSession(bytes,options);
        }
        if(!Arrays.equals(((TensorInfo)session.getInputInfo().get("input_image_with_alpha:0").getInfo()).getShape(),new long[]{1,320,320,4})){
            session.close();throw new IOException("Unexpected M-LSD input");
        }
    }
    StopLineGeometry.Candidate detect(Bitmap bitmap)throws Exception{
        new Canvas(scaled).drawBitmap(bitmap,null,new Rect(0,0,320,320),new Paint(Paint.FILTER_BITMAP_FLAG));
        scaled.getPixels(pixels,0,320,0,0,320,320);
        // Match the published ONNX/OpenCV reference: B,G,R,1 with internal normalization.
        for(int i=0;i<pixels.length;i++){int c=pixels[i];input[4*i]=c&255;input[4*i+1]=(c>>8)&255;input[4*i+2]=(c>>16)&255;input[4*i+3]=1;}
        StopLineGeometry.Candidate candidate;
        try(OnnxTensor tensor=OnnxTensor.createTensor(env,FloatBuffer.wrap(input),new long[]{1,320,320,4});
                OrtSession.Result result=session.run(Collections.singletonMap("input_image_with_alpha:0",tensor))){
            int[][] points=((int[][][])result.get("Identity").get().getValue())[0];
            float[] scores=((float[][])result.get("Identity_1").get().getValue())[0];
            float[][][] offsets=((float[][][][])result.get("Identity_2").get().getValue())[0];
            int w=bitmap.getWidth(),h=bitmap.getHeight();int[] original=new int[w*h];bitmap.getPixels(original,0,w,0,0,w,h);
            candidate=StopLineGeometry.select(StopLineGeometry.decode(points,scores,offsets),original,w,h);
        }
        if(candidate==null){previous=null;stable=0;return null;}
        if(previous!=null && Math.abs(candidate.line[1]-previous.line[1])<.035f && Math.abs(candidate.line[3]-previous.line[3])<.035f)stable++;
        else stable=1;
        previous=candidate;return stable>=2?candidate:null;
    }
    @Override public void close(){try{session.close();}catch(OrtException ignored){}scaled.recycle();}
}
