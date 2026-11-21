package com.kisapilot.kisaagent;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Arrays;
public class ColorProbeTest {
    @Test public void absentRegionDoesNotClaimDetection(){assertEquals("영역 미지정",ColorProbe.observe(new int[16],4,4,null));}
    @Test public void redEvidenceIsNotBrakeOrRedSignalClassification(){int[] p=new int[100];Arrays.fill(p,0xffff0000);String result=ColorProbe.observe(p,10,10,new float[]{0,0,1,1});assertTrue(result.contains("빨강"));assertTrue(result.contains("의미 판단 불가"));}
    @Test public void darkAndGrayNeverBecomeOffOrGreen(){int[] p=new int[100];Arrays.fill(p,0xff888888);assertTrue(ColorProbe.observe(p,10,10,new float[]{0,0,1,1}).contains("판단 불가"));}
    @Test public void trafficColorsRemainOnlyColorEvidence(){for(int c:new int[]{0xff00ff00,0xffffff00}){int[] p=new int[100];Arrays.fill(p,c);assertTrue(ColorProbe.observe(p,10,10,new float[]{0,0,1,1}).contains("의미 판단 불가"));}}
}
