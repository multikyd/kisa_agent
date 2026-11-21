package com.kisapilot.kisaagent;
import org.junit.Test;
import static org.junit.Assert.*;
public class GroundDistanceTest {
    private final GroundDistance.Lens lens=new GroundDistance.Lens(1,1,.5,.5);
    @Test public void levelPortraitCameraAt150cmSeesGroundAt10m(){assertEquals(10,GroundDistance.metres(.5,.65,90,90,lens,new float[]{0,9.81f,0},1.5),.0001);}
    @Test public void upsideDownPortraitMatchesGroundDistance(){assertEquals(10,GroundDistance.metres(.5,.65,270,90,lens,new float[]{0,-9.81f,0},1.5),.0001);}
    @Test public void bothLandscapeOrientationsMatch(){
        assertEquals(10,GroundDistance.metres(.5,.65,0,90,lens,new float[]{9.81f,0,0},1.5),.0001);
        assertEquals(10,GroundDistance.metres(.5,.65,180,90,lens,new float[]{-9.81f,0,0},1.5),.0001);
    }
    @Test public void sensor270PortraitMatches(){assertEquals(10,GroundDistance.metres(.5,.65,270,270,lens,new float[]{0,9.81f,0},1.5),.0001);}
    @Test public void unsetHeightAndMissingPoseNeverReturnNumbers(){
        assertTrue(Double.isNaN(GroundDistance.metres(.5,.65,90,90,lens,new float[]{0,9.81f,0},0)));
        assertTrue(Double.isNaN(GroundDistance.metres(.5,.65,90,90,lens,null,1.5)));
        assertTrue(Double.isNaN(GroundDistance.metres(.5,.65,90,90,null,new float[]{0,9.81f,0},1.5)));
    }
    @Test public void horizonAndSkyAreRejected(){
        for(double y:new double[]{.3,.5,.501})assertTrue(Double.isNaN(GroundDistance.metres(.5,y,90,90,lens,new float[]{0,9.81f,0},1.5)));
    }
    @Test public void heightErrorScalesDistance(){assertEquals(8,GroundDistance.metres(.5,.65,90,90,lens,new float[]{0,9.81f,0},1.2),.0001);}
    @Test public void outputAspectCropChangesFocalLengths(){
        GroundDistance.Lens l=GroundDistance.lens(4,6,4.5,4000,3000,0,0,4000,3000,16.0/9);
        assertNotNull(l);assertEquals(2.0/3,l.fx,.0001);assertEquals(1.185185,l.fy,.0001);assertEquals(.5,l.cx,0);assertEquals(.5,l.cy,0);
    }
    @Test public void badMetadataHasNoCalibration(){assertNull(GroundDistance.lens(0,6,4,4000,3000,0,0,4000,3000,4.0/3));}
    @Test public void invalidGravityNeverReturnsNumbers(){assertTrue(Double.isNaN(GroundDistance.metres(.5,.65,90,90,lens,new float[]{Float.NaN,0,0},1.5)));}
}
