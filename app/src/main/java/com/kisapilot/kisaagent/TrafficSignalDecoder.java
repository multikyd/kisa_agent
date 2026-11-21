package com.kisapilot.kisaagent;
import java.util.*;
/** Decodes the verified [1,8,8400] Korean YOLOv8 output. */
final class TrafficSignalDecoder {
 static final String[] LABELS={"green","left","red","yellow"};
 static final float THRESHOLD=.35f;
 static final class Detection { final float[] roi;final int label;final float score;Detection(float[] b,int c,float s){roi=b;label=c;score=s;} }
 static final class Letterbox {
  final int width,height,left,top;final float scale;
  Letterbox(int w,int h){if(w<1||h<1)throw new IllegalArgumentException("Invalid dimensions");scale=Math.min(640f/w,640f/h);width=Math.round(w*scale);height=Math.round(h*scale);left=(640-width)/2;top=(640-height)/2;}
 }
 static List<Detection> decode(float[][] data,int width,int height){
  if(data.length!=8)throw new IllegalArgumentException("Expected 8 output channels");int count=data[0].length;
  for(float[] channel:data)if(channel.length!=count)throw new IllegalArgumentException("Mismatched channels");
  Letterbox pad=new Letterbox(width,height);List<Detection> candidates=new ArrayList<>();
  for(int i=0;i<count;i++){
   int cls=0;float score=data[4][i];for(int c=1;c<4;c++)if(data[4+c][i]>score){score=data[4+c][i];cls=c;}
   if(!Float.isFinite(score)||score<THRESHOLD||score>1)continue;
   float x=data[0][i],y=data[1][i],w=data[2][i],h=data[3][i];
   if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(w)||!Float.isFinite(h)||w<=0||h<=0)continue;
   float[] b={(x-w/2-pad.left)/pad.scale/width,(y-h/2-pad.top)/pad.scale/height,(x+w/2-pad.left)/pad.scale/width,(y+h/2-pad.top)/pad.scale/height};
   candidates.add(new Detection(b,cls,score));
  }
  candidates.sort((a,b)->Float.compare(b.score,a.score));if(candidates.size()>300)candidates=new ArrayList<>(candidates.subList(0,300));
  List<Detection> selected=new ArrayList<>();
  for(Detection c:candidates){boolean overlap=false;for(Detection p:selected)if(c.label==p.label&&iou(c.roi,p.roi)>.45f){overlap=true;break;}if(!overlap){selected.add(c);if(selected.size()>=50)break;}}
  List<Detection> result=new ArrayList<>();for(Detection d:selected){float[] b=d.roi.clone();for(int i=0;i<4;i++)b[i]=Math.max(0,Math.min(1,b[i]));if(b[2]>b[0]&&b[3]>b[1])result.add(new Detection(b,d.label,d.score));}return result;
 }
 private static float iou(float[] a,float[] b){float area=Math.max(0,Math.min(a[2],b[2])-Math.max(a[0],b[0]))*Math.max(0,Math.min(a[3],b[3])-Math.max(a[1],b[1]));float union=(a[2]-a[0])*(a[3]-a[1])+(b[2]-b[0])*(b[3]-b[1])-area;return union>0?area/union:0;}
 static String korean(String label){switch(label){case "green":return "초록";case "left":return "좌회전";case "red":return "빨강";case "yellow":return "노랑";default:return "판단 불가";}}
}
