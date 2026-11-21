package com.kisapilot.kisaagent;

import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.regex.*;

/** Bounded, read-only OBD commands and strict ISO-TP response decoding. */
final class Elm327Protocol {
    private static final Set<String> ALLOWED = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "ATZ", "ATI", "ATE0", "ATL0", "ATH1", "ATS1", "ATD0", "ATSP6", "ATCAF1", "ATCFC1", "ATSTFF",
            "ATDP", "ATRV", "ATSH7DF", "ATSH7E4", "ATCRA", "ATCRA7EC", "0100", "03", "07", "0A", "220101", "220105")));

    static String command(String input) {
        String value = input == null ? "" : input.toUpperCase(Locale.ROOT).replaceAll("\\s", "");
        if (!ALLOWED.contains(value)) throw new IllegalArgumentException("Read-only command not allowed");
        return value;
    }

    static Double adapterVoltage(String response) {
        Matcher match = Pattern.compile("(?i)([0-9]{1,2}(?:\\.[0-9]+)?)\\s*V").matcher(response);
        if (!match.find()) return null;
        double volts = Double.parseDouble(match.group(1));
        return volts >= 5 && volts <= 30 ? volts : null;
    }

    static boolean standardEcuReply(String raw) {
        if(raw==null||raw.length()>65536)return false;
        for(String line:raw.toUpperCase(Locale.ROOT).split("[\\r\\n]+")){
            String compact=line.replaceAll("\\s","").replace(">","");
            if(compact.matches("7E[8-F](?:06)?4100[0-9A-F]{8}(?:00){0,2}"))return true;
        }
        return false;
    }

    static final class Result {
        final Map<String, List<String>> codes = new LinkedHashMap<>();
        boolean responding, incomplete;
        String status() { return incomplete ? "응답 일부 해석 실패" : responding ? "응답 ECU 조회 완료" : "응답 없음·미지원 여부 확인 필요"; }
    }

    private static final class Frame {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int length, next = 1;
        boolean invalid;
    }

    static Result dtcs(String raw, int requestMode) {
        if (raw == null || raw.length() > 65536 || (requestMode != 3 && requestMode != 7 && requestMode != 10))
            throw new IllegalArgumentException("Invalid OBD response");
        Result result = new Result();
        Map<String, Frame> frames = new LinkedHashMap<>();
        for (String line : raw.toUpperCase(Locale.ROOT).split("[\\r\\n]+")) {
            String compact = line.replaceAll("\\s", "").replace(">", "");
            if (!compact.matches("7E[8-F][0-9A-F]+")) continue;
            String ecu = compact.substring(0, 3), body = compact.substring(3);
            if (body.length() < 2 || (body.length() & 1) != 0) { result.incomplete = true; continue; }
            byte[] data = new byte[body.length()/2];
            for (int i=0; i<data.length; i++) data[i]=(byte)Integer.parseInt(body.substring(i*2,i*2+2),16);
            int first=data[0]&255, type=first>>4;
            if (type == 0) {
                int length=first&15;
                if (length==0 || length>data.length-1) { result.incomplete=true; continue; }
                decode(ecu,Arrays.copyOfRange(data,1,1+length),requestMode,result);
            } else if (type == 1) {
                if (data.length<3) { result.incomplete=true; continue; }
                Frame frame=new Frame(); frame.length=((first&15)<<8)|(data[1]&255);
                if (frame.length<=data.length-2 || frame.length>4095) { result.incomplete=true; continue; }
                if (frames.containsKey(ecu)) result.incomplete=true;
                frame.bytes.write(data,2,data.length-2);frames.put(ecu,frame);
            } else if (type == 2) {
                Frame frame=frames.get(ecu);
                if(frame==null || frame.invalid || (first&15)!=frame.next) {
                    if(frame!=null)frame.invalid=true;result.incomplete=true;continue;
                }
                frame.next=(frame.next+1)&15;
                frame.bytes.write(data,1,Math.min(data.length-1,frame.length-frame.bytes.size()));
                if(frame.bytes.size()==frame.length){decode(ecu,frame.bytes.toByteArray(),requestMode,result);frames.remove(ecu);}
            } else if (first == requestMode+0x40) {
                decode(ecu,data,requestMode,result);
            }
        }
        if(!frames.isEmpty())result.incomplete=true;
        return result;
    }

    private static void decode(String ecu,byte[] payload,int mode,Result result) {
        if(payload.length<2 || (payload[0]&255)!=mode+0x40)return;
        int start=1,count=(payload.length-1)/2;
        // ISO 15765 includes a count; also accept adapters that expose a bare DTC list.
        if(((payload.length-1)&1)==1){
            count=payload[1]&255;start=2;
            if(count>(payload.length-start)/2){result.incomplete=true;return;}
            for(int i=start+count*2;i<payload.length;i++)if(payload[i]!=0){result.incomplete=true;return;}
        }
        LinkedHashSet<String> found=new LinkedHashSet<>(result.codes.getOrDefault(ecu,Collections.emptyList()));
        for(int i=0;i<count;i++){
            int hi=payload[start+i*2]&255,lo=payload[start+i*2+1]&255;
            if(hi==0&&lo==0)continue;
            found.add("PCBU".charAt(hi>>6)+String.format(Locale.ROOT,"%01X%01X%02X",(hi>>4)&3,hi&15,lo));
        }
        result.responding=true;result.codes.put(ecu,new ArrayList<>(found));
    }
    private Elm327Protocol() {}
}
