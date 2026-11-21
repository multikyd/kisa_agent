package com.kisapilot.kisaagent;
import org.junit.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.Assert.*;
public class BookmarkFileNameTest {
    private final long time=Instant.parse("2026-10-30T15:05:06Z").toEpochMilli();
    @Test public void kstRolloverUsesNextCalendarDay(){assertEquals("2026-10-31_00-05-06.zip",BookmarkFileName.zipName(time));}
    @Test public void filenameIsIndependentOfPhoneTimezoneAndLocale(){
        TimeZone zone=TimeZone.getDefault();Locale locale=Locale.getDefault();
        try{TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));Locale.setDefault(Locale.forLanguageTag("ar"));
            assertEquals("2026-10-31_00-05-06.zip",BookmarkFileName.zipName(time));
        }finally{TimeZone.setDefault(zone);Locale.setDefault(locale);}
    }
    @Test public void resumingPreservesChosenTimestamp(){assertEquals("2026-10-31_09-10-11.zip",BookmarkFileName.resumeName("2026-10-31_09-10-11.zip",time));}
    @Test public void oldSerialNameIsReplacedAndPathCannotBecomeFilename(){
        for(String old:new String[]{null,"kisa_bookmarks_1793384729.zip","../other.zip"})
            assertEquals("2026-10-31_00-05-06.zip",BookmarkFileName.resumeName(old,time));
    }
}
