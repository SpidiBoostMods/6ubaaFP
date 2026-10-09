package net.spidiboost.sixubaafp;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class InventoryScanTest {
    static ServerMenus.Item item(String id,String name){return new ServerMenus.Item(id,name);}
    static final List<ServerMenus.Item> MATCH=List.of(item("minecraft:compass","Custom Compass"));
    static final List<ServerMenus.Item> EMPTY=Collections.nCopies(36,item("minecraft:air",""));
    static final class H implements InventoryScan.Port {
        long time;int finished;boolean writable=true,sendBroken;List<String> online=new ArrayList<>(List.of("A","B","C"));
        List<String> sent=new ArrayList<>(),logs=new ArrayList<>();List<Integer> closed=new ArrayList<>();
        List<String> saved=List.of();FpScan.Result result;
        InventoryScan scan=new InventoryScan(this,()->time,InventoryQuery.parse("minecraft:compass"));
        public void send(String c){if(sendBroken)throw new IllegalStateException("disconnected while sending");sent.add(c);}public Collection<String> online(){return online;}
        public void close(int sync){closed.add(sync);scan.closed(sync);} // Exercise synchronous close callback too.
        public boolean checkpoint(List<String> names){saved=List.copyOf(names);return writable;}
        public void log(String text){logs.add(text);}public void finish(FpScan.Result r){finished++;result=r;}
        void start(String... names){scan.start(List.of(names));}
        void tick(long ms){time+=ms;scan.tick();}
        void menu(int sync,List<ServerMenus.Item> items){scan.opened(sync,"Player");scan.contents(sync,items);tick(5);}
    }
    @Test void registryAndExactRenamedDisplayNameCommaUnicodeFormattingAndCase(){
        var q=InventoryQuery.parse(" Minecraft:Compass, §bАлмазный  МЕЧ , minecraft:compass ");
        assertEquals(2,q.terms().size());assertFalse(q.matches(item("minecraft:compass","Anything")));
        assertFalse(q.matches(item("minecraft:stick","§6Алмазный меч")));
        assertTrue(q.matches(List.of(item("minecraft:compass","Anything"),item("minecraft:stick","§6Алмазный меч"))));
        assertFalse(q.matches(item("minecraft:diamond_sword","Алмазный меч героя")));
        assertFalse(q.matches(item("minecraft:air","Алмазный меч")));
        assertFalse(q.matches(item("minecraft:recovery_compass","Custom Compass")));
        for(String bad:List.of("",",", "minecraft:compass,", "minecraft:compass,,x", "minecraft: плохой"))assertThrows(IllegalArgumentException.class,()->InventoryQuery.parse(bad));
    }
    @Test void snapshotTabOnlyOneRequestAtATimeNoDupeOrHistory(){
        H h=new H();h.start("A","a","B","C","bad nickname");assertEquals(List.of("invsee A"),h.sent);
        h.menu(1,MATCH);assertEquals(List.of("A"),h.saved);assertEquals(1,h.sent.size());
        h.tick(4);assertEquals(1,h.sent.size());h.tick(1);assertEquals("invsee B",h.sent.getLast());
        h.menu(2,EMPTY);h.tick(5);assertEquals("invsee C",h.sent.getLast());
        h.menu(3,List.of(item("minecraft:compass","Алмазный меч")));h.tick(5);
        assertEquals(List.of("A","C"),h.result.names());assertTrue(h.result.complete());
        assertEquals(List.of(1,2,3),h.closed);assertEquals(1,h.finished);
    }
    @Test void unloadedMenuAndSlotOnlyUpdatesCannotQualifyAsEmpty(){
        H h=new H();h.start("A","B");h.scan.opened(4,"Player");h.scan.changed(4,EMPTY);
        h.tick(500);assertEquals(1,h.sent.size());assertTrue(h.saved.isEmpty());assertTrue(h.closed.isEmpty());
        h.scan.contents(4,MATCH);h.tick(4);assertTrue(h.closed.isEmpty());h.tick(1);assertEquals(List.of("A"),h.saved);
    }
    @Test void trulyEmptyFullPacketAdvancesImmediatelyWithoutCooldown(){
        H h=new H();h.start("A","B");h.menu(9,EMPTY);h.tick(5);assertEquals(10,h.time);assertEquals("invsee B",h.sent.getLast());
    }
    @Test void wrongWindowAndWrongSyncCannotBeAssignedToCurrentPlayer(){
        H h=new H();h.start("A");h.scan.opened(1,"Выбор сервера");h.scan.contents(1,MATCH);h.tick(100);assertTrue(h.saved.isEmpty());
        h.scan.opened(2,"Player");h.scan.contents(1,MATCH);h.tick(100);assertTrue(h.saved.isEmpty());
        h.menu(2,EMPTY);h.tick(5);assertTrue(h.result.names().isEmpty());
    }
    @Test void lateSlotUpdatesWithinFiveMsAreIncluded(){
        H h=new H();h.start("A");h.scan.opened(1,"Player");h.scan.contents(1,EMPTY);h.tick(4);h.scan.changed(1,MATCH);h.tick(4);assertTrue(h.saved.isEmpty());h.tick(1);h.tick(5);assertEquals(List.of("A"),h.result.names());
    }
    @Test void refusedPlayerMovesOnButOtherChatCannotSkip(){
        H h=new H();h.start("A","B");h.scan.message(FpProtocol.Line.plain("[19:00:00] Игрок C не найден."));h.tick(100);assertEquals(1,h.sent.size());
        h.scan.message(FpProtocol.Line.plain("[19:00:00] Игрок A не в сети."));h.tick(5);assertEquals("invsee B",h.sent.getLast());assertTrue(h.scan.active());
    }
    @Test void leavingTabIsSkippedAndNewlyJoinedPlayersNotAdded(){
        H h=new H();h.start("A","B");h.menu(1,MATCH);h.online=List.of("A","New");h.tick(5);assertTrue(h.result.complete());assertEquals(List.of("invsee A"),h.sent);
    }
    @Test void missingWindowRetriesSameOwnerIndefinitelyAndStopKeepsPartial(){
        for(boolean opened:List.of(false,true)){
            H h=new H();h.start("A","B","C");h.menu(1,MATCH);h.tick(5);if(opened)h.scan.opened(2,"Player");
            for(int i=0;i<30;i++)h.tick(5000);assertTrue(h.scan.active());assertNull(h.result);assertEquals(List.of("A"),h.saved);
            assertTrue(h.sent.subList(1,h.sent.size()).stream().allMatch("invsee B"::equals));
            int count=h.sent.size();h.scan.stop("user");h.tick(10000);assertEquals(count,h.sent.size());assertEquals(List.of("A"),h.result.names());assertEquals(List.of("B"),h.result.unresolved());assertEquals(1,h.finished);
        }
    }
    @Test void disconnectDuringInitialSendOrLaterSendCannotEscapeAndKeepsPartial(){
        H first=new H();first.sendBroken=true;assertDoesNotThrow(()->first.start("A"));assertFalse(first.scan.active());assertEquals(1,first.finished);assertEquals(List.of("A"),first.result.unresolved());
        H later=new H();later.start("A","B");later.menu(1,MATCH);later.sendBroken=true;assertDoesNotThrow(()->later.tick(5));assertFalse(later.scan.active());assertEquals(List.of("A"),later.saved);assertEquals(List.of("A"),later.result.names());assertEquals(List.of("B"),later.result.unresolved());
        later.tick(100000);assertEquals(1,later.finished);
    }
    @Test void stopAfterAnyStagePreservesCheckpointAndNeverRestarts(){
        for(int stage=0;stage<4;stage++){
            H h=new H();h.start("A","B");if(stage>0){h.menu(1,MATCH);h.tick(5);}if(stage>1)h.scan.opened(2,"Player");if(stage>2)h.scan.contents(2,MATCH);
            h.scan.stop("disconnect/user/update");int count=h.sent.size();h.scan.stop("again");h.tick(100000);h.scan.opened(3,"Player");h.scan.contents(3,MATCH);
            assertEquals(stage==0?List.of():List.of("A"),h.result.names());assertEquals(count,h.sent.size());assertEquals(1,h.finished);
        }
    }
    @Test void deniedPermissionAndWriteFailureSavePartialAndStop(){
        H h=new H();h.start("A","B");h.menu(1,MATCH);h.tick(5);h.scan.message(FpProtocol.Line.plain("Нет прав на использование команды"));assertFalse(h.scan.active());assertEquals(List.of("A"),h.result.names());
        H failed=new H();failed.writable=false;failed.start("A","B");failed.menu(1,MATCH);assertFalse(failed.scan.active());assertEquals(List.of("A"),failed.result.names());assertFalse(failed.result.complete());
    }
    @Test void earlyServerCloseRetriesSameOwnerInsteadOfFalseEmptyOrAborting(){
        H h=new H();h.start("A","B");h.scan.opened(1,"Player");h.scan.closed(1);assertNull(h.result);assertTrue(h.scan.active());h.tick(250);assertEquals(List.of("invsee A","invsee A"),h.sent);
    }
    @Test void threadConfinementRejectsBackgroundMutation()throws Exception{
        H h=new H();h.start("A");var error=new java.util.concurrent.atomic.AtomicReference<Throwable>();Thread t=new Thread(()->{try{h.scan.tick();}catch(Throwable e){error.set(e);}});t.start();t.join();assertInstanceOf(IllegalStateException.class,error.get());
    }
    @Test void replacementAndRepeatedWindowsAreDrainedWithoutWrongOwner(){
        H h=new H();h.start("A","B");h.scan.opened(1,"Player");h.scan.opened(2,"Player");h.scan.contents(1,MATCH);h.tick(5);assertTrue(h.saved.isEmpty());h.scan.contents(2,EMPTY);h.tick(5);assertTrue(h.scan.active());assertEquals(1,h.sent.size());
        H late=new H();late.start("A","B");late.tick(1500);late.menu(1,MATCH);late.scan.opened(2,"Player");late.scan.contents(2,EMPTY);late.tick(100);assertEquals(2,late.sent.size());assertEquals(List.of("A"),late.saved);
        late.tick(1900);late.tick(5);assertEquals("invsee B",late.sent.getLast());late.menu(3,EMPTY);late.tick(5);assertEquals(List.of("A"),late.result.names());
    }
    @Test void scannerCanBeReusedWithoutOldMatchesAndInvalidNames(){
        H h=new H();h.start("A");h.menu(1,MATCH);h.tick(5);h.start("B");h.menu(2,EMPTY);h.tick(5);assertTrue(h.result.names().isEmpty());assertEquals(List.of("invsee A","invsee B"),h.sent);
    }
    @Test void genericUnavailableResponseSkipsOnlyTheOneCurrentRequest(){
        H h=new H();h.start("A","B");h.scan.message(FpProtocol.Line.plain("Игрок не найден."));h.tick(5);assertEquals("invsee B",h.sent.getLast());
    }
    @Test void exactErrorPrefixCaseTimestampAndFormattingSkipImmediately(){
        for(String text:List.of("Ошибка: Игрок не найден.","[19:01:00] §cОшибка: §fИгрок не найден.","ERROR: Player not found.","ошибка: Игрок A не в сети.")){
            H h=new H();h.start("A","B");h.scan.message(FpProtocol.Line.plain(text));h.tick(5);assertEquals("invsee B",h.sent.getLast(),text);assertNull(h.result);
        }
    }
    @Test void currentPlayerLeavingTabWhileWaitingDoesNotRetryForever(){
        H h=new H();h.start("A","B");h.online=List.of("B");h.tick(1);h.tick(2000);h.tick(5);assertEquals(List.of("invsee A","invsee B"),h.sent);
        h.menu(1,MATCH);h.tick(5);assertEquals(List.of("B"),h.result.names());assertTrue(h.result.complete());
    }
    @Test void duplicateResponsesAfterRetryCannotBecomeNextPlayersItems(){
        H h=new H();h.start("A","B");h.tick(1500);h.menu(1,MATCH);h.tick(100);h.scan.opened(2,"Player");h.scan.contents(2,MATCH);h.tick(1999);assertEquals(List.of("invsee A","invsee A"),h.sent);
        h.tick(1);h.tick(5);assertEquals("invsee B",h.sent.getLast());h.menu(3,EMPTY);h.tick(5);assertEquals(List.of("A"),h.result.names());
    }
    @Test void thousandPlayersSerialLargeSnapshotHasNoDuplicatesOrHiddenCommands(){
        H h=new H();h.online=new ArrayList<>();for(int i=0;i<1000;i++)h.online.add("Nick"+i);h.scan.start(h.online);
        for(int i=0;i<1000;i++){h.menu(i+1,i%2==0?MATCH:EMPTY);h.tick(5);}
        assertTrue(h.result.complete());assertEquals(1000,h.sent.size());assertEquals(500,h.result.names().size());assertEquals(10000,h.time);
    }
}
