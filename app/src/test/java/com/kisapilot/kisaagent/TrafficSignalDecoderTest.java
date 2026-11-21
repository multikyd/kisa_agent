package com.kisapilot.kisaagent;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.io.*;
import java.nio.*;
public class TrafficSignalDecoderTest {
 @Test public void emptyOutputDoesNotMeanGreenOrOff(){assertTrue(TrafficSignalDecoder.decode(new float[8][8400],1280,720).isEmpty());}
 @Test public void classAndLetterboxCoordinates(){float[][] o=new float[8][1];o[0][0]=320;o[1][0]=320;o[2][0]=100;o[3][0]=40;o[6][0]=.8f;List<TrafficSignalDecoder.Detection> r=TrafficSignalDecoder.decode(o,1280,720);assertEquals(2,r.get(0).label);assertArrayEquals(new float[]{540f/1280,320f/720,740f/1280,400f/720},r.get(0).roi,.0001f);}
 @Test public void sameClassNmsRetainsStrongerBox(){float[][] o=new float[8][2];for(int i=0;i<2;i++){o[0][i]=320+i;o[1][i]=320;o[2][i]=100;o[3][i]=40;o[6][i]=.8f-i*.1f;}assertEquals(1,TrafficSignalDecoder.decode(o,640,640).size());}
 @Test public void distinctSignalStatesAreNotMerged(){float[][] o=new float[8][2];for(int i=0;i<2;i++){o[0][i]=320;o[1][i]=320;o[2][i]=100;o[3][i]=40;}o[5][0]=.8f;o[6][1]=.7f;assertEquals(2,TrafficSignalDecoder.decode(o,640,640).size());}
 @Test public void nanCoordinatesCannotEscapeIntoUi(){float[][] o=new float[8][1];o[0][0]=Float.NaN;o[1][0]=320;o[2][0]=20;o[3][0]=20;o[6][0]=.9f;assertTrue(TrafficSignalDecoder.decode(o,640,640).isEmpty());}
 @Test public void realPcOutputMatchesAndroidDecoder()throws Exception{
  byte[] bytes;try(InputStream in=getClass().getResourceAsStream("/korean-signal-output.bin");ByteArrayOutputStream out=new ByteArrayOutputStream()){assertNotNull(in);byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);bytes=out.toByteArray();}
  FloatBuffer raw=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();float[][] o=new float[8][8400];for(float[] row:o)raw.get(row);
  List<TrafficSignalDecoder.Detection> r=TrafficSignalDecoder.decode(o,1280,720);assertEquals(2,r.size());assertEquals(0,r.get(0).label);assertEquals(.42051685f,r.get(0).score,.00001f);
  assertArrayEquals(new float[]{448.024353f/1280,40.772583f/720,464.503906f/1280,60.562866f/720},r.get(0).roi,.0001f);assertEquals(1,r.get(1).label);
 }
}
