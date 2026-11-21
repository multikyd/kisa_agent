package com.kisapilot.kisaagent;
import org.junit.Test;
import static org.junit.Assert.*;
public class TerminalScreenTest {
    @Test public void escapeAcrossHttpChunksDoesNotLeak(){TerminalScreen s=new TerminalScreen(80,24);s.feed("\u001b[?20");s.feed("04h\u001b[31mhello\u001b[0m");assertEquals("hello",s.text());}
    @Test public void topRedrawReplacesPriorScreen(){TerminalScreen s=new TerminalScreen(80,24);s.feed("\u001b[H\u001b[2Jtop - old\r\nPID CPU\r\n123 10");s.feed("\u001b[H\u001b[2Jtop - new\r\nPID CPU\r\n123 20");assertTrue(s.text().contains("top - new"));assertFalse(s.text().contains("old"));assertFalse(s.text().contains("\u001b"));}
    @Test public void carriageReturnAndEraseLine(){TerminalScreen s=new TerminalScreen(20,4);s.feed("progress 100%\rOK\u001b[K");assertEquals("OK",s.text());}
    @Test public void oscTitleAndCharsetAreNotText(){TerminalScreen s=new TerminalScreen(20,4);s.feed("\u001b]0;title\u001b");s.feed("\\\u001b(Btext");assertEquals("text",s.text());}
    @Test public void cursorMotionRewritesCell(){TerminalScreen s=new TerminalScreen(20,4);s.feed("abcd\u001b[2DXY");assertEquals("abXY",s.text());}
    @Test public void scrollbackIsBounded(){TerminalScreen s=new TerminalScreen(20,4);for(int i=0;i<5000;i++)s.feed("row\r\n");assertTrue(s.text().length()<5000);}

    @Test public void colorSurvivesChunkBoundaryAndReset(){TerminalScreen s=new TerminalScreen(80,24);s.feed("\u001b[1;3");s.feed("4mblue\u001b[0mplain");TerminalScreen.Snapshot v=s.snapshot();assertEquals("blueplain",v.text);assertEquals(TerminalScreen.color(4),v.runs.get(0).style.foreground);assertTrue(v.runs.get(0).style.bold);assertEquals(-1,v.runs.get(1).style.foreground);assertFalse(v.runs.get(1).style.bold);}
    @Test public void extendedColorsBackgroundAndInverse(){TerminalScreen s=new TerminalScreen(20,4);s.feed("\u001b[38;5;196;48;2;10;20;30;7mX");TerminalScreen.Style style=s.snapshot().runs.get(0).style;assertEquals(0xff0000,style.foreground);assertEquals(0x0a141e,style.background);assertTrue(style.inverse);}
    @Test public void overwrittenCellsGetNewColors(){TerminalScreen s=new TerminalScreen(20,4);s.feed("\u001b[31mred\r\u001b[32mNEW");TerminalScreen.Snapshot v=s.snapshot();assertEquals("NEW",v.text);assertEquals(TerminalScreen.color(2),v.runs.get(0).style.foreground);}
    @Test public void alternateScreenRestoresShellAndStyle(){TerminalScreen s=new TerminalScreen(20,4);s.feed("\u001b[32mshell> \u001b[?1049htop\r\nPID CPU");assertFalse(s.text().contains("shell>"));s.feed("\u001b[31mred\u001b[?1049lready");assertEquals("shell> ready",s.text());assertEquals(TerminalScreen.color(2),s.snapshot().runs.get(0).style.foreground);}
    @Test public void refreshedTopCellsKeepOnlyLatestStyle(){TerminalScreen s=new TerminalScreen(100,32);s.feed("\u001b[H\u001b[41mOLD\u001b[0m\r\nPID 123");s.feed("\u001b[H\u001b[2J\u001b[42mNEW\u001b[0m\r\nPID 456");assertTrue(s.text().contains("PID 456"));assertFalse(s.text().contains("OLD"));assertEquals(TerminalScreen.color(2),s.snapshot().runs.get(0).style.background);}
    @Test public void fullHundredColumnLineDoesNotBreakBeforeColumn100(){TerminalScreen s=new TerminalScreen(100,32);s.feed(String.join("",java.util.Collections.nCopies(100,"x")));assertEquals(100,s.text().length());assertFalse(s.text().contains("\n"));}
}
