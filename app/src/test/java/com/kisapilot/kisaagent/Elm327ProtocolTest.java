package com.kisapilot.kisaagent;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class Elm327ProtocolTest {
    @Test public void readOnlyAllowlistBlocksClearsWritesAndInjection(){
        for(String input:new String[]{"04","14FFFFFF","1101","2701","2EF19000","3101","ATMA","ATI\r04","","ATSH123"}){
            try{Elm327Protocol.command(input);fail(input);}catch(IllegalArgumentException expected){}
        }
        assertEquals("0100",Elm327Protocol.command("01 00"));
        assertEquals("220101",Elm327Protocol.command("22 01 01"));
    }
    @Test public void adapterVoltageIsNotFabricated(){
        assertEquals(12.6,Elm327Protocol.adapterVoltage("ATRV\r12.6V"),.001);
        assertNull(Elm327Protocol.adapterVoltage("NO DATA"));assertNull(Elm327Protocol.adapterVoltage("99V"));
    }
    @Test public void ecuReplyRequiresVehicleHeaderAndMatchingService(){
        assertTrue(Elm327Protocol.standardEcuReply("7E8 06 41 00 BE 3E A8 13"));
        assertTrue(Elm327Protocol.standardEcuReply("7E94100BE3EA813"));
        for(String input:new String[]{"ELM327 V1.5","OK","NO DATA","7E8 06 41 01 BE 3E A8 13","4100BE3EA813"})assertFalse(input,Elm327Protocol.standardEcuReply(input));
    }
    @Test public void noDataIsNotAnEmptySuccessfulDtcScan(){
        Elm327Protocol.Result r=Elm327Protocol.dtcs("03\rNO DATA\r>",3);
        assertFalse(r.responding);assertTrue(r.codes.isEmpty());
    }
    @Test public void countedSingleFrameDecodesAndIgnoresZeroPadding(){
        Elm327Protocol.Result r=Elm327Protocol.dtcs("7E8 06 43 01 01 33 00 00",3);
        assertTrue(r.responding);assertFalse(r.incomplete);assertEquals(Arrays.asList("P0133"),r.codes.get("7E8"));
    }
    @Test public void bareDtcListAndMultipleEcusRemainSeparate(){
        Elm327Protocol.Result r=Elm327Protocol.dtcs("7E8 05 43 01 33 C1 00\r7E9 03 43 80 01",3);
        assertEquals(Arrays.asList("P0133","U0100"),r.codes.get("7E8"));
        assertEquals(Arrays.asList("B0001"),r.codes.get("7E9"));
    }
    @Test public void explicitZeroCountIsAnAnsweredScan(){
        Elm327Protocol.Result r=Elm327Protocol.dtcs("7E8 02 43 00",3);
        assertTrue(r.responding);assertFalse(r.incomplete);assertTrue(r.codes.get("7E8").isEmpty());
    }
    @Test public void consecutiveFramesAreReassembledPerEcu(){
        Elm327Protocol.Result r=Elm327Protocol.dtcs("7E8 10 0A 43 04 01 33 01 34\r7E9 02 43 00\r7E8 21 C1 00 80 01 00 00 00",3);
        assertFalse(r.incomplete);assertEquals(Arrays.asList("P0133","P0134","U0100","B0001"),r.codes.get("7E8"));
        assertTrue(r.codes.get("7E9").isEmpty());
    }
    @Test public void incompleteOrOutOfSequenceFramesCannotClaimSuccess(){
        for(String raw:new String[]{"7E8 10 0A 43 04 01 33 01 34","7E8 10 0A 43 04 01 33 01 34\r7E8 22 C1 00 80 01 00 00 00","7E8 09 43 00"}){
            Elm327Protocol.Result r=Elm327Protocol.dtcs(raw,3);assertTrue(r.incomplete);assertFalse(r.responding);
        }
    }
    @Test public void modesAreNotMixedAndImpossibleCountsAreRejected(){
        assertFalse(Elm327Protocol.dtcs("7E8 02 47 00",3).responding);
        assertTrue(Elm327Protocol.dtcs("7E8 02 47 00",7).responding);
        assertTrue(Elm327Protocol.dtcs("7E8 02 4A 00",10).responding);
        Elm327Protocol.Result r=Elm327Protocol.dtcs("7E8 04 43 03 01 33",3);assertTrue(r.incomplete);assertFalse(r.responding);
    }
}
