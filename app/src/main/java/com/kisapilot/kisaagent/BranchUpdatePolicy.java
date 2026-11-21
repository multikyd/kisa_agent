package com.kisapilot.kisaagent;
/** Distinguishes accepted branch requests from staged updates and onroad idle waits. */
final class BranchUpdatePolicy {
 enum State { DISABLED, READY, STALE_READY, CHECKING, DOWNLOADING, FINALIZING, WAITING_OS_OFFROAD, STARTING, ONROAD_IDLE, IDLE }
 static boolean truth(Object value){return value instanceof Boolean?(Boolean)value:value instanceof Number?((Number)value).doubleValue()==1:"1".equals(String.valueOf(value))||"true".equalsIgnoreCase(String.valueOf(value));}
 static State state(String raw,boolean onroad,boolean disabled,boolean available,String prepared,String requested){
  if(disabled)return State.DISABLED;
  String s=raw==null?"":raw.toLowerCase(java.util.Locale.ROOT);
  if(available)return prepared!=null&&prepared.contains(" / "+requested+" / ")?State.READY:State.STALE_READY;
  if(s.contains("waiting_offroad_os"))return State.WAITING_OS_OFFROAD;
  if(s.contains("finalizing"))return State.FINALIZING;
  if(s.contains("downloading"))return State.DOWNLOADING;
  if(s.contains("checking"))return State.CHECKING;
  if(s.contains("starting")||s.contains("initializ"))return State.STARTING;
  return onroad?State.ONROAD_IDLE:State.IDLE;
 }
}
