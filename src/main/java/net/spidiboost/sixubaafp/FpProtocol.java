package net.spidiboost.sixubaafp;

import java.util.*;
import java.util.regex.*;

/** Styled protocol primitives. Colors are attached to characters, not chat screenshots. */
public final class FpProtocol {
    private FpProtocol() {}
    private static final Pattern TIME=Pattern.compile("^\\s*\\[\\d{1,2}:\\d{2}:\\d{2}]\\s*");
    public static final Pattern NICK=Pattern.compile("(?<![A-Za-z0-9_])([A-Za-z0-9_]{1,16})(?![A-Za-z0-9_])");
    public record Line(String text, int[] colors) {
        public Line { if(text.length()!=colors.length) throw new IllegalArgumentException("color length"); colors=colors.clone(); }
        public static Line plain(String text) { int[] c=new int[text.length()]; Arrays.fill(c,-1); return new Line(text,c); }
        public static Line colored(String text,int rgb) { int[] c=new int[text.length()]; Arrays.fill(c,rgb); return new Line(text,c); }
        public Line clean() {
            StringBuilder s=new StringBuilder(); List<Integer> c=new ArrayList<>(); int legacy=-1;
            for(int i=0;i<text.length();i++) {
                char ch=text.charAt(i);
                if(ch=='§' && i+1<text.length()) {
                    char code=Character.toLowerCase(text.charAt(++i));
                    int digit="0123456789abcdef".indexOf(code);
                    if(digit>=0) legacy=new int[]{0,0x0000aa,0x00aa00,0x00aaaa,0xaa0000,0xaa00aa,0xffaa00,0xaaaaaa,0x555555,0x5555ff,0x55ff55,0x55ffff,0xff5555,0xff55ff,0xffff55,0xffffff}[digit];
                    if(code=='r') legacy=-1;
                    if(code=='x' && i+12<text.length()) {
                        StringBuilder hex=new StringBuilder(); int end=i;
                        for(int n=0;n<6;n++) { if(end+2>=text.length()||text.charAt(end+1)!='§') break; hex.append(text.charAt(end+2)); end+=2; }
                        try { if(hex.length()==6) {legacy=Integer.parseInt(hex.toString(),16);i=end;} } catch(NumberFormatException ignored) {}
                    }
                    continue;
                }
                if(ch=='\u200b'||ch=='\ufeff') continue;
                s.append(ch=='\u00a0'||ch=='\u202f'?' ':ch); c.add(legacy>=0?legacy:colors[i]);
            }
            String value=s.toString(); int begin=0,end=value.length();
            while(begin<end && Character.isWhitespace(value.charAt(begin))) begin++;
            while(end>begin && Character.isWhitespace(value.charAt(end-1))) end--;
            Matcher stamp=TIME.matcher(value.substring(begin,end));
            while(stamp.find()) {begin+=stamp.end();stamp=TIME.matcher(value.substring(begin,end));}
            int[] rgb=new int[end-begin]; for(int i=begin;i<end;i++) rgb[i-begin]=c.get(i);
            return new Line(value.substring(begin,end),rgb);
        }
        public List<Line> lines() {
            List<Line> result=new ArrayList<>(); Matcher newline=Pattern.compile("\\R").matcher(text); int begin=0;
            while(newline.find()) { result.add(new Line(text.substring(begin,newline.start()),Arrays.copyOfRange(colors,begin,newline.start())).clean());begin=newline.end(); }
            result.add(new Line(text.substring(begin),Arrays.copyOfRange(colors,begin,colors.length)).clean());return result;
        }
        public boolean yellowName(int begin,int end) {
            for(int i=begin;i<end;i++) if(!yellow(colors[i])) return false;
            return end>begin;
        }
        public boolean eligibleName(int begin,int end) {
            for(int i=begin;i<end;i++) if(!yellow(colors[i])&&!red(colors[i])) return false;
            return end>begin;
        }
    }
    public static boolean yellow(int color) {
        if(color<0) return false;
        double r=(color>>16&255)/255d,g=(color>>8&255)/255d,b=(color&255)/255d;
        double max=Math.max(r,Math.max(g,b)),min=Math.min(r,Math.min(g,b)),delta=max-min;
        if(max<.55 || delta<.4*max) return false;
        double hue=max==r?60*((g-b)/delta):max==g?60*((b-r)/delta+2):60*((r-g)/delta+4);
        if(hue<0) hue+=360;
        return hue>=35 && hue<=70;
    }
    public static String normalize(String text) {
        return Line.plain(text).clean().text().toLowerCase(Locale.ROOT).replace('ё','е')
                .replaceAll("[\\p{Cf}]","").replaceAll("(?U)[\\s\\p{Z}]+"," ").strip();
    }
    public static boolean red(int color) {
        if(color<0)return false;
        double r=(color>>16&255)/255d,g=(color>>8&255)/255d,b=(color&255)/255d;
        double max=Math.max(r,Math.max(g,b)),min=Math.min(r,Math.min(g,b)),delta=max-min;
        if(max<.35||delta<.4*max||max!=r)return false;
        double hue=60*(g-b)/delta;if(hue<0)hue+=360;
        return hue<=15||hue>=345;
    }
    public static boolean market(String reason) {
        String value=normalize(reason).replaceAll("(?U)[\\s\\p{P}]+","");
        return value.contains("fp")||value.contains("funpay")||value.contains("playerok");
    }
    public static boolean valid(String name) {return name!=null&&name.matches("[A-Za-z0-9_]{1,16}");}
    public static String key(String value) {return value.toLowerCase(Locale.ROOT);}
}
