package com.kisapilot.kisaagent;
import android.app.*;import android.content.*;import android.os.*;import android.text.*;import android.text.style.*;import android.widget.*;import android.view.*;import androidx.test.platform.app.InstrumentationRegistry;import org.junit.Test;import static org.junit.Assert.*;import java.lang.reflect.*;import java.io.*;
public class TerminalRenderingTest {
 @Test public void coloredFixedColumnsAndTopRedraw() throws Exception {
  Instrumentation probe=InstrumentationRegistry.getInstrumentation();Context c=probe.getTargetContext();Activity a=probe.startActivitySync(new Intent(c,TerminalActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  try {Thread.sleep(500);Field f=TerminalActivity.class.getDeclaredField("terminalScreen");f.setAccessible(true);TerminalScreen screen=(TerminalScreen)f.get(a);Method update=TerminalActivity.class.getDeclaredMethod("updateTerminalView");update.setAccessible(true);
   probe.runOnMainSync(()->{try{screen.feed("\u001b[1;32muser@C4\u001b[0m:\u001b[1;34m/data/openpilot\u001b[0m$ ls -al\r\n-rw-r--r-- 1 user user  2048 Nov 13 18:00 README.md\r\ndrwxr-xr-x 2 user user  4096 Nov 13 18:00 \u001b[1;34mselfdrive\u001b[0m\r\n\u001b[H\u001b[2J\u001b[42;30mtop - live process table\u001b[0m\r\n");screen.feed(String.join("",java.util.Collections.nCopies(100,"-")));screen.feed("\r\n\u001b[38;5;196m  1234  python3       18.2%  openpilot\u001b[0m");update.invoke(a);}catch(Exception e){throw new RuntimeException(e);}});Thread.sleep(400);
   TextView view=a.findViewById(R.id.tv_terminal_log);HorizontalScrollView horizontal=a.findViewById(R.id.terminal_horizontal_scroll);Spanned text=(Spanned)view.getText();assertTrue("foreground spans missing",text.getSpans(0,text.length(),ForegroundColorSpan.class).length>0);assertTrue("background spans missing",text.getSpans(0,text.length(),BackgroundColorSpan.class).length>0);assertEquals(3,view.getLayout().getLineCount());assertTrue("fixed columns width="+view.getWidth()+" viewport="+horizontal.getWidth(),view.getWidth()>horizontal.getWidth());
   probe.runOnMainSync(()->horizontal.scrollTo(100,0));assertTrue(horizontal.getScrollX()>0);
   probe.runOnMainSync(()->a.findViewById(R.id.terminal_fit).performClick());Thread.sleep(200);assertTrue(view.getWidth()<=horizontal.getWidth()+2);
   probe.runOnMainSync(()->a.findViewById(R.id.terminal_font_larger).performClick());Thread.sleep(200);assertTrue("fixed columns width="+view.getWidth()+" viewport="+horizontal.getWidth(),view.getWidth()>horizontal.getWidth());
   android.graphics.Bitmap shot=probe.getUiAutomation().takeScreenshot();try(FileOutputStream out=new FileOutputStream(new File(c.getExternalFilesDir(null),"terminal-color-check.png"))){shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}shot.recycle();
   probe.runOnMainSync(()->{try{screen.feed("\u001b[H\u001b[2Jtop - refreshed\r\nPID CPU\r\n5678 42%");update.invoke(a);}catch(Exception e){throw new RuntimeException(e);}});assertFalse(view.getText().toString().contains("1234"));assertTrue(view.getText().toString().contains("5678"));
  } finally {probe.runOnMainSync(a::finish);}
 }
}
