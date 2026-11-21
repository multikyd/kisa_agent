package com.kisapilot.kisaagent;

/** Pinhole ray/level-ground intersection. Camera ground projection, not bumper distance. */
final class GroundDistance {
    static final class Lens {
        final double fx,fy,cx,cy;
        Lens(double fx,double fy,double cx,double cy){this.fx=fx;this.fy=fy;this.cx=cx;this.cy=cy;}
    }
    static Lens lens(double focalMm,double sensorWidthMm,double sensorHeightMm,int pixelWidth,int pixelHeight,
            int left,int top,int right,int bottom,double outputAspect){
        if(!(focalMm>0 && sensorWidthMm>0 && sensorHeightMm>0 && pixelWidth>0 && pixelHeight>0 && outputAspect>0))return null;
        double w=right-left,h=bottom-top;if(w<=0 || h<=0)return null;
        double cx=(left+right)/2.0,cy=(top+bottom)/2.0;
        if(w/h>outputAspect)w=h*outputAspect;else h=w/outputAspect;
        return new Lens(focalMm*pixelWidth/sensorWidthMm/w,focalMm*pixelHeight/sensorHeightMm/h,
                (pixelWidth/2.0-cx+w/2)/w,(pixelHeight/2.0-cy+h/2)/h);
    }
    static double metres(double u,double v,int rotationDegrees,int sensorDegrees,Lens lens,float[] gravity,double height){
        if(lens==null || gravity==null || gravity.length<3 || !(height>=.3 && height<=3) || !Double.isFinite(u) || !Double.isFinite(v) || u<0 || u>1 || v<0 || v>1)return Double.NaN;
        double rawU,rawV;
        switch(rotationDegrees){
            case 0:rawU=u;rawV=v;break;
            case 90:rawU=v;rawV=1-u;break;
            case 180:rawU=1-u;rawV=1-v;break;
            case 270:rawU=1-v;rawV=u;break;
            default:return Double.NaN;
        }
        if(!(lens.fx>0 && lens.fy>0))return Double.NaN;
        double gx=gravity[0],gy=gravity[1],gz=gravity[2],norm=Math.sqrt(gx*gx+gy*gy+gz*gz);
        if(!Double.isFinite(norm) || norm<7 || norm>12)return Double.NaN;
        double angle=Math.toRadians(sensorDegrees),c=Math.cos(angle),s=Math.sin(angle);
        double nx=(c*gx-s*gy)/norm,ny=(-s*gx-c*gy)/norm,nz=-gz/norm;
        double x=(rawU-lens.cx)/lens.fx,y=(rawV-lens.cy)/lens.fy;
        double dot=nx*x+ny*y+nz;
        if(dot>=-.025)return Double.NaN; // no forward intersection, or too near the horizon
        double t=-height/dot,groundSquared=t*t*(x*x+y*y+1)-height*height;
        double distance=Math.sqrt(Math.max(0,groundSquared));
        return Double.isFinite(distance) && distance>=.5 && distance<=60?distance:Double.NaN;
    }
}
