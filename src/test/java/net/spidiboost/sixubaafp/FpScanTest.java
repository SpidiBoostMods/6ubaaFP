package net.spidiboost.sixubaafp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FpScanTest {
    @TempDir Path temp;
    static final class H implements FpScan.Port {
        long time;List<String> online=new ArrayList<>(List.of("6ubaa","6uba","Online"));
        List<String> sent=new ArrayList<>(),logs=new ArrayList<>();FpScan.Result result;
        FpScan s=new FpScan(this,()->time);
        public void send(String c){sent.add(c);}public Collection<String> online(){return online;}
        public void log(String l){logs.add(l);}public void finish(FpScan.Result r){result=r;}
        void tick(long ms){time+=ms;s.tick();}void feed(String line){s.accept(FpProtocol.Line.plain(line));}
        void start(String... names){s.start(List.of(names),true);}
        void dupe(String owner,String body){feed("[18:54:48] Сканирование "+owner+". [Онлайн] [Забанен]");s.accept(FpProtocol.Line.plain(body));tick(500);tick(250);}
        void hist(String name,String verb,String reason,String status){feed("История "+name+" (Лимит: 1):\n -- [2026-10-08 15:29] --\n"+name+" был "+verb+" куратором Moderator\nПо причине: '"+reason+"' ["+status+"]");tick(1100);tick(250);}
        void drain(){for(int i=0;s.active()&&i<20000;i++)tick(250);assertFalse(s.active());}
    }
    @Test void onlyYellowAndGoldAccountsAreQueried(){
        H h=new H();h.start("6ubaa");h.dupe("6ubaa","§66ubaa§7, 6uba, §eOnline§7, Offline [C] [H]");
        assertEquals(List.of("dupeip 6ubaa","hist 6ubaa ban 100"),h.sent);
        h.hist("6ubaa","забанен","fp_RW","Истек");assertEquals("hist Online ban 100",h.sent.getLast());
        h.hist("Online","забанен","playerok","Активный");h.drain();assertEquals(List.of("6ubaa","Online"),h.result.names());
        assertTrue(h.sent.stream().noneMatch(c->c.contains("checkban")||c.startsWith("hist 6uba ")||c.contains("Offline")));
    }
    @Test void allMentionVariantsAndStatuses(){
        for(String reason:List.of("FP_RW","fp_rw","fp","Покупка на Funpay","Funpay","FP","PLAYEROK","бан fp test","Fun pay","Player-ok"))
            for(String status:List.of("Активный","Истек","Снят")){
                H h=new H();h.start("6ubaa");h.dupe("6ubaa","§66ubaa");h.hist("6ubaa","забанен",reason,status);h.drain();assertEquals(List.of("6ubaa"),h.result.names(),reason);
            }
    }
    @Test void warningsAndMutesDoNotQualify(){for(String verb:List.of("предупрежден","предупреждён","заткнут")){
        H h=new H();h.start("6ubaa");h.dupe("6ubaa","§66ubaa");h.hist("6ubaa",verb,"Funpay","Активный");h.drain();assertEquals(List.of(),h.result.names());}}
    @Test void grayOnlineAndYellowOutsideTabAreNotQueried(){H h=new H();h.start("6ubaa");h.dupe("6ubaa","§76ubaa, §eOffline");h.drain();assertEquals(1,h.sent.size());}
    @Test void leavesTabBeforeItsHistory(){H h=new H();h.start("6ubaa");h.feed("Сканирование 6ubaa.");h.s.accept(FpProtocol.Line.plain("§66ubaa"));h.online.clear();h.drain();assertEquals(1,h.sent.size());}
    @Test void dedupeAcrossDupeipOwners(){H h=new H();h.start("6ubaa","6uba");h.dupe("6ubaa","§66ubaa, §eOnline");h.hist("6ubaa","забанен","FP","Истек");h.hist("Online","забанен","fp","Активный");
        h.dupe("6uba","§66ubaa, §eOnline, §66uba");h.hist("6uba","забанен","FP","Истек");h.drain();assertEquals(3,h.sent.stream().filter(s->s.startsWith("hist ")).count());assertEquals(3,h.result.names().size());}
    @Test void wrongOwnerHeaderAndPlayerChatIgnored(){H h=new H();h.start("6ubaa");h.feed("Сканирование Other.");h.s.accept(FpProtocol.Line.plain("§66ubaa"));h.tick(1000);assertEquals(1,h.sent.size());assertNull(h.result);h.s.stop("stop");}
    @Test void timeoutNeverDuplicatesRequestAndPreservesUnresolved(){H h=new H();h.start("6ubaa");h.tick(6100);assertEquals(1,h.sent.size());h.tick(100000);assertEquals(1,h.sent.size());assertFalse(h.result.complete());assertFalse(h.result.unresolved().isEmpty());}
    @Test void unavailablePlayerSkipped(){H h=new H();h.start("6ubaa");h.feed("Игрок 6ubaa не найден.");h.drain();assertTrue(h.result.complete());assertTrue(h.result.names().isEmpty());}
    @Test void trueNewlinesAndTimestampsWithSuffixAnnotations(){H h=new H();h.start("6ubaa");h.dupe("6ubaa","[18:54:48] §66ubaa,\n§eOnline [C] [H]");h.hist("6ubaa","забанен","fp","Истек");h.hist("Online","забанен","funpay","Истек");h.drain();assertEquals(2,h.result.names().size());}
    @Test void reasonWrappingBetweenPackets(){H h=new H();h.start("6ubaa");h.dupe("6ubaa","§66ubaa");h.feed("История 6ubaa (Лимит: 1):\n6ubaa был забанен куратором Mod\nПо причине: Покупка на");h.feed("Funpay [Истек]");h.drain();assertEquals(List.of("6ubaa"),h.result.names());}
    @Test void colorsNotInferredFromOnlineHeader(){H h=new H();h.start("6ubaa");h.dupe("6ubaa","6ubaa, Online");h.drain();assertEquals(1,h.sent.size());}
    @Test void unrelatedBroadcastCannotContaminateFinishedReason(){H h=new H();h.start("6ubaa");h.dupe("6ubaa","§66ubaa");h.feed("История 6ubaa (Лимит: 1):\n6ubaa был забанен куратором Mod\nПо причине: Читы [Истек]");h.feed("Реклама сервера Funpay");h.drain();assertTrue(h.result.names().isEmpty());}
    @Test void unbanAdministratorNameIsNotPartOfTheBanReason(){H h=new H();h.start("6ubaa");h.dupe("6ubaa","§66ubaa");h.feed("История 6ubaa (Лимит: 1):\n6ubaa был забанен куратором Mod\nПо причине: 'Читы'\n6ubaa was unbanned by FpModerator.");h.drain();assertTrue(h.result.names().isEmpty());}
    @Test void customYellowPaletteAndNoGrayGreenOrRed(){for(int c:new int[]{0xffaa00,0xffff55,0xffd240,0xffcc33})assertTrue(FpProtocol.yellow(c));for(int c:new int[]{-1,0xffffff,0xaaaaaa,0x55ff55,0xff5555,0x333300})assertFalse(FpProtocol.yellow(c));}
    @Test void atomicFileAndArbitraryMinecraftDirectory()throws Exception{Path root=temp.resolve("Prism instance with spaces & Юникод");Path output=FpFiles.result(root);FpFiles.write(output,List.of("A B - grief #1","C - grief #56"));assertEquals(root.toAbsolutePath().resolve("6ubaafp.txt"),output);assertEquals("A B - grief #1\nC - grief #56",Files.readString(output));FpFiles.write(output,List.of());assertEquals("",Files.readString(output));}
    @Test void banHistoryOfWrongNicknameCannotQualify(){H h=new H();h.start("6ubaa");h.dupe("6ubaa","§66ubaa");h.feed("История Other (Лимит: 1):\nOther был забанен куратором Mod\nПо причине: FP [Активный]");h.tick(1000);assertTrue(h.s.matches().isEmpty());h.s.stop("stop");}
    @Test void noBurstEvenWithSynchronousResponses(){H h=new H();h.start("6ubaa");h.dupe("6ubaa","§66ubaa");h.hist("6ubaa","забанен","Читы","Истек");h.drain();assertTrue(h.result.complete());assertTrue(h.result.names().isEmpty());}
    @Test void nextCommandAtFiveMillisecondsNotBeforeOrInABurst(){
        H h=new H();h.start("6ubaa","Online");
        h.feed("Сканирование 6ubaa.\n§66ubaa, §eOnline");
        h.tick(4);assertEquals(1,h.sent.size());
        h.tick(1);assertEquals(List.of("dupeip 6ubaa","hist 6ubaa ban 100"),h.sent);
        h.feed("История 6ubaa (Лимит: 1):\n6ubaa был забанен куратором Mod\nПо причине: FP [Истек]");
        assertEquals(2,h.sent.size(),"accept must not send recursively");
        h.tick(4);assertEquals(2,h.sent.size());h.tick(1);
        assertEquals("hist Online ban 100",h.sent.getLast());assertEquals(3,h.sent.size());
        for(int i=0;i<20;i++)h.s.tick();assertEquals(3,h.sent.size(),"no sends at same clock");
        h.feed("История Online (Лимит: 1):\nOnline был забанен куратором Mod\nПо причине: Playerok [Истек]");
        h.tick(5);assertEquals("dupeip Online",h.sent.getLast());assertEquals(4,h.sent.size());
        h.feed("Сканирование Online.\n§eOnline");h.tick(5);
        assertTrue(h.result.complete());assertEquals(List.of("6ubaa","Online"),h.result.names());
        assertEquals(20,h.time,"no hidden 150/300/450 ms pauses");
    }
    @Test void fiveMillisecondsStartsAfterResponseNotAfterRequest(){
        H h=new H();h.start("6ubaa");h.tick(160);
        h.feed("Сканирование 6ubaa.\n§66ubaa");h.tick(4);assertEquals(1,h.sent.size());
        h.tick(1);assertEquals(2,h.sent.size());assertEquals(165,h.time);
    }
    @Test void lastHistoryEntryMustHaveItsCompleteReason(){
        H h=new H();h.start("6ubaa","Online");h.feed("Сканирование 6ubaa.\n§66ubaa");h.tick(5);
        h.feed("История 6ubaa (Лимит: 1):\n6ubaa был забанен куратором Mod");
        h.tick(100);assertEquals(2,h.sent.size());
        h.feed("По причине: Покупка на");h.tick(100);assertEquals(2,h.sent.size());
        h.feed("Funpay [Истек]");h.tick(4);assertEquals(2,h.sent.size());h.tick(1);
        assertEquals("dupeip Online",h.sent.getLast());assertEquals(List.of("6ubaa"),h.s.matches());
    }
    @Test void advertisedLimitDoesNotStallAndLaterRowsStillBelongToOwner(){
        H h=new H();h.start("6ubaa","Online");h.feed("Сканирование 6ubaa.\n§66ubaa");h.tick(5);
        h.feed("История 6ubaa (Лимит: 2):\n6ubaa был забанен куратором Mod\nПо причине: Читы [Истек]");
        h.tick(5);assertEquals(3,h.sent.size());assertEquals("dupeip Online",h.sent.getLast());assertNull(h.result);
        h.feed("-- [2026-10-08 15:29] --\n6ubaa был забанен куратором Mod\nПо причине: FP_RW [Активный]\nОкончание в 11 дней.");
        h.tick(5);assertEquals("dupeip Online",h.sent.getLast());assertEquals(List.of("6ubaa"),h.s.matches());
    }
    @Test void commaTerminatedDupeipWaitsForDelayedContinuation(){
        H h=new H();h.start("6ubaa");h.feed("Сканирование 6ubaa.\n§66ubaa,");
        h.tick(1000);assertEquals(1,h.sent.size());
        h.feed("§eOnline [C] [H]");h.tick(5);assertEquals("hist 6ubaa ban 100",h.sent.getLast());
        h.feed("История 6ubaa (Лимит: 0):");h.tick(5);assertEquals("hist Online ban 100",h.sent.getLast());
        h.feed("История Online (Лимит: 1):\nOnline был забанен куратором Mod\nПо причине: FP [Истек]");
        h.tick(5);assertEquals(List.of("Online"),h.result.names());assertTrue(h.result.complete());
    }
    @Test void unavailableAndEmptyResponsesKeepFiveMillisecondGap(){
        H h=new H();h.start("6ubaa","Online");h.feed("Игрок 6ubaa не найден.");
        h.tick(4);assertEquals(1,h.sent.size());h.tick(1);assertEquals("dupeip Online",h.sent.getLast());
        h.feed("Сканирование Online.\n§eOnline");h.tick(5);assertEquals("hist Online ban 100",h.sent.getLast());
        h.feed("Нет истории игрока Online.");h.tick(4);assertNull(h.result);h.tick(1);assertTrue(h.result.complete());
    }
    @Test void wrongUnbanDoesNotCompleteWrappedReason(){
        H h=new H();h.start("6ubaa");h.feed("Сканирование 6ubaa.\n§66ubaa");h.tick(5);
        h.feed("История 6ubaa (Лимит: 1):\n6ubaa был забанен куратором Mod\nПо причине: Покупка на");
        h.feed("Other was unbanned by ReallyWorld.");h.tick(100);assertNull(h.result);
        h.feed("Funpay [Истек]");h.tick(5);assertEquals(List.of("6ubaa"),h.result.names());
    }
    @Test void mismatchedLimitCompletesWithoutRepeatingHistory(){
        H h=new H();h.start("6ubaa");h.feed("Сканирование 6ubaa.\n§66ubaa");h.tick(5);
        h.feed("История 6ubaa (Лимит: 2):\n6ubaa был забанен куратором Mod\nПо причине: Читы [Истек]");
        h.tick(5);assertEquals(2,h.sent.size());h.tick(100);assertTrue(h.result.complete());
        assertEquals(2,h.sent.size());assertTrue(h.logs.stream().noneMatch(s->s.startsWith("INCOMPLETE")));
    }
    @Test void actualEmptyHundredLimitHeaderUsesOnlyNormalFiveMillisecondGap(){
        H h=new H();h.start("6ubaa","Online");h.feed("Сканирование 6ubaa.\n§66ubaa");h.tick(5);
        h.feed("История 6ubaa (Лимит: 100):");h.tick(4);assertEquals(2,h.sent.size());
        h.feed("SomePlayer » Funpay advertisement");h.tick(1);
        assertEquals("dupeip Online",h.sent.getLast());assertTrue(h.s.matches().isEmpty());
        assertTrue(h.logs.stream().anyMatch(s->s.contains("header-only empty history")));
    }
    @Test void firstDatePreventsEmptyHeaderHeuristicEvenBeforeFirstEntry(){
        H h=new H();h.start("6ubaa");h.feed("Сканирование 6ubaa.\n§66ubaa");h.tick(5);
        h.feed("История 6ubaa (Лимит: 100):");h.tick(4);h.feed("-- [2026-10-08 15:29] --");
        h.tick(200);assertNull(h.result);assertEquals(2,h.sent.size());h.s.stop("stop");assertFalse(h.result.complete());
    }
    @Test void emptyHeaderWithAnyLimitAdvancesWithoutCooldownOrRetry(){
        for(int limit:new int[]{1,2,7,10,50,99,100,101,10000}){
            H h=new H();h.start("6ubaa","Online");h.feed("Сканирование 6ubaa.\n§66ubaa");h.tick(5);
            h.feed("[00:56:36] История 6ubaa (Лимит: "+limit+"):");h.feed("\n\n");
            h.tick(4);assertEquals(2,h.sent.size());h.tick(1);
            assertEquals("dupeip Online",h.sent.getLast(),"limit="+limit);assertEquals(3,h.sent.size());
            assertEquals(10,h.time);assertTrue(h.logs.stream().noneMatch(s->s.startsWith("INCOMPLETE")));
        }
    }
    @Test void dateReceivedInDrainedBatchAfterFiveMsPreventsSkippingRealBody(){
        H h=new H();h.start("6ubaa","Online");h.feed("Сканирование 6ubaa.\n§66ubaa");h.tick(5);
        h.feed("История 6ubaa (Лимит: 1):");h.time+=35;h.feed("-- [2026-10-08 15:29] --");
        h.s.tick();assertEquals(2,h.sent.size());h.feed("6ubaa был забанен куратором Mod\nПо причине: Funpay [Истек]");
        h.tick(5);assertEquals("dupeip Online",h.sent.getLast());assertEquals(List.of("6ubaa"),h.s.matches());
    }
    @Test void delayedBodyAfterSkipStillFindsFpWhileNextDupeipIsInFlight(){
        H h=new H();h.start("6ubaa","Online");h.feed("Сканирование 6ubaa.\n§66ubaa");h.tick(5);
        h.feed("История 6ubaa (Лимит: 1):");h.tick(5);assertEquals("dupeip Online",h.sent.getLast());
        h.tick(30);h.feed("-- [2026-10-08 15:29] --\n6ubaa был забанен куратором Mod\nПо причине: Покупка на");
        h.tick(10);h.feed("Funpay [Истек]");assertEquals(List.of("6ubaa"),h.s.matches());
        h.feed("Сканирование Online.\n§eOnline");h.tick(5);assertEquals("hist Online ban 100",h.sent.getLast());
        h.feed("История Online (Лимит: 1):\nOnline был забанен куратором Mod\nПо причине: Читы [Истек]");h.drain();
        assertEquals(List.of("6ubaa"),h.result.names());assertTrue(h.result.complete());assertEquals(4,h.sent.size());
    }
    @Test void lateOldHistoryIsNotAttributedToNextHistory(){
        H h=new H();h.start("6ubaa");h.feed("Сканирование 6ubaa.\n§66ubaa, §eOnline");h.tick(5);
        h.feed("История 6ubaa (Лимит: 7):");h.tick(5);assertEquals("hist Online ban 100",h.sent.getLast());
        h.feed("6ubaa был забанен куратором Mod\nПо причине: Playerok [Истек]");
        h.feed("История Online (Лимит: 1):\nOnline был забанен куратором Mod\nПо причине: Читы [Истек]");h.drain();
        assertEquals(List.of("6ubaa"),h.result.names());assertTrue(h.result.complete());
    }
    @Test void lateFinalHistoryBodyIsCapturedWithoutRepeatingHist(){
        H h=new H();h.start("6ubaa");h.feed("Сканирование 6ubaa.\n§66ubaa");h.tick(5);
        h.feed("История 6ubaa (Лимит: 1):");h.tick(5);assertNull(h.result);
        h.tick(30);h.feed("6ubaa был забанен куратором Mod\nПо причине: FP_RW [Активный]");h.drain();
        assertTrue(h.result.complete());assertEquals(List.of("6ubaa"),h.result.names());assertEquals(2,h.sent.size());
    }
    @Test void lateReasonCannotBeContaminatedByNextDupeipNames(){
        H h=new H();h.online=List.of("6ubaa","Funpay");h.start("6ubaa","Funpay");h.feed("Сканирование 6ubaa.\n§66ubaa");h.tick(5);
        h.feed("История 6ubaa (Лимит: 1):");h.tick(5);
        h.feed("6ubaa был забанен куратором Mod\nПо причине: Читы");
        h.feed("Сканирование Funpay.\n§eFunpay");h.tick(5);assertEquals("hist Funpay ban 100",h.sent.getLast());
        assertTrue(h.s.matches().isEmpty());h.s.stop("stop");
    }
    @Test void foreignBanCannotBeAssignedToSkippedHeader(){
        H h=new H();h.start("6ubaa","Online");h.feed("Сканирование 6ubaa.\n§66ubaa");h.tick(5);
        h.feed("История 6ubaa (Лимит: 1):");h.tick(5);
        h.feed("Foreign был забанен куратором Mod\nПо причине: Funpay [Истек]");
        assertTrue(h.s.matches().isEmpty());assertEquals("dupeip Online",h.sent.getLast());h.s.stop("stop");
    }
    @Test void actualServerShapeWithSevenSeparateRecordsAndUnbans(){
        H h=new H();h.online=List.of("CooL_EVGEXA","Online");h.start("CooL_EVGEXA","Online");
        h.feed("Сканирование CooL_EVGEXA. [Онлайн] [Оффлайн] [Забанен]");h.feed("§6CooL_EVGEXA§7, tif_15, Forget4");h.tick(5);
        h.feed("История CooL_EVGEXA (Лимит: 7):");
        for(int record=0;record<7;record++){
            h.feed(" -- [2026-06-30 04:57] --");h.feed("CooL_EVGEXA был забанен куратором Regalia ");
            h.tick(10);assertEquals(record==0?2:3,h.sent.size());
            h.feed("По причине: '"+(record==6?"FP_RW":"Уклон от проверки")+"' [Истек]");
            if(record==0||record==2){h.feed("");h.feed(" CooL_EVGEXA was unbanned by ReallyWorld.");}
            if(record<6){h.tick(10);assertEquals(3,h.sent.size());}
        }
        h.tick(5);assertEquals("dupeip Online",h.sent.getLast());assertEquals(List.of("CooL_EVGEXA"),h.s.matches());
    }
    @Test void largeTabAndHundredHistoryRecordsPerAccountDoNotLoseOrRepeatNames(){
        class Server implements FpScan.Port {
            long clock,last=-1000000;int dupes,hists;FpScan.Result output;
            List<String> names=java.util.stream.IntStream.range(0,300).mapToObj(i->"Player"+i).toList();
            Set<String> checked=new HashSet<>();FpScan scan=new FpScan(this,()->clock);
            public Collection<String> online(){return names;}public void log(String message){}
            public void finish(FpScan.Result r){output=r;}
            public void send(String command){
                assertTrue(clock-last>=5,"burst");last=clock;String nick=command.split(" ")[1];
                if(command.startsWith("dupeip")) {
                    dupes++;scan.accept(FpProtocol.Line.plain("Сканирование "+nick+". [Онлайн]"));
                    scan.accept(FpProtocol.Line.plain("§6"+nick+", "+nick.toUpperCase(Locale.ROOT)+"§7, GrayOffline [C] [H]"));
                } else {
                    hists++;assertTrue(checked.add(nick),"duplicate history");
                    int index=Integer.parseInt(nick.substring(6));StringBuilder text=new StringBuilder("История "+nick+" (Лимит: 100):\n");
                    for(int record=0;record<100;record++)text.append("-- [2026-10-08 15:29] --\n").append(nick).append(" был забанен куратором Mod\nПо причине: ")
                            .append(record==99&&index%3==0?"FP_RW":"Читы").append(record%2==0?" [Активный]\n":" [Истек]\n");
                    scan.accept(FpProtocol.Line.plain(text.toString()));
                }
            }
        }
        Server s=new Server();s.scan.start(s.names,true);
        for(int i=0;s.scan.active()&&i<30000;i++){s.clock+=5;s.scan.tick();}
        assertNotNull(s.output);assertTrue(s.output.complete());assertEquals(300,s.dupes);assertEquals(300,s.hists);
        assertEquals(java.util.stream.IntStream.range(0,300).filter(i->i%3==0).mapToObj(i->"Player"+i).toList(),s.output.names());
        assertEquals(3000,s.clock,"600 serialized full responses, no artificial waits beyond 5 ms");
    }
    @Test void threeHundredEmptyHistoriesHaveNoPerNicknameCooldown(){
        class Server implements FpScan.Port {
            long clock,last=-1000000;int dupes,hists;FpScan.Result result;
            List<String> names=java.util.stream.IntStream.range(0,300).mapToObj(i->"Fresh"+i).toList();
            FpScan scan=new FpScan(this,()->clock);
            public Collection<String> online(){return names;}public void log(String message){}
            public void finish(FpScan.Result r){result=r;}
            public void send(String command){
                assertTrue(clock-last>=5,"burst");last=clock;String nick=command.split(" ")[1];
                if(command.startsWith("dupeip")){dupes++;scan.accept(FpProtocol.Line.plain("Сканирование "+nick+".\n§6"+nick));}
                else {hists++;scan.accept(FpProtocol.Line.plain("История "+nick+" (Лимит: "+(1+hists%101)+"):"));}
            }
        }
        Server s=new Server();s.scan.start(s.names,true);
        while(s.scan.active()&&s.clock<10000){s.clock+=5;s.scan.tick();}
        assertNotNull(s.result);assertTrue(s.result.complete());assertTrue(s.result.names().isEmpty());
        assertEquals(300,s.dupes);assertEquals(300,s.hists);assertEquals(3095,s.clock,"one final tail only, not 300 cooldowns");
    }
}
