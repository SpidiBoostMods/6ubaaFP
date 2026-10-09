package net.spidiboost.sixubaafp;

import net.minecraft.text.*;
import net.minecraft.util.Identifier;
import java.util.*;

/** Copper → champagne → jade. Namespace markers ensure other mods' text is never recolored. */
public final class FpTheme {
    public static final Identifier ACCENT=Identifier.of("sixubaafp","accent"),BODY=Identifier.of("sixubaafp","body"),ALERT=Identifier.of("sixubaafp","alert");
    private static final Map<OrderedText,Boolean> OWNED=new WeakHashMap<>();
    private FpTheme(){}
    public static MutableText prefix(){return gradient("[6ubaaFP]",ACCENT,true,0xff8965,0xffd884,0x83e6bb).append(Text.literal("  "));}
    public static MutableText message(String value){return prefix().append(gradient(value,BODY,false,0xf7ede0,0xd8e9dc));}
    public static MutableText error(String value){return prefix().append(gradient("ВНИМАНИЕ  ",ALERT,true,0xff7f7a,0xffcc8e)).append(gradient(value,BODY,false,0xffd7c1,0xf9e9d5));}
    public static MutableText title(String value){return gradient(value,ACCENT,true,0xffa779,0xffe3a0,0xb7f0cf);}
    public static MutableText small(String value){return gradient(value,BODY,false,0xc4dfcf,0xf2e3c5);}
    public static MutableText gradient(String value,Identifier font,boolean bold,int... stops){
        if(stops.length<2)throw new IllegalArgumentException("Gradient requires at least two colors");
        var output=Text.empty();int[] points=value.codePoints().toArray();
        for(int i=0;i<points.length;i++){
            double p=points.length<=1?0:(double)i/(points.length-1)*(stops.length-1);int stop=Math.min(stops.length-2,(int)p);
            output.append(Text.literal(new String(Character.toChars(points[i]))).setStyle(Style.EMPTY.withColor(mix(stops[stop],stops[stop+1],p-stop)).withFont(font).withBold(bold)));
        }return output;
    }
    public static OrderedText animate(OrderedText text){
        Boolean owned=OWNED.get(text);if(owned==null){if(OWNED.size()>=256)OWNED.clear();owned=!text.accept((i,style,point)->!themed(style));OWNED.put(text,owned);}if(!owned)return text;
        return animate(text,System.nanoTime());
    }
    public static OrderedText animate(OrderedText text,long nanos){
        double phase=(nanos%7_000_000_000L)*2*Math.PI/7_000_000_000L;
        return sink->text.accept((i,style,point)->{
            if(!themed(style)||style.getColor()==null)return sink.accept(i,style,point);
            double glow=(Math.sin(phase-i*.28)+1)*.5*(style.getFont().equals(ACCENT)?.22:.055);
            return sink.accept(i,style.withFont(Style.DEFAULT_FONT_ID).withColor(mix(style.getColor().getRgb(),0xfff6db,glow)),point);
        });
    }
    private static boolean themed(Style s){return s.getFont().equals(ACCENT)||s.getFont().equals(BODY)||s.getFont().equals(ALERT);}
    static int mix(int a,int b,double p){int color=0;for(int shift=16;shift>=0;shift-=8)color|=(int)Math.round((a>>shift&255)+((b>>shift&255)-(a>>shift&255))*p)<<shift;return color;}
}
