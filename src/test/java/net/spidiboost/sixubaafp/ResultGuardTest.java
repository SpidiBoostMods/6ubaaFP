package net.spidiboost.sixubaafp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ResultGuardTest {
    @TempDir Path root;
    @Test void childOpensCheckpointAfterHardJvmDeathWithUnicodePathsAndNeverBefore()throws Exception{run(true);}
    @Test void normalFinishOpensOnceWhileOwningJvmStillAlive()throws Exception{run(false);}
    private void run(boolean crash)throws Exception{
        Path dir=root.resolve("инстанс с пробелами & Юникод");Files.createDirectories(dir);
        Path file=dir.resolve("6ubaafp-inv.txt"),receipt=dir.resolve("receipt.txt");FpFiles.write(file,java.util.List.of("VerifiedA VerifiedB - grief #3"));
        Path source=dir.resolve("Wait.java");Files.writeString(source,"public class Wait { public static void main(String[] a)throws Exception {System.out.println(\"LIVE\");System.out.flush();System.in.read();}}",StandardCharsets.UTF_8);
        Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java");
        assertEquals(0,new ProcessBuilder(java.resolveSibling(System.getProperty("os.name").startsWith("Windows")?"javac.exe":"javac").toString(),"-d",dir.toString(),source.toString()).inheritIO().start().waitFor());
        Process parent=new ProcessBuilder(java.toString(),"-cp",".","Wait").directory(dir.toFile()).start();assertEquals("LIVE",new BufferedReader(new InputStreamReader(parent.getInputStream())).readLine());
        Files.copy(Path.of("build/shared-agent/sixubaafp-shared-update-agent.jar"),dir.resolve("guard.jar"));
        Process guard=new ProcessBuilder(java.toString(),"-Dsixubaafp.guard.dryRun=true","-cp","guard.jar","net.spidiboost.sixubaafp.guard.ResultGuard").directory(dir.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try {
            var pipe=new DataOutputStream(guard.getOutputStream());pipe.writeLong(parent.pid());write(pipe,file.toString());write(pipe,receipt.toString());pipe.flush();assertEquals("READY",new BufferedReader(new InputStreamReader(guard.getInputStream())).readLine());assertFalse(Files.exists(receipt));
            if(crash){parent.destroyForcibly();assertTrue(parent.waitFor(5,TimeUnit.SECONDS));}else {pipe.writeByte(1);pipe.flush();assertTrue(parent.isAlive());}
            assertTrue(guard.waitFor(10,TimeUnit.SECONDS));assertEquals(0,guard.exitValue());assertEquals("OPENED "+file,Files.readString(receipt));assertEquals("VerifiedA VerifiedB - grief #3",Files.readString(file));
        }finally{parent.destroyForcibly();guard.destroyForcibly();}
    }
    private void write(DataOutputStream out,String value)throws IOException{byte[] data=value.getBytes(StandardCharsets.UTF_8);out.writeInt(data.length);out.write(data);}
}
