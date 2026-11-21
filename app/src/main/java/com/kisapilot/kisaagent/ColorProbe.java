package com.kisapilot.kisaagent;

/** Colour evidence in a human-selected region. Not a vehicle or traffic-light model. */
final class ColorProbe {
    static String observe(int[] pixels, int width, int height, float[] roi) {
        if (roi == null) return "영역 미지정";
        int red=0, yellow=0, green=0, total=0;
        for (int y=Math.max(0,(int)(roi[1]*height)); y<Math.min(height,(int)(roi[3]*height)); y++)
            for (int x=Math.max(0,(int)(roi[0]*width)); x<Math.min(width,(int)(roi[2]*width)); x++) {
                int p=pixels[y*width+x], r=(p>>16)&255, g=(p>>8)&255, b=p&255; total++;
                if(r>100 && r>g*1.6 && r>b*1.5) red++;
                else if(r>120 && g>100 && b<g*.65 && r<g*1.6) yellow++;
                else if(g>100 && g>r*1.4 && g>b*1.2) green++;
            }
        int best=Math.max(red,Math.max(yellow,green));
        if(total==0 || best<3 || (double)best/total<.005) return "색상 근거 부족 · 판단 불가";
        String color=best==red?"빨강":best==yellow?"노랑":"초록";
        return color+" 픽셀 "+Math.round(100.0*best/total)+"% · 의미 판단 불가";
    }
}
