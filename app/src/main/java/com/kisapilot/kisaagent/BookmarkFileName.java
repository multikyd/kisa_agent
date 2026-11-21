package com.kisapilot.kisaagent;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Export/save time, independent of the phone's timezone and route IDs. */
final class BookmarkFileName {
    private static final DateTimeFormatter KST=DateTimeFormatter.ofPattern("uuuu-MM-dd_HH-mm-ss",Locale.ROOT)
        .withZone(ZoneId.of("Asia/Seoul"));
    static String zipName(long unixMillis){return KST.format(Instant.ofEpochMilli(unixMillis))+".zip";}
    static String resumeName(String saved,long now){
        return saved!=null && saved.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}_[0-9]{2}-[0-9]{2}-[0-9]{2}\\.zip")?saved:zipName(now);
    }
}
