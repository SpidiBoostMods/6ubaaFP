package net.spidiboost.sixubaafp;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FpRunLedgerTest {
    static class H implements FpScan.Port {
        long time;int clears;String self="Self";List<String> online=List.of("Self","Twin1","Twin2","Seed");
        final List<String> sent=new ArrayList<>();final FpRunLedger ledger=new FpRunLedger();FpScan scan;FpScan.Result result;
        public String currentName(){return self;}public Collection<String> online(){return online;}
        public void log(String message){}public void clearChat(){clears++;}public void finish(FpScan.Result r){result=r;}
        public void send(String command){sent.add(command);}
        void start(String... names){result=null;scan=new FpScan(this,()->time,ledger);scan.start(List.of(names),false);}
        void tick(long ms){time+=ms;scan.tick();}void feed(String packet){scan.accept(FpProtocol.Line.plain(packet));}
        void dupe(String owner,String body){feed("Сканирование "+owner+".\n"+body);tick(5);}
        void hist(String owner,String reason,int limit){feed("История "+owner+" (Лимит: "+limit+"): [CopyFull]\n"+owner+" был забанен куратором Mod\nПо причине: "+reason+" [Горит]");tick(5);}
        void drain(){for(int i=0;scan.active()&&i<100;i++)tick(100);assertFalse(scan.active());}
    }
    @Test void selfIsExcludedFromSeedsAndColoredDupeAndUsesLiveAccount(){
        H h=new H();h.start("Self","Seed","Twin2");assertEquals(List.of("dupeip Seed"),h.sent);
        h.dupe("Seed","§cSelf§7, §eTwin1§7, §eTwin2");assertEquals("hist Twin1 ban 100",h.sent.getLast());
        h.self="Twin2";h.hist("Twin1","FP_RW",7);h.drain();
        assertEquals(List.of("dupeip Seed","hist Twin1 ban 100"),h.sent);assertEquals(List.of("Twin1"),h.result.names());
    }
    @Test void repeatedGroupsAcrossGriefsReuseCacheWithoutAnyRepeatedCommands(){
        H h=new H();h.start("Seed","Twin1");h.dupe("Seed","§cBanned§7, §eTwin1§7, §eTwin2");
        h.hist("Banned","Funpay",2);h.hist("Twin1","Читы",7);h.hist("Twin2","Читы",1);
        h.dupe("Twin1","§eTwin1§7, §4Banned§7, §eTwin2");h.drain();
        assertEquals(List.of("Banned (На сервере: Twin1, Twin2)"),h.result.names());
        int commands=h.sent.size();h.start("Seed","Twin1");h.drain();assertEquals(commands,h.sent.size());
        assertEquals(List.of("Banned (На сервере: Twin1, Twin2)"),h.result.names());
        assertEquals(1,Collections.frequency(h.sent,"hist Banned ban 100"));
        assertEquals(1,Collections.frequency(h.sent,"hist Twin1 ban 100"));
    }
    @Test void annotationUsesOnlyYellowTwinsPresentInCurrentTab(){
        H h=new H();h.start("Seed");h.dupe("Seed","§cBanned§7, §eTwin1§7, §eGone§7, §cRedTwin§7, §7Gray§7, §eSelf");
        h.hist("Banned","FP",1);assertEquals(List.of("Banned (На сервере: Twin1, Self)"),h.scan.matches());
        h.online=List.of("Self");assertEquals(List.of("Banned (На сервере: Self)"),h.scan.matches());
        h.scan.stop("stop");
    }
    @Test void yellowFindingNeverHasBannedAnnotation(){
        H h=new H();h.start("Seed");h.dupe("Seed","§eTwin1§7, §eTwin2");h.hist("Twin1","fp",1);h.hist("Twin2","Читы",1);h.drain();assertEquals(List.of("Twin1"),h.result.names());
    }
    @Test void lateRowsAfterStructurallyCompleteHistoryAreNotAssignedToNextNick(){
        H h=new H();h.start("Seed");h.dupe("Seed","§eTwin1§7, §eTwin2");h.hist("Twin1","Читы",7);
        assertEquals("hist Twin2 ban 100",h.sent.getLast());
        h.feed("Twin1 был забанен куратором Mod\nПо причине: FP_RW [Горит]");
        h.hist("Twin2","Читы",1);h.drain();assertEquals(List.of("Twin1"),h.result.names());assertEquals(3,h.sent.size());
    }
    @Test void globalCounterClearsAtTwentyAcrossGriefsAndKeepsFindings(){
        class Auto extends H {
            @Override public void send(String command){super.send(command);String n=command.split(" ")[1];
                if(command.startsWith("dupeip"))feed("Сканирование "+n+".\n§6"+n);
                else feed("История "+n+" (Лимит: 7):\n"+n+" был забанен куратором Mod\nПо причине: FP_RW [Горит]");
            }
        }
        Auto h=new Auto();for(int page=0;page<3;page++){
            String[] names=new String[15];for(int i=0;i<15;i++)names[i]="Nick"+(page*15+i);
            h.start(names);for(int i=0;h.scan.active()&&i<1000;i++)h.tick(5);
            assertNotNull(h.result);assertEquals(15,h.result.names().size());assertTrue(h.result.complete());
        }
        assertEquals(2,h.clears);assertEquals(45,h.ledger.completed);assertEquals(45,h.ledger.findings.size());
        assertEquals(90,h.sent.size());assertEquals(90,new HashSet<>(h.sent).size());
    }
    @Test void newManualRunGetsFreshLedger(){
        H h=new H();h.start("Seed");h.dupe("Seed","§eTwin1");h.hist("Twin1","Читы",1);h.drain();
        h.scan=new FpScan(h,()->h.time);h.scan.start(List.of("Seed"),false);
        assertEquals(2,Collections.frequency(h.sent,"dupeip Seed"));
    }
    @Test void advertisedLimitAndBurningStatusNeverCauseSixSecondRetry(){
        for(int limit:new int[]{1,2,7,100}){
            H h=new H();h.start("Seed","Twin2");h.dupe("Seed","§eTwin1");h.hist("Twin1","Читы",limit);
            assertEquals("dupeip Twin2",h.sent.getLast());assertEquals(10,h.time);
            h.dupe("Twin2","§eTwin1");h.drain();assertTrue(h.result.complete());assertEquals(3,h.sent.size());
        }
    }
    @Test void genericErrorPrefixSkipsUnavailableHistoryAtNormalGap(){
        H h=new H();h.start("Seed","Twin2");h.dupe("Seed","§eTwin1");
        h.feed("[18:45:23] Ошибка: Игрок не найден.");h.tick(5);
        assertEquals("dupeip Twin2",h.sent.getLast());assertEquals(10,h.time);assertEquals(1,h.ledger.completed);
    }
}
