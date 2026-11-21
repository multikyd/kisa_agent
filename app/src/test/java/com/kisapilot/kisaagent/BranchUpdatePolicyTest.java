package com.kisapilot.kisaagent;
import org.junit.Test;import static org.junit.Assert.*;
public class BranchUpdatePolicyTest {
 @Test public void onroadDownloadKeepsProgress(){assertEquals(BranchUpdatePolicy.State.DOWNLOADING,BranchUpdatePolicy.state("downloading...",true,false,false,"","target"));}
 @Test public void oldOnroadIdleExplainsMissingWorker(){assertEquals(BranchUpdatePolicy.State.ONROAD_IDLE,BranchUpdatePolicy.state("idle",true,false,false,"","target"));}
 @Test public void offroadIdleIsSeparate(){assertEquals(BranchUpdatePolicy.State.IDLE,BranchUpdatePolicy.state("idle",false,false,false,"","target"));}
 @Test public void anotherPreparedBranchCannotTriggerReboot(){assertEquals(BranchUpdatePolicy.State.STALE_READY,BranchUpdatePolicy.state("idle",true,false,true,"v / other / abc / today","target"));}
 @Test public void matchingSlashBranchCanBeReadyOnroad(){assertEquals(BranchUpdatePolicy.State.READY,BranchUpdatePolicy.state("idle",true,false,true,"v / feature/target / abc / today","feature/target"));}
 @Test public void changedOsWaitHasExplicitState(){assertEquals(BranchUpdatePolicy.State.WAITING_OS_OFFROAD,BranchUpdatePolicy.state("waiting_offroad_os",true,false,false,"","target"));}
 @Test public void disabledCannotLookLikeSuccess(){assertEquals(BranchUpdatePolicy.State.DISABLED,BranchUpdatePolicy.state("idle",false,true,true,"v / target / abc / today","target"));}
 @Test public void acceptsTypedParamBooleans(){assertTrue(BranchUpdatePolicy.truth(true));assertTrue(BranchUpdatePolicy.truth(1));assertTrue(BranchUpdatePolicy.truth("true"));assertFalse(BranchUpdatePolicy.truth("0"));assertFalse(BranchUpdatePolicy.truth(""));}
}
