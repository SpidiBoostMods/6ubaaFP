package net.spidiboost.sixubaafp;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;

final class ResultWatch {
    private final Process process;private final DataOutputStream control;
    private ResultWatch(Process process,DataOutputStream control){this.process=process;this.control=control;}
    static ResultWatch start(Path result,Path session)throws IOException{
        Path jar=session.resolve("result-guard.jar");
        try(var in=ResultWatch.class.getResourceAsStream("/sixubaafp-shared-update-agent.jar")){if(in==null)throw new IOException("Missing result guard");Files.copy(in,jar);}
        Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java");
        // Paths travel as UTF-8 through a pipe, not the Windows process argument codepage.
        Process p=new ProcessBuilder(java.toString(),"-cp",jar.getFileName().toString(),"net.spidiboost.sixubaafp.guard.ResultGuard").directory(session.toFile()).redirectError(ProcessBuilder.Redirect.appendTo(session.resolve("viewer-guard.log").toFile())).start();
        var pipe=new DataOutputStream(p.getOutputStream());pipe.writeLong(ProcessHandle.current().pid());write(pipe,result.toAbsolutePath().toString());write(pipe,session.resolve("viewer-opened.txt").toAbsolutePath().toString());pipe.flush();
        var ack=CompletableFuture.supplyAsync(()->{try{return new BufferedReader(new InputStreamReader(p.getInputStream(),StandardCharsets.UTF_8)).readLine();}catch(IOException e){throw new CompletionException(e);}});
        try{if(!"READY".equals(ack.get(5,TimeUnit.SECONDS)))throw new IOException("Guard not ready");}catch(Exception e){p.destroy();throw new IOException("Result guard handshake failed",e);}
        return new ResultWatch(p,pipe);
    }
    private static void write(DataOutputStream out,String value)throws IOException{byte[] data=value.getBytes(StandardCharsets.UTF_8);out.writeInt(data.length);out.write(data);}
    synchronized boolean open(){if(!process.isAlive())return false;try{control.writeByte(1);control.flush();control.close();return true;}catch(IOException e){return false;}}
}
