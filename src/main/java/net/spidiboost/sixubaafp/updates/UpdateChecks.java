package net.spidiboost.sixubaafp.updates;

/** Serial checks with explicit activation authority. Timer retries can never inherit it. */
final class UpdateChecks {
    enum Mode { DISCOVERY, ACTIVATE }
    private boolean running;
    private Mode queued;
    private int failures;

    synchronized boolean request(Mode mode) {
        if (!running) { running=true; return true; }
        if (queued==null || mode==Mode.ACTIVATE) queued=mode;
        return false;
    }
    synchronized Mode finish() {
        running=false; Mode next=queued; queued=null; return next;
    }
    synchronized long failedDelaySeconds() {
        failures=Math.min(5,failures+1);
        return Math.min(300,30L << (failures-1));
    }
    synchronized void success() { failures=0; }
}
