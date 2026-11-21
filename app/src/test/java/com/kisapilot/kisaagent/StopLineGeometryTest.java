package com.kisapilot.kisaagent;
import org.junit.Test;
import java.util.*;
import java.io.*;
import static org.junit.Assert.*;
public class StopLineGeometryTest {
    private int[] road(){int[] p=new int[640*480];Arrays.fill(p,0xff323232);return p;}
    private void band(int[] p,int top,int bottom){for(int y=top;y<=bottom;y++)for(int x=90;x<=550;x++)p[y*640+x]=0xffebebeb;}
    private List<StopLineGeometry.Line> edges(float top,float bottom){return Arrays.asList(new StopLineGeometry.Line(.14f,top,.86f,top,.8f),new StopLineGeometry.Line(.14f,bottom,.86f,bottom,.8f));}
    @Test public void actualModelOutputDecodesAndSelectsWhiteBand()throws Exception{
        try(DataInputStream in=new DataInputStream(getClass().getResourceAsStream("/mlsd-white-band-output.bin"))){
            int w=in.readInt(),h=in.readInt();int[][] points=new int[200][2];float[] scores=new float[200];float[][][] offsets=new float[160][160][4];
            for(int[] point:points)for(int j=0;j<2;j++)point[j]=in.readInt();
            for(int i=0;i<200;i++)scores[i]=in.readFloat();
            for(float[][] row:offsets)for(float[] pixel:row)for(int j=0;j<4;j++)pixel[j]=in.readFloat();
            List<StopLineGeometry.Line> lines=StopLineGeometry.decode(points,scores,offsets);assertEquals(2,lines.size());
            assertEquals(.1434968f,lines.get(0).x1,.00001f);assertEquals(.758998f,lines.get(0).score,.00001f);
            int[] pixels=road();band(pixels,300,320);StopLineGeometry.Candidate c=StopLineGeometry.select(lines,pixels,w,h);
            assertNotNull(c);assertEquals(.645f,c.line[1],.01f);
        }
    }
    @Test public void darkBandIsNotAWhiteStopLine(){assertNull(StopLineGeometry.select(edges(.625f,.667f),road(),640,480));}
    @Test public void brightFlatImageWithoutEdgeContrastIsRejected(){int[] p=road();Arrays.fill(p,0xffebebeb);assertNull(StopLineGeometry.select(edges(.625f,.667f),p,640,480));}
    @Test public void repeatedCrosswalkBandsAreRejected(){
        int[] p=road();List<StopLineGeometry.Line> lines=new ArrayList<>();
        for(int y:new int[]{240,290,340}){band(p,y,y+15);lines.addAll(edges(y/480f,(y+15)/480f));}
        assertNull(StopLineGeometry.select(lines,p,640,480));
    }
    @Test public void dashboardBottomIsExcluded(){int[] p=road();band(p,432,451);assertNull(StopLineGeometry.select(edges(.90f,.94f),p,640,480));}
    @Test public void isolatedEdgeCannotBecomeAStopLine(){int[] p=road();band(p,300,320);assertNull(StopLineGeometry.select(Collections.singletonList(edges(.625f,.667f).get(0)),p,640,480));}
    @Test public void decoderRejectsBadCentresAndNonfiniteOffsets(){
        float[][][] offsets=new float[160][160][4];offsets[80][80]=new float[]{Float.NaN,0,40,0};
        assertTrue(StopLineGeometry.decode(new int[][]{{80,80},{-1,10},{160,20}},new float[]{.9f,.9f,.9f},offsets).isEmpty());
    }
    @Test public void verticalLinesAreExcluded(){int[] p=road();band(p,300,320);assertNull(StopLineGeometry.select(Arrays.asList(new StopLineGeometry.Line(.4f,.4f,.41f,.8f,.9f),new StopLineGeometry.Line(.6f,.4f,.61f,.8f,.9f)),p,640,480));}
}
