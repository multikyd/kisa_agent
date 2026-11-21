package com.kisapilot.kisaagent;
import org.junit.Test;
import static org.junit.Assert.*;
public class LogTimeTest {
    @Test public void epochAlwaysDisplaysSeoul(){assertEquals("1970-01-01 09:00 KST",LogTime.kst(0));}
    @Test public void midnightRollsToNextDay(){assertEquals("2026-10-26 02:00 KST",LogTime.utcTextToKst("2026-10-25 17:00:00"));}
}
