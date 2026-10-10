package net.spidiboost.sixubaafp.updates;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class QuietUpdaterTest {
    @TempDir Path root;
    private static Object installed(ModCatalog mod,String version,Path jar)throws Exception {
        var type=Class.forName(SharedUpdater.class.getName()+"$Installed");
        var ctor=type.getDeclaredConstructors()[0];ctor.setAccessible(true);return ctor.newInstance(mod,version,jar);
    }
    private Object inspect(SharedUpdater updater,Object installed,boolean activate)throws Exception {
        var method=SharedUpdater.class.getDeclaredMethod("prepare",installed.getClass(),Path.class,Path.class,boolean.class);
        method.setAccessible(true);return method.invoke(updater,installed,root,root.resolve(".spidiboost-updates"),activate);
    }
    private static byte[] manifest(ModCatalog mod,String version) {
        return ("version="+version+"\nminecraft=1.21.4\nartifact="+mod.artifact(version)+"\nsha256="+"a".repeat(64)).getBytes(StandardCharsets.UTF_8);
    }
    @Test void failedChecksBackOffAndHealthyResponseResetsTheDelay() {
        var checks=new UpdateChecks();for(long expected:new long[]{30,60,120,240,300,300,300})assertEquals(expected,checks.failedDelaySeconds());
        checks.success();assertEquals(30,checks.failedDelaySeconds());
        for(int i=0;i<1000;i++)assertTrue(checks.failedDelaySeconds()<=300);
    }
    @Test void timerCannotInheritActivationFromFailedEntry() {
        var checks=new UpdateChecks();assertTrue(checks.request(UpdateChecks.Mode.ACTIVATE));
        checks.failedDelaySeconds();assertNull(checks.finish());
        assertTrue(checks.request(UpdateChecks.Mode.DISCOVERY));assertNull(checks.finish());
    }
    @Test void queuedJoinWinsOverAllManualAndTimerDiscoveryChecks() {
        for(var first:UpdateChecks.Mode.values())for(var next:UpdateChecks.Mode.values()){
            var checks=new UpdateChecks();assertTrue(checks.request(first));assertFalse(checks.request(next));
            assertFalse(checks.request(UpdateChecks.Mode.DISCOVERY));assertEquals(next,checks.finish());
            assertTrue(checks.request(next));assertNull(checks.finish());
        }
        var checks=new UpdateChecks();assertTrue(checks.request(UpdateChecks.Mode.DISCOVERY));
        assertFalse(checks.request(UpdateChecks.Mode.DISCOVERY));assertFalse(checks.request(UpdateChecks.Mode.ACTIVATE));
        assertEquals(UpdateChecks.Mode.ACTIVATE,checks.finish());
    }
    @Test void concurrentEntryEventsElectOneCheckAndKeepActivation()throws Exception {
        var checks=new UpdateChecks();var pool=Executors.newFixedThreadPool(8);var elected=new AtomicInteger();
        try{var tasks=new ArrayList<Future<?>>();for(int i=0;i<100;i++)tasks.add(pool.submit(()->{if(checks.request(UpdateChecks.Mode.ACTIVATE))elected.incrementAndGet();}));for(var task:tasks)task.get();}
        finally{pool.shutdown();}assertEquals(1,elected.get());assertEquals(UpdateChecks.Mode.ACTIVATE,checks.finish());
    }
    @Test void discoveryOnlyFetchesManifestForCurrentOlderAndNewerVersions()throws Exception {
        var mod=ModCatalog.ALL.get(2);var calls=new AtomicInteger();
        var updater=new SharedUpdater((uri,limit)->{assertEquals(mod.latest(),uri);calls.incrementAndGet();return manifest(mod,"1.5.2");});
        for(String current:List.of("1.5.1","1.5.2","1.5.3")){
            assertNull(inspect(updater,installed(mod,current,root.resolve("mods/old-name.jar")),false));
            var status=SharedUpdater.status(mod.id());assertEquals("1.5.2",status.get("latest"));
            assertEquals(current.equals("1.5.1")?"available":"current",status.get("state"));
        }
        assertEquals(3,calls.get());assertFalse(Files.exists(root.resolve(".spidiboost-updates")));
        assertFalse(Files.exists(root.resolve("mods")));
        for(String field:List.of("pendingHelper","pendingPlan")){var f=SharedUpdater.class.getDeclaredField(field);f.setAccessible(true);assertNull(f.get(updater));}
    }
    @Test void networkFailureIsSilentAndRecoveryOnlyDiscoversUntilNextEntry()throws Exception {
        var mod=ModCatalog.ALL.get(2);var calls=new AtomicInteger();
        var updater=new SharedUpdater((uri,limit)->{assertEquals(mod.latest(),uri);if(calls.incrementAndGet()<3)throw new IOException("offline");return manifest(mod,"1.5.2");});
        var m=installed(mod,"1.5.1",root.resolve("mods/old.jar"));
        assertNull(inspect(updater,m,true));assertEquals("unavailable",SharedUpdater.status(mod.id()).get("state"));
        assertNull(inspect(updater,m,false));assertNull(inspect(updater,m,false));
        assertEquals("available",SharedUpdater.status(mod.id()).get("state"));
        assertTrue(SharedUpdater.status(mod.id()).get("status").contains("следующего"));
        assertFalse(Files.exists(root.resolve(".spidiboost-updates")));
        assertFalse(Files.exists(root.resolve("mods")));
    }
    @Test void currentVersionAtEntryIsSilentAndDoesNotCreateFiles()throws Exception {
        var mod=ModCatalog.ALL.get(2);var updater=new SharedUpdater((uri,limit)->manifest(mod,"1.5.2"));
        assertNull(inspect(updater,installed(mod,"1.5.2",root.resolve("mods/"+mod.artifact("1.5.2"))),true));
        assertEquals("current",SharedUpdater.status(mod.id()).get("state"));
        assertFalse(Files.exists(root.resolve("mods")));assertFalse(Files.exists(root.resolve(".spidiboost-updates")));
    }
    @Test void failureCannotFallBackToActivatingACachedRelease()throws Exception {
        var mod=ModCatalog.ALL.get(2);var updater=new SharedUpdater((uri,limit)->{throw new IOException("offline");});
        var cached=new BatchPlan.Change(root.resolve("mods/old.jar"),root.resolve("mods/new.jar"),root.resolve("stage.jar"),"a".repeat(64),"b".repeat(64));
        var field=SharedUpdater.class.getDeclaredField("prepared");field.setAccessible(true);
        @SuppressWarnings("unchecked") var prepared=(Map<String,BatchPlan.Change>)field.get(updater);prepared.put(mod.id(),cached);
        assertNull(inspect(updater,installed(mod,"1.5.1",cached.target()),true));
        assertNull(inspect(updater,installed(mod,"1.5.1",cached.target()),false));
        assertEquals(cached,prepared.get(mod.id()));assertFalse(Files.exists(root.resolve("mods")));
    }
    @Test void malformedOrUnsupportedManifestsDoNotAuthorizeInstallation()throws Exception {
        var mod=ModCatalog.ALL.get(2);var m=installed(mod,"1.5.1",root.resolve("mods/old.jar"));
        for(byte[] data:List.of(new byte[0],"version=broken".getBytes(StandardCharsets.UTF_8),new String(manifest(mod,"1.5.2"),StandardCharsets.UTF_8).replace("1.21.4","1.21.11").getBytes(StandardCharsets.UTF_8))){
            var updater=new SharedUpdater((uri,limit)->data);
            assertNull(inspect(updater,m,false));assertEquals("unavailable",SharedUpdater.status(mod.id()).get("state"));
        }
        assertFalse(Files.exists(root.resolve("mods")));
    }
}
