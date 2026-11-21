package com.kisapilot.kisaagent;

import java.util.*;

/** Incremental fixed-column VT screen with per-cell SGR attributes. */
final class TerminalScreen {
    static final class Style {
        final int foreground,background; final boolean bold,underline,inverse;
        Style(int fg,int bg,boolean b,boolean u,boolean i){foreground=fg;background=bg;bold=b;underline=u;inverse=i;}
    }
    static final Style DEFAULT=new Style(-1,-1,false,false,false);
    static final class Line {
        final char[] chars;final Style[] styles;
        Line(int width,Style style){chars=new char[width];Arrays.fill(chars,' ');styles=new Style[width];Arrays.fill(styles,style);}
    }
    static final class Run {final int start,end;final Style style;Run(int a,int b,Style s){start=a;end=b;style=s;}}
    static final class Snapshot {final String text;final List<Run> runs;Snapshot(String t,List<Run> r){text=t;runs=r;}}
    private final int columns,rows;
    private final List<Line> history=new ArrayList<>();
    private Line[] screen,normalScreen;
    private int x,y,savedX,savedY,normalX,normalY,state,top,bottom;
    private boolean alternate;
    private Style style=DEFAULT,normalStyle=DEFAULT;
    private final StringBuilder sequence=new StringBuilder();
    TerminalScreen(int columns,int rows){this.columns=columns;this.rows=rows;screen=blank();bottom=rows-1;}
    private Line[] blank(){Line[] grid=new Line[rows];for(int i=0;i<rows;i++)grid[i]=new Line(columns,DEFAULT);return grid;}
    synchronized void feed(String text){
        for(int i=0;i<text.length();i++){
            char c=text.charAt(i);
            if(state==4){if(c=='\u0007')state=0;else if(c=='\u001b')state=5;continue;}
            if(state==5){state=c=='\\'?0:4;continue;}
            if(state==3){state=0;continue;}
            if(state==1){
                if(c=='['){state=2;sequence.setLength(0);}else if(c==']'||c=='P'||c=='^'||c=='_')state=4;
                else if(c=='('||c==')'||c=='*'||c=='+')state=3;
                else{if(c=='7'){savedX=x;savedY=y;}if(c=='8'){x=savedX;y=savedY;}if(c=='D')newline();if(c=='E'){x=0;newline();}if(c=='M'){if(y==top)scrollDown(1);else y=Math.max(0,y-1);}state=0;}continue;
            }
            if(state==2){if(c>=0x40&&c<=0x7e){command(c,sequence.toString());state=0;}else if(sequence.length()<128)sequence.append(c);else state=0;continue;}
            if(c=='\u001b'){state=1;continue;}if(c=='\r'){x=0;continue;}if(c=='\n'){newline();continue;}if(c=='\b'){x=Math.max(0,x-1);continue;}
            if(c=='\t'){x=Math.min(columns-1,(x/8+1)*8);continue;}if(c<32||c==127)continue;
            if(x>=columns){x=0;newline();}screen[y].chars[x]=c;screen[y].styles[x]=style;x++;
        }
    }
    private void newline(){if(y==bottom)scrollUp(1);else y=Math.min(rows-1,y+1);}
    private void scrollUp(int count){for(int i=0;i<Math.min(rows,count);i++){if(!alternate&&top==0&&bottom==rows-1){history.add(screen[top]);if(history.size()>1000)history.remove(0);}for(int r=top;r<bottom;r++)screen[r]=screen[r+1];screen[bottom]=new Line(columns,style);}}
    private void scrollDown(int count){for(int i=0;i<Math.min(rows,count);i++){for(int r=bottom;r>top;r--)screen[r]=screen[r-1];screen[top]=new Line(columns,style);}}
    private int value(String[] p,int i,int fallback){try{int v=i<p.length?Integer.parseInt(p[i]):fallback;return Math.min(10000,Math.max(0,v));}catch(NumberFormatException e){return fallback;}}
    private void clear(int row,int start,int end){Arrays.fill(screen[row].chars,start,end,' ');Arrays.fill(screen[row].styles,start,end,style);}
    private void alternate(boolean enabled){if(enabled==alternate)return;if(enabled){normalScreen=screen;normalX=x;normalY=y;normalStyle=style;screen=blank();x=y=0;}else{screen=normalScreen;normalScreen=null;x=normalX;y=normalY;style=normalStyle;}alternate=enabled;top=0;bottom=rows-1;}
    private void command(char c,String raw){
        String[] p=raw.replaceFirst("^[?=>]","").split(";",-1);int n=Math.max(1,value(p,0,1));
        if((c=='h'||c=='l')&&raw.startsWith("?")){for(String item:p)if(item.equals("1049")||item.equals("1047")||item.equals("47"))alternate(c=='h');return;}
        switch(c){
            case 'm':sgr(p);break;
            case 'H':case 'f':y=Math.min(rows-1,Math.max(0,value(p,0,1)-1));x=Math.min(columns-1,Math.max(0,value(p,1,1)-1));break;
            case 'A':y=Math.max(0,y-n);break;case 'B':y=Math.min(rows-1,y+n);break;case 'C':x=Math.min(columns-1,x+n);break;case 'D':x=Math.max(0,x-n);break;
            case 'G':x=Math.min(columns-1,n-1);break;case 'd':y=Math.min(rows-1,n-1);break;
            case 'J':int mode=value(p,0,0);if(mode==2||mode==3){for(int r=0;r<rows;r++)clear(r,0,columns);if(mode==3)history.clear();}else if(mode==0){clear(y,Math.min(x,columns),columns);for(int r=y+1;r<rows;r++)clear(r,0,columns);}else if(mode==1){for(int r=0;r<y;r++)clear(r,0,columns);clear(y,0,Math.min(columns,x+1));}break;
            case 'K':int k=value(p,0,0);clear(y,k==0?Math.min(x,columns):0,k==1?Math.min(columns,x+1):columns);break;
            case 's':savedX=x;savedY=y;break;case 'u':x=savedX;y=savedY;break;
            case 'r':int a=value(p,0,1)-1,b=value(p,1,rows)-1;if(a>=0&&b<rows&&a<b){top=a;bottom=b;x=y=0;}break;
            case 'S':scrollUp(n);break;case 'T':scrollDown(n);break;
            case 'L':if(y>=top&&y<=bottom){int old=top;top=y;scrollDown(n);top=old;}break;
            case 'M':if(y>=top&&y<=bottom){int old=top;top=y;scrollUp(n);top=old;}break;
            default:break;
        }
    }
    private void sgr(String[] p){int fg=style.foreground,bg=style.background;boolean bold=style.bold,underline=style.underline,inverse=style.inverse;
        for(int i=0;i<p.length;i++){int n=value(p,i,0);
            if(n==0){fg=bg=-1;bold=underline=inverse=false;}else if(n==1)bold=true;else if(n==22)bold=false;else if(n==4)underline=true;else if(n==24)underline=false;else if(n==7)inverse=true;else if(n==27)inverse=false;
            else if(n==39)fg=-1;else if(n==49)bg=-1;else if(n>=30&&n<=37)fg=color(n-30);else if(n>=40&&n<=47)bg=color(n-40);else if(n>=90&&n<=97)fg=color(n-90+8);else if(n>=100&&n<=107)bg=color(n-100+8);
            else if(n==38||n==48){int result=-2;if(i+2<p.length&&value(p,i+1,-1)==5){result=color(value(p,i+2,0));i+=2;}else if(i+4<p.length&&value(p,i+1,-1)==2){result=(Math.min(255,value(p,i+2,0))<<16)|(Math.min(255,value(p,i+3,0))<<8)|Math.min(255,value(p,i+4,0));i+=4;}if(result!=-2){if(n==38)fg=result;else bg=result;}}
        }style=new Style(fg,bg,bold,underline,inverse);
    }
    static int color(int index){int[] basic={0x171717,0xcc5555,0x55bb66,0xc9b458,0x6699ee,0xbb77cc,0x55bbbb,0xcccccc,0x777777,0xff7777,0x77dd88,0xffdd77,0x88bbff,0xdd99ee,0x77dddd,0xffffff};if(index<0||index>255)return 0xcccccc;if(index<16)return basic[index];if(index>=232){int v=8+(index-232)*10;return v*0x010101;}int[] levels={0,95,135,175,215,255};int v=index-16;return levels[v/36]<<16|levels[v/6%6]<<8|levels[v%6];}
    private int end(Line line){int n=columns;while(n>0&&line.chars[n-1]==' '&&line.styles[n-1].background==-1&&!line.styles[n-1].inverse)n--;return n;}
    synchronized Snapshot snapshot(){StringBuilder text=new StringBuilder();List<Run> runs=new ArrayList<>();List<Line> lines=new ArrayList<>();if(!alternate)lines.addAll(history);int last=rows;while(last>y+1&&end(screen[last-1])==0)last--;for(int r=0;r<last;r++)lines.add(screen[r]);
        for(int row=0;row<lines.size();row++){Line line=lines.get(row);int limit=end(line);for(int i=0;i<limit;){int j=i+1;while(j<limit&&line.styles[j]==line.styles[i])j++;int start=text.length();text.append(line.chars,i,j-i);runs.add(new Run(start,text.length(),line.styles[i]));i=j;}if(row+1<lines.size())text.append('\n');}return new Snapshot(text.toString(),runs);}
    synchronized String text(){return snapshot().text;}
}
