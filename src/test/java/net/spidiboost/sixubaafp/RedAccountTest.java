package net.spidiboost.sixubaafp;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class RedAccountTest {
    @Test void redDarkRedAndCloseShadesAlongsideGold(){
        for(int color:new int[]{0xff5555,0xaa0000,0xff0000,0xc03030,0xee3355,0xffaa00,0xffff55,0xffd240})
            assertTrue(FpProtocol.Line.colored("Nick",color).eligibleName(0,4),Integer.toHexString(color));
        for(int color:new int[]{-1,0xffffff,0xaaaaaa,0x55ff55,0xff55ff,0x5555ff,0x333300,0x220000,0xff8800})
            assertFalse(FpProtocol.Line.colored("Nick",color).eligibleName(0,4),Integer.toHexString(color));
    }
    @Test void darkRedBanFlagAloneDoesNotQualifyGrayNickname(){
        var h=new FpScanTest.H();h.start("6ubaa");h.dupe("6ubaa","§76ubaa §4[C]");h.drain();assertEquals(1,h.sent.size());
    }
    @Test void redNicknamesAreQueriedButOnlyWhileInTab(){
        var h=new FpScanTest.H();h.start("6ubaa");h.dupe("6ubaa","§c6ubaa§7, §4Online§7, §cOffline [C] [H]");
        h.hist("6ubaa","забанен","FP","Истек");h.hist("Online","забанен","Funpay","Активный");h.drain();
        assertEquals(List.of("6ubaa","Online"),h.result.names());assertTrue(h.sent.stream().noneMatch(s->s.contains("Offline")));
    }
    @Test void productionModeChecksYellowAndRedOfflineAccountsWithoutTabFiltering(){
        long[] time={0};var sent=new java.util.ArrayList<String>();var results=new java.util.ArrayList<FpScan.Result>();
        var scan=new FpScan(new FpScan.Port(){
            public void send(String command){sent.add(command);}
            public java.util.Collection<String> online(){return List.of("Owner");}
            public void log(String value){}public void finish(FpScan.Result r){results.add(r);}
        },()->time[0]);
        scan.start(List.of("Owner"),false);
        scan.accept(FpProtocol.Line.plain("Сканирование Owner.\n§eYellowOffline§7, §cRedOffline§7, §4DarkOffline§7, §7GrayOffline"));time[0]+=5;scan.tick();
        for(String nick:List.of("YellowOffline","RedOffline","DarkOffline")){
            assertEquals("hist "+nick+" ban 100",sent.getLast());
            scan.accept(FpProtocol.Line.plain("История "+nick+" (Лимит: 1):\n"+nick+" был забанен куратором Mod\nПо причине: FP [Истек]"));time[0]+=5;scan.tick();
        }
        assertEquals(List.of("YellowOffline","RedOffline","DarkOffline"),results.getFirst().names());
        assertEquals(4,sent.size());assertTrue(results.getFirst().complete());
    }
}
