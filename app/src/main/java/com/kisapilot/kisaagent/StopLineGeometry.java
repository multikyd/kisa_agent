package com.kisapilot.kisaagent;

import java.util.*;

/** Generic line-model decoding and experimental transverse white-band filtering. */
final class StopLineGeometry {
    static final class Line {
        final float x1,y1,x2,y2,score;
        Line(float x1,float y1,float x2,float y2,float score){
            if(x1>x2){float t=x1;x1=x2;x2=t;t=y1;y1=y2;y2=t;}
            this.x1=x1;this.y1=y1;this.x2=x2;this.y2=y2;this.score=score;
        }
        float y(float x){return y1+(y2-y1)*(x-x1)/(x2-x1);}
    }
    static final class Candidate {
        final float[] line; final float score;
        Candidate(float[] line,float score){this.line=line;this.score=score;}
    }
    static List<Line> decode(int[][] points,float[] scores,float[][][] offsets){
        List<Line> lines=new ArrayList<>();
        for(int i=0;i<Math.min(points.length,scores.length);i++){
            if(points[i].length<2 || !Float.isFinite(scores[i]) || scores[i]<.15f)continue;
            int y=points[i][0],x=points[i][1];
            if(y<0 || y>=160 || x<0 || x>=160 || y>=offsets.length || x>=offsets[y].length)continue;
            float[] d=offsets[y][x];if(d.length<4)continue;
            boolean finite=true;for(int j=0;j<4;j++)finite&=Float.isFinite(d[j]);if(!finite)continue;
            if(Math.hypot(d[0]-d[2],d[1]-d[3])<10)continue;
            float x1=(x+d[0])/160f,y1=(y+d[1])/160f,x2=(x+d[2])/160f,y2=(y+d[3])/160f;
            if(x1<0 || x1>1 || x2<0 || x2>1 || y1<0 || y1>1 || y2<0 || y2>1)continue;
            lines.add(new Line(x1,y1,x2,y2,scores[i]));
        }
        return lines;
    }
    static Candidate select(List<Line> source,int[] pixels,int w,int h){
        if(w<=0 || h<=0 || pixels.length!=(long)w*h)return null;
        List<Line> lines=new ArrayList<>();
        for(Line l:source){
            float mid=(l.y1+l.y2)/2;
            if(!Float.isFinite(l.score) || l.x2-l.x1<.25f || mid<.40f || mid>.86f || l.x1>.48f || l.x2<.52f)continue;
            if(Math.abs((l.y2-l.y1)*h)> (l.x2-l.x1)*w*.28f)continue;
            lines.add(l);
        }
        List<Candidate> bands=new ArrayList<>();
        for(int i=0;i<lines.size();i++)for(int j=i+1;j<lines.size();j++){
            Line a=lines.get(i),b=lines.get(j);
            float left=Math.max(a.x1,b.x1),right=Math.min(a.x2,b.x2);
            if(right-left<.25f)continue;
            float dl=Math.abs(a.y(left)-b.y(left)),dr=Math.abs(a.y(right)-b.y(right));
            float thickness=(dl+dr)/2;
            if(thickness*h<2 || thickness>.065f || Math.abs(dl-dr)>.02f)continue;
            if((a.y(left)-b.y(left))*(a.y(right)-b.y(right))<=0)continue;
            int whites=0,contrasts=0;
            for(int k=0;k<25;k++){
                float x=left+(right-left)*(k+.5f)/25;
                float ay=a.y(x),by=b.y(x),top=Math.min(ay,by),bottom=Math.max(ay,by);
                int inside=pixel(pixels,w,h,x,(top+bottom)/2);
                int max=Math.max((inside>>16)&255,Math.max((inside>>8)&255,inside&255));
                int min=Math.min((inside>>16)&255,Math.min((inside>>8)&255,inside&255));
                if(luma(inside)>125 && max-min<75)whites++;
                float margin=Math.max(3f/h,thickness*.6f);
                float outside=(luma(pixel(pixels,w,h,x,top-margin))+luma(pixel(pixels,w,h,x,bottom+margin)))/2;
                if(luma(inside)-outside>14)contrasts++;
            }
            if(whites<18 || contrasts<16)continue;
            float yl=(a.y(left)+b.y(left))/2,yr=(a.y(right)+b.y(right))/2;
            Candidate c=new Candidate(new float[]{left,yl,right,yr},Math.min(a.score,b.score));
            boolean duplicate=false;
            for(Candidate other:bands)if(Math.abs((other.line[1]+other.line[3]-yl-yr)/2)<.018f){duplicate=true;break;}
            if(!duplicate)bands.add(c);
        }
        // Repeated white stripes are more likely a crosswalk; do not report a stop line.
        if(bands.size()>=3)return null;
        Candidate best=null;float rank=-1;
        for(Candidate c:bands){float r=c.score+(c.line[2]-c.line[0])*.3f+(c.line[1]+c.line[3])*.05f;
            if(r>rank){best=c;rank=r;}}
        return best;
    }
    private static int pixel(int[] p,int w,int h,float x,float y){return p[Math.max(0,Math.min(h-1,(int)(y*h)))*w+Math.max(0,Math.min(w-1,(int)(x*w)))];}
    private static float luma(int c){return .299f*((c>>16)&255)+.587f*((c>>8)&255)+.114f*(c&255);}
}
