package net.spidiboost.sixubaafp;

import java.nio.file.*;
import java.io.*;
import java.util.Properties;

/** GUI-scaled coordinates; absent/invalid configuration uses the action-bar anchor. */
public final class HudPlacement {
    private final Path file;private int x=-1,y=-1;private boolean dragging;private double dx,dy;
    public record Bounds(int x,int y,int width,int height){public boolean contains(double mx,double my){return mx>=x&&mx<x+width&&my>=y&&my<y+height;}}
    public HudPlacement(Path file){this.file=file;try(var r=Files.newBufferedReader(file)){var p=new Properties();p.load(r);x=Integer.parseInt(p.getProperty("x","-1"));y=Integer.parseInt(p.getProperty("y","-1"));if(x<0||y<0){x=y=-1;}}catch(IOException|NumberFormatException ignored){x=y=-1;}}
    public Bounds bounds(int sw,int sh,int w,int h){return new Bounds(clamp(x<0?(sw-w)/2:x,sw-w),clamp(y<0?sh-78-h:y,sh-h),w,h);}
    private static int clamp(int value,int max){return Math.max(0,Math.min(value,Math.max(0,max)));}
    public boolean press(double mx,double my,Bounds b){if(!b.contains(mx,my))return false;dragging=true;dx=mx-b.x();dy=my-b.y();x=b.x();y=b.y();return true;}
    public boolean drag(double mx,double my,int sw,int sh,int w,int h){if(!dragging)return false;x=clamp((int)Math.round(mx-dx),sw-w);y=clamp((int)Math.round(my-dy),sh-h);return true;}
    public boolean release()throws IOException{
        if(!dragging)return false;dragging=false;Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp=Files.createTempFile(file.toAbsolutePath().getParent(),"6ubaafp-hud-",".tmp");
        try{var p=new Properties();p.setProperty("x",Integer.toString(x));p.setProperty("y",Integer.toString(y));try(var writer=Files.newBufferedWriter(temp)){p.store(writer,"6ubaaFP HUD - scaled GUI coordinates");}
            try{Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(temp);}return true;
    }
}
