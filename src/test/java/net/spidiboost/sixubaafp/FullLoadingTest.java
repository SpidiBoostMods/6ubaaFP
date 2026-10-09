package net.spidiboost.sixubaafp;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FullLoadingTest {
    private static FullRun.View view(boolean ready, boolean compass, ServerMenus.Menu menu,
                                     long world, long revision, long fingerprint, long position, boolean tab, int sidebar) {
        return new FullRun.View(true, ready, compass, world, revision, fingerprint, sidebar, menu, position, tab);
    }
    private static FullRun.View hub(ServerMenus.Menu menu) { return view(true,true,menu,0,1,1,0,true,0); }
    private static FullRun.View grief() { return view(true,false,null,1,2,2,1,true,0); }
    private static void select(FullRun run, int number, long time) {
        run.tick(hub(null),time); run.tick(hub(null),time); run.tick(hub(FullRunTest.root()),time);
        run.tick(hub(FullRunTest.griefs(1)),time);
        if(number>32)run.tick(hub(FullRunTest.griefs(2)),time);
    }
    @Test void configurationKeepsEveryNavigationStageAliveWithoutSendingCommandsWhileLoading() {
        for(int stage=0;stage<4;stage++) {
            var p=new Probe();var f=new FullRun(p,5000);f.begin(0);
            if(stage>=1)f.tick(hub(null),1);
            if(stage>=2)f.tick(hub(FullRunTest.root()),1);
            if(stage>=3)f.tick(hub(FullRunTest.griefs(1)),1);
            FullRun.Phase phase=f.phase();int actions=p.actions.size();
            for(long time:new long[]{2,5000,30000,80000})
                f.tick(view(false,false,null,5,0,0,0,false,0),time);
            assertTrue(f.active());assertEquals(phase,f.phase());assertEquals(actions,p.actions.size());
            assertEquals(0,p.finishedCalls);
        }
    }
    @Test void configurationTimeDoesNotConsumeMenuWatchdogAndLoadedMenuIsClickedImmediately() {
        var p=new Probe();var f=new FullRun(p,5000);f.begin(0);
        f.tick(hub(null),1);f.tick(hub(FullRunTest.root()),1);
        f.tick(view(false,false,null,1,0,0,0,false,0),2);
        f.tick(view(false,false,null,1,0,0,0,false,0),20000);
        f.tick(hub(FullRunTest.griefs(1)),20001);
        assertEquals(FullRun.Phase.WAIT_TRANSFER,f.phase());
        assertEquals("click:11:0",p.actions.getLast());
        assertEquals(1,p.actions.stream().filter("hub"::equals).count());
    }
    @Test void missingRootAndLostCompassRetryHubInsteadOfHangingOrStopping() {
        var p=new Probe();var f=new FullRun(p,5000);f.begin(0);f.tick(hub(null),1);
        f.tick(view(true,false,null,1,2,2,1,true,0),5002);
        assertTrue(f.active());assertEquals(FullRun.Phase.WAIT_HUB,f.phase());
        assertEquals(2,p.actions.stream().filter("hub"::equals).count());
        f.tick(hub(null),5100);f.tick(hub(FullRunTest.root()),5101);
        assertEquals(FullRun.Phase.WAIT_GRIEFS,f.phase());
    }
    @Test void menuClosingBetweenRootAndGriefReopensSelectorOnSameGrief() {
        var p=new Probe();var f=new FullRun(p,5000);f.begin(0);
        f.tick(hub(null),1);f.tick(hub(FullRunTest.root()),1);
        f.tick(hub(null),5002);
        assertEquals(FullRun.Phase.WAIT_ROOT,f.phase());assertEquals(1,f.grief());assertTrue(f.active());
        assertEquals(1,p.actions.stream().filter("hub"::equals).count());
        f.tick(hub(FullRunTest.root()),5003);f.tick(hub(FullRunTest.griefs(1)),5004);
        assertEquals(FullRun.Phase.WAIT_TRANSFER,f.phase());
    }
    @Test void closeAndLatencyOnlyTabUpdatesCannotBeMistakenForServerEntry() {
        var p=new Probe();var f=new FullRun(p,5000);f.begin(0);select(f,1,1);f.menuClosed(11);
        f.tick(view(true,false,null,0,999,1,0,true,0),2000);
        assertEquals(FullRun.Phase.WAIT_TRANSFER,f.phase());assertFalse(p.actions.contains("scan"));
        f.tick(view(true,false,null,0,999,1,1,true,0),2100);
        f.tick(view(true,false,null,0,999,1,1,true,0),2850);
        assertEquals(FullRun.Phase.SCANNING,f.phase());
    }
    @Test void closedMenuButFailedTransferRetriesExactSelectedGrief() {
        var p=new Probe();var f=new FullRun(p,5000);f.begin(0);select(f,1,1);f.menuClosed(11);
        f.tick(hub(null),15002);
        assertTrue(f.active());assertEquals(FullRun.Phase.WAIT_ROOT,f.phase());assertEquals(1,f.grief());
        f.tick(hub(FullRunTest.root()),15003);f.tick(hub(FullRunTest.griefs(1)),15004);
        assertEquals("click:11:0",p.actions.getLast());assertFalse(p.actions.contains("scan"));
    }
    @Test void emptyTabWaitsForActualPlayersAndNeverFinishesAnUnloadedGriefAsEmpty() {
        var p=new Probe();var f=new FullRun(p,5000);f.begin(0);select(f,1,1);
        f.tick(view(true,false,null,1,2,0,1,false,0),2000);
        f.tick(view(true,false,null,1,2,0,1,false,0),12000);
        assertEquals(0,f.completedGriefs());assertFalse(p.actions.contains("scan"));assertTrue(f.active());
        f.tick(grief(),13000);f.tick(grief(),13750);
        assertEquals(FullRun.Phase.SCANNING,f.phase());
    }
    @Test void tabQuietPeriodRestartsAfterLongWorldLoadingGap() {
        var p=new Probe();var f=new FullRun(p,5000);f.begin(0);select(f,1,1);f.tick(grief(),2);
        f.tick(view(false,false,null,1,2,2,1,true,0),500);
        f.tick(view(false,false,null,2,3,3,2,true,0),40000);
        var loaded=view(true,false,null,2,3,3,2,true,0);
        f.tick(loaded,40001);assertFalse(p.actions.contains("scan"));
        f.tick(loaded,40750);assertFalse(p.actions.contains("scan"));
        f.tick(loaded,40751);assertEquals(FullRun.Phase.SCANNING,f.phase());
    }
    @Test void selfOnlyTabWaitsForDelayedPlayersBeforeStartingScan() {
        var p=new Probe();var f=new FullRun(p,5000);f.begin(0);select(f,1,1);
        var selfOnly=new FullRun.View(true,true,false,1,2,2,0,null,1,true,0);
        f.tick(selfOnly,2);f.tick(selfOnly,1601);f.tick(selfOnly,3001);
        assertFalse(p.actions.contains("scan"));assertEquals(0,f.completedGriefs());
        var players=new FullRun.View(true,true,false,1,3,3,0,null,1,true,10);
        f.tick(players,3100);f.tick(players,3849);assertFalse(p.actions.contains("scan"));
        f.tick(players,3850);assertEquals(FullRun.Phase.SCANNING,f.phase());
    }
    @Test void genuinelyEmptyLoadedServerCanBeScannedAfterFiveSecondsOfStableTab() {
        var p=new Probe();var f=new FullRun(p,5000);f.begin(0);select(f,1,1);
        var selfOnly=new FullRun.View(true,true,false,1,2,2,0,null,1,true,0);
        f.tick(selfOnly,2);f.tick(selfOnly,5001);assertFalse(p.actions.contains("scan"));
        f.tick(selfOnly,5002);assertEquals(FullRun.Phase.SCANNING,f.phase());
    }
    @Test void serverReturningToHubDuringScanPreservesRunAndRetriesSameGrief() {
        var p=new Probe();var f=new FullRun(p,5000);p.run=f;f.begin(0);select(f,1,1);
        f.tick(grief(),2);f.tick(grief(),1601);p.matches=List.of("Partial");
        f.tick(hub(null),1700);
        assertTrue(f.active());assertEquals(1,f.grief());assertEquals(FullRun.Phase.WAIT_ROOT,f.phase());
        assertEquals(0,p.finishedCalls);assertEquals(1,p.cancels);
        select(f,1,1800);f.tick(grief(),1801);f.tick(grief(),3400);
        f.scanFinished(true,List.of("Fresh"),"done",3500);
        assertEquals(List.of("Fresh - grief #1"),f.lines());
    }
    @Test void secondPageMenuLossReopensPageTwoWithoutSkippingGrief33() {
        var p=new Probe();var f=new FullRun(p,5000);f.begin(0);long now=1;
        for(int n=1;n<=32;n++) {
            select(f,n,now);f.tick(grief(),now+1);f.tick(grief(),now+1600);
            f.scanFinished(true,List.of("Nick"+n),"done",now+1601);now+=2400;
        }
        f.tick(hub(null),now);f.tick(hub(null),now);f.tick(hub(FullRunTest.root()),now);f.tick(hub(FullRunTest.griefs(1)),now);
        assertEquals(FullRun.Phase.WAIT_PAGE2,f.phase());f.tick(hub(null),now+5001);
        assertEquals(33,f.grief());assertEquals(FullRun.Phase.WAIT_ROOT,f.phase());
        select(f,33,now+5002);assertEquals("click:12:0",p.actions.getLast());
        assertEquals(32,f.completedGriefs());assertTrue(f.active());
    }
    @Test void completedScanWaitsBeforeHubAndLateWindowRenewsTheReturnGuard() {
        var p = new Probe() {
            @Override public void closeMenu() { actions.add("close"); }
        };
        var f = new FullRun(p, 5000); f.begin(0); select(f, 1, 1);
        f.tick(grief(), 2); f.tick(grief(), 1601);
        f.scanFinished(true, List.of("Found"), "done", 1700);
        assertEquals(2, f.grief()); assertEquals(List.of("Found - grief #1"), f.lines());
        f.tick(grief(), 2198);
        assertEquals(1, p.actions.stream().filter("hub"::equals).count());
        f.tick(view(true, false, FullRunTest.root(), 1, 2, 2, 1, true, 0), 2199);
        assertEquals("close", p.actions.getLast());
        f.tick(grief(), 2698);
        assertEquals(1, p.actions.stream().filter("hub"::equals).count());
        f.tick(grief(), 2699); f.tick(grief(), 2699);
        assertEquals(2, p.actions.stream().filter("hub"::equals).count());
        assertEquals(1, p.actions.stream().filter("scan"::equals).count());
    }

    @Test void stopOrClosedTransportDuringReturnGuardPreservesCompletedGriefsWithoutSendingHub() {
        for (boolean disconnect : new boolean[]{false, true}) {
            var p = new Probe(); var f = new FullRun(p, 5000); f.begin(0); select(f, 1, 1);
            f.tick(grief(), 2); f.tick(grief(), 1601);
            f.scanFinished(true, List.of("Found"), "done", 1700);
            if (disconnect) f.tick(new FullRun.View(false, false, false, 1, 2, 2, 0, null), 1701);
            else f.stop("Stop");
            assertFalse(f.active()); assertEquals(List.of("Found - grief #1"), f.lines());
            assertEquals(1, p.actions.stream().filter("hub"::equals).count());
            assertEquals(1, p.finishedCalls);
            f.tick(grief(), 50000); assertEquals(1, p.finishedCalls);
        }
    }

    static class Probe implements FullRun.Port {
        List<String> actions=new ArrayList<>(),matches=List.of();FullRun run;int cancels,finishedCalls;
        public void hub(){actions.add("hub");}public void useCompass(){actions.add("compass");}
        public void click(int id,int slot){actions.add("click:"+id+":"+slot);}
        public boolean startScan(){actions.add("scan");return true;}
        public void cancelScan(){cancels++;if(run!=null)run.scanFinished(false,matches,"cancel",1700);}
        public List<String> scanMatches(){return matches;}public boolean persist(List<String> lines){return true;}
        public void status(String s){}public void finished(boolean complete,String reason){finishedCalls++;}
    }
}
