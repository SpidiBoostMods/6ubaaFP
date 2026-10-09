package net.spidiboost.sixubaafp;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class UnbanHistoryTest {
    private static FpScanTest.H begin(){var h=new FpScanTest.H();h.start("6ubaa","Online");h.feed("Сканирование 6ubaa.\n§66ubaa");h.tick(5);h.feed("История 6ubaa (Лимит: 5):");return h;}
    private static String entry(String reason,String author){return "-- [2026-10-09 15:29] --\n6ubaa был забанен куратором Moderator\nПо причине: '"+reason+"'"+(author==null?" [Истек]":"\n6ubaa was unbanned by "+author+".");}
    @Test void everyManualUnbanIsRejectedAndReallyWorldIsExactCaseInsensitive(){
        for(String author:List.of("meowikis","Mod","FpModerator","ReallyWorldAdmin","FakeReallyWorld","ReallyWorld","rEaLlYwOrLd")){
            var h=begin();h.feed(entry("FunPay",author));h.drain();assertEquals(author.equalsIgnoreCase("ReallyWorld")?List.of("6ubaa"):List.of(),h.result.names(),author);
        }
    }
    @Test void screenshotManualFPRecordsExcludedEvenWithUnrelatedAutomaticUnban(){
        var h=begin();h.feed(entry("Хранение читов","ReallyWorld"));h.feed(entry("FunPay","meowikis"));h.feed(entry("4bFunPay","Other"));h.drain();assertTrue(h.result.names().isEmpty());
    }
    @Test void separateAutomaticFpRecordWinsOverManualOnSameAccountInEitherOrder(){
        for(boolean first:List.of(true,false)){
            var h=begin();h.feed(entry("Funpay",first?"ReallyWorld":"Mod"));h.feed(entry("Playerok",first?"Mod":"ReallyWorld"));h.drain();assertEquals(List.of("6ubaa"),h.result.names());
        }
    }
    @Test void bansWithoutUnbanRemainEligibleButWarningsAndOtherReasonsDoNot(){
        var h=begin();h.feed(entry("fp",null));h.feed(entry("Читы","ReallyWorld"));h.drain();assertEquals(List.of("6ubaa"),h.result.names());
        var evidence=new HistoryEvidence();evidence.begin(false);evidence.reason("FP");evidence.unban("ReallyWorld.");assertFalse(evidence.matches());
    }
    @Test void lateManualUnbanRetractsCompletedFindingWithoutAffectingNextOwner(){
        var h=begin();h.feed(entry("FP",null));h.tick(5);assertEquals("dupeip Online",h.sent.getLast());assertEquals(List.of("6ubaa"),h.s.matches());
        h.feed("[21:54:49] §76ubaa was unbanned by meowikis.");assertTrue(h.s.matches().isEmpty());
        h.feed("Сканирование Online.\n§eOnline");h.tick(5);h.feed("История Online (Лимит: 1):\nOnline был забанен куратором QA\nПо причине: Playerok [Истек]");h.drain();assertEquals(List.of("Online"),h.result.names());
    }
    @Test void foreignUnbanCannotRetractOrPolluteReasonAndLateAutomaticRecordCanQualify(){
        var h=begin();h.feed(entry("FP",null));h.feed("Other was unbanned by Moderator.");assertEquals(List.of("6ubaa"),h.s.matches());h.tick(5);
        h.feed("6ubaa was unbanned by Moderator.");assertTrue(h.s.matches().isEmpty());
        h.feed(entry("Funpay","ReallyWorld"));assertEquals(List.of("6ubaa"),h.s.matches());h.drain();assertEquals(List.of("6ubaa"),h.result.names());
    }
}
