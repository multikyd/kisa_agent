package com.kisapilot.kisaagent;
import java.time.*;
import java.time.format.*;
final class LogTime {
    static String kst(long unixSeconds){return Instant.ofEpochSecond(unixSeconds).atZone(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'KST'"));}
    static String utcTextToKst(String value){try{return kst(LocalDateTime.parse(value,DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).toEpochSecond(ZoneOffset.UTC));}catch(Exception e){return value+" (시간대 미확인)";}}
}
