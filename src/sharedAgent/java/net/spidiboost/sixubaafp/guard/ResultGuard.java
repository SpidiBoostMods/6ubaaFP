package net.spidiboost.sixubaafp.guard;

import java.awt.Desktop;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.CompletableFuture;

/** Independent watchdog: a JVM crash cannot prevent opening already checkpointed output. */
public final class ResultGuard {
    private ResultGuard(){}
    public static void main(String[] args){
        try {
            var in=new DataInputStream(System.in);long pid=in.readLong();Path file=Path.of(read(in)).toAbsolutePath().normalize();Path receipt=Path.of(read(in));
            if(!file.getFileName().toString().matches("6ubaafp(?:-inv)?\\.txt"))throw new IOException("Unexpected result file");
            var ready=new CompletableFuture<Boolean>();var parent=ProcessHandle.of(pid);
            if(parent.isEmpty()||!parent.get().isAlive())ready.complete(true);else parent.get().onExit().thenRun(()->ready.complete(true));
            Thread commands=new Thread(()->{try{int command=in.read();if(command==1)ready.complete(true);else if(command==0)ready.complete(false);}catch(IOException ignored){}},"result-guard-control");commands.setDaemon(true);commands.start();
            System.out.println("READY");System.out.flush();
            if(ready.join()&&Files.isRegularFile(file)){
                if(!Boolean.getBoolean("sixubaafp.guard.dryRun"))open(file);
                Files.writeString(receipt,"OPENED "+file,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
            }
        }catch(Throwable e){System.err.println("Result guard: "+e.getClass().getSimpleName());System.exit(1);}
        System.exit(0);
    }
    private static String read(DataInputStream in)throws IOException{int count=in.readInt();if(count<1||count>65536)throw new IOException("Invalid path length");byte[] value=in.readNBytes(count);if(value.length!=count)throw new EOFException();return new String(value,StandardCharsets.UTF_8);}
    private static void open(Path file)throws IOException{
        if(Desktop.isDesktopSupported()&&Desktop.getDesktop().isSupported(Desktop.Action.OPEN)){
            try{Desktop.getDesktop().open(file.toFile());return;}catch(IOException|RuntimeException ignored){}
        }
        String os=System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT);
        if(os.startsWith("mac"))new ProcessBuilder("/usr/bin/open","-t",file.toString()).start();
        else if(os.startsWith("windows"))new ProcessBuilder("rundll32.exe","url.dll,FileProtocolHandler",file.toString()).start();
        else new ProcessBuilder("xdg-open",file.toString()).start();
    }
}
