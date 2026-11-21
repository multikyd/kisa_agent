package com.kisapilot.kisaagent;
import android.content.Context;
import android.graphics.*;
import ai.onnxruntime.*;
import java.io.*;
import java.nio.FloatBuffer;
import java.util.*;
/** Experimental, local Korean signal model. No vehicle-control output. */
final class KoreanSignalDetector implements AutoCloseable {
 private final OrtEnvironment environment;
 private final OrtSession session;
 private final Bitmap inputBitmap=Bitmap.createBitmap(640,640,Bitmap.Config.ARGB_8888);
 private final int[] pixels=new int[640*640];
 private final float[] tensor=new float[3*640*640];
 KoreanSignalDetector(Context context)throws Exception{
  environment=OrtEnvironment.getEnvironment();byte[] bytes;
  try(InputStream in=context.getAssets().open("korean_signal.onnx");ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);bytes=out.toByteArray();}
  try(OrtSession.SessionOptions options=new OrtSession.SessionOptions()){options.setIntraOpNumThreads(2);options.setInterOpNumThreads(1);session=environment.createSession(bytes,options);}
  TensorInfo info=(TensorInfo)session.getInputInfo().get("images").getInfo();
  if(!Arrays.equals(info.getShape(),new long[]{1,3,640,640})){session.close();throw new IOException("Unexpected signal model input");}
 }
 List<RoadObjectDetector.Box> detect(Bitmap image)throws Exception{
  TrafficSignalDecoder.Letterbox pad=new TrafficSignalDecoder.Letterbox(image.getWidth(),image.getHeight());
  Canvas canvas=new Canvas(inputBitmap);canvas.drawColor(Color.rgb(114,114,114));
  canvas.drawBitmap(image,null,new Rect(pad.left,pad.top,pad.left+pad.width,pad.top+pad.height),new Paint(Paint.FILTER_BITMAP_FLAG));
  inputBitmap.getPixels(pixels,0,640,0,0,640,640);int plane=640*640;
  for(int i=0;i<plane;i++){int rgb=pixels[i];tensor[i]=((rgb>>16)&255)/255f;tensor[plane+i]=((rgb>>8)&255)/255f;tensor[2*plane+i]=(rgb&255)/255f;}
  try(OnnxTensor input=OnnxTensor.createTensor(environment,FloatBuffer.wrap(tensor),new long[]{1,3,640,640});OrtSession.Result result=session.run(Collections.singletonMap("images",input))){
   float[][][] output=(float[][][])result.get(0).getValue();List<RoadObjectDetector.Box> boxes=new ArrayList<>();
   for(TrafficSignalDecoder.Detection d:TrafficSignalDecoder.decode(output[0],image.getWidth(),image.getHeight()))boxes.add(new RoadObjectDetector.Box(d.roi,"traffic light:"+TrafficSignalDecoder.LABELS[d.label],d.score));return boxes;
  }
 }
 @Override public void close(){try{session.close();}catch(OrtException ignored){}inputBitmap.recycle();}
}
