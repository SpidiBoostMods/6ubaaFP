package net.spidiboost.sixubaafp;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** A client-thread state machine. Menu packets can call tick immediately, without waiting a game tick. */
public final class FullRun {
    public enum Phase { WAIT_HUB, WAIT_ROOT, WAIT_GRIEFS, WAIT_PAGE2, WAIT_TRANSFER, WAIT_TAB,
        SCANNING, RETRY_SCAN, FINISHED }
    public record View(boolean connected, boolean ready, boolean compass, long worldEpoch,
                       long tabRevision, long tabFingerprint, int scoreboardGrief, ServerMenus.Menu menu,
                       long positionRevision, boolean tabPresent, int eligiblePlayers) {
        public View(boolean connected, boolean ready, boolean compass, long worldEpoch,
                    long tabRevision, long tabFingerprint, int scoreboardGrief, ServerMenus.Menu menu,
                    long positionRevision, boolean tabPresent) {
            this(connected, ready, compass, worldEpoch, tabRevision, tabFingerprint, scoreboardGrief, menu,
                    positionRevision, tabPresent, tabPresent ? 1 : 0);
        }
        public View(boolean connected, boolean ready, boolean compass, long worldEpoch,
                    long tabRevision, long tabFingerprint, int scoreboardGrief, ServerMenus.Menu menu) {
            this(connected, ready, compass, worldEpoch, tabRevision, tabFingerprint, scoreboardGrief, menu, 0, true);
        }
    }
    public interface Port {
        void hub();
        void useCompass();
        void click(int syncId, int slot);
        default void closeMenu() {}
        boolean startScan();
        void cancelScan();
        List<String> scanMatches();
        boolean persist(List<String> lines);
        void status(String message);
        void finished(boolean complete, String reason);
    }
    private final Port port;
    private final int retryDrainMs;
    private final List<Integer> route;
    private int routeIndex, completed;
    private final List<String> lines = new ArrayList<>();
    private final LinkedHashSet<String> partial = new LinkedHashSet<>();
    private Phase phase = Phase.WAIT_HUB;
    private int grief;
    private int transferSync = -1;
    private int retries;
    private long nextAction;
    private long selectedAt;
    private long selectedWorld;
    private long selectedTabRevision;
    private long selectedTabFingerprint;
    private long selectedPosition;
    private int selectedScoreboard;
    private long enteredWorld;
    private long stableSince = -1;
    private long lastTabFingerprint;
    private long retryAt;
    private boolean transferAcknowledged;
    private boolean hubPending;
    private boolean attempted;
    private boolean cancelled;
    private long currentTime, phaseStartedAt, pausedSince = -1, nextStatus;
    private static final long RETURN_QUIET_MS = 500;
    private static final long MENU_TIMEOUT = 5000;
    private static final long TRANSFER_TIMEOUT = 15000;
    private static final long TAB_TIMEOUT = 30000;

    public FullRun(Port port, int retryDrainMs) {
        this(port, retryDrainMs, 1);
    }
    public FullRun(Port port, int retryDrainMs, int firstGrief) {
        this(port, retryDrainMs, GriefSelection.from(firstGrief));
    }
    public FullRun(Port port, int retryDrainMs, List<Integer> route) {
        if (route.isEmpty()) throw new IllegalArgumentException("Не выбран ни один гриф.");
        int previous = 0;
        for (int number : route) {
            ServerMenus.pageFor(number); // Validate before any commands or result writes.
            if (number <= previous) throw new IllegalArgumentException("Грифы должны быть уникальными и идти по возрастанию.");
            previous = number;
        }
        this.port = port;
        this.retryDrainMs = retryDrainMs;
        this.route = List.copyOf(route);
        this.grief = this.route.getFirst();
    }
    public void begin(long now) {
        currentTime = now;
        hubPending = false;
        setPhase(Phase.WAIT_HUB);
        nextAction = now + 5000;
        port.hub();
    }

    public void tick(View view, long now) {
        if (!active()) return;
        currentTime = now;
        if (!view.connected()) { stop("Соединение с сервером потеряно."); return; }
        if (!view.ready()) {
            if (pausedSince < 0) pausedSince = now;
            stableSince = -1;
            if (now >= nextStatus) {
                nextStatus = now + 5000;
                port.status("Full: гриф #" + grief + "/56 — мир загружается, продолжаю ожидание.");
            }
            return; // Configuration phase is loading, not a disconnect; never send clicks here.
        }
        if (pausedSince >= 0) {
            long duration = now - pausedSince;
            phaseStartedAt += duration;
            nextAction += duration;
            pausedSince = -1;
        }
        ServerMenus.Menu menu = view.menu();
        switch (phase) {
            case WAIT_HUB -> {
                if (hubPending) {
                    // Do not overlap the last /invsee close and a proxy/server transfer.
                    // A late window is closed first and gets its own quiet interval.
                    if (menu != null) {
                        nextAction = now + RETURN_QUIET_MS;
                        port.closeMenu();
                    } else if (now >= nextAction) begin(now);
                    return;
                }
                if (view.compass() && menu == null) {
                    setPhase(Phase.WAIT_ROOT);
                    nextAction = now + 1000;
                    port.useCompass();
                } else if (now >= nextAction) {
                    nextAction = now + 5000;
                    port.hub();
                }
            }
            case WAIT_ROOT -> {
                if (menu != null && menu.root()) {
                    setPhase(Phase.WAIT_GRIEFS);
                    nextAction = now + 750;
                    port.click(menu.syncId(), 21);
                } else if (menu == null && view.compass() && now >= nextAction) {
                    nextAction = now + 1000;
                    port.useCompass();
                } else if (now - phaseStartedAt >= MENU_TIMEOUT) {
                    recoverNavigation(view, now, "Меню компаса не появилось или хаб изменился.");
                }
            }
            case WAIT_GRIEFS -> {
                if (menu != null && menu.page() == 1) {
                    if (ServerMenus.pageFor(grief) == 2) {
                        if (menu.nextPage()) {
                            setPhase(Phase.WAIT_PAGE2);
                            nextAction = now + 750;
                            port.click(menu.syncId(), 44);
                        }
                    } else if (menu.target(grief)) select(menu, view, now);
                } else if (menu != null && menu.root() && now >= nextAction) {
                    nextAction = now + 750;
                    port.click(menu.syncId(), 21);
                } else if (now - phaseStartedAt >= MENU_TIMEOUT) {
                    recoverNavigation(view, now, "Меню грифов не появилось; открываю выбор заново.");
                }
            }
            case WAIT_PAGE2 -> {
                if (menu != null && menu.target(grief)) select(menu, view, now);
                else if (menu != null && menu.nextPage() && now >= nextAction) {
                    nextAction = now + 750;
                    port.click(menu.syncId(), 44);
                } else if (now - phaseStartedAt >= MENU_TIMEOUT) {
                    recoverNavigation(view, now, "Вторая страница не появилась; повторяю выбор текущего грифа.");
                }
            }
            case WAIT_TRANSFER -> {
                if (menu != null && menu.target(grief) && now >= nextAction) {
                    // Retry only the exact selected grief, and only in its verified menu/page.
                    transferSync = menu.syncId();
                    nextAction = now + 400;
                    port.click(menu.syncId(), ServerMenus.slotFor(grief));
                } else if (menu == null && griefConfirmed(view) && view.tabPresent()) {
                    enteredWorld = view.worldEpoch();
                    stableSince = now;
                    lastTabFingerprint = view.tabFingerprint();
                    setPhase(Phase.WAIT_TAB);
                } else if (now - phaseStartedAt >= TRANSFER_TIMEOUT
                        && (menu == null || !menu.target(grief))) {
                    recoverNavigation(view, now, "Переход на гриф не подтверждён; повторяю подключение.");
                }
            }
            case WAIT_TAB -> {
                if (menu != null || !view.tabPresent() || !griefConfirmed(view)) {
                    stableSince = -1;
                    if (now - phaseStartedAt >= TAB_TIMEOUT)
                        recoverNavigation(view, now, "Таб грифа не загрузился; повторяю подключение.");
                    return;
                }
                if (view.worldEpoch() != enteredWorld || view.tabFingerprint() != lastTabFingerprint) {
                    enteredWorld = view.worldEpoch();
                    lastTabFingerprint = view.tabFingerprint();
                    stableSince = now;
                }
                if (stableSince < 0) stableSince = now;
                // Give the proxy time to replace the hub tab list; do not scan a half-loaded tab.
                if (now - selectedAt >= 1500 && now - stableSince >= requiredTabQuiet(view)) startScan(now);
            }
            case SCANNING -> {
                if (view.compass()) returnToHubDuringScan(view, now);
                else if(view.scoreboardGrief()!=0 && view.scoreboardGrief()!=grief) {
                    partial.addAll(port.scanMatches());setPhase(Phase.WAIT_HUB);port.cancelScan();
                    recoverNavigation(view,now,"Переход на другой гриф во время проверки; возвращаюсь на выбранный.");
                }
            }
            case RETRY_SCAN -> {
                if (view.compass()) { recoverNavigation(view, now, "Сервер вернул в хаб; возвращаюсь на тот же гриф."); return; }
                if(view.scoreboardGrief()!=0 && view.scoreboardGrief()!=grief) {recoverNavigation(view,now,"Текущий гриф изменился; повторяю выбранный.");return;}
                if (menu == null && view.tabPresent() && now >= retryAt) {
                    if (stableSince < 0 || view.tabFingerprint() != lastTabFingerprint) {
                        stableSince = now; lastTabFingerprint = view.tabFingerprint();
                    }
                    if (now - stableSince >= requiredTabQuiet(view)) startScan(now);
                }
            }
            case FINISHED -> {}
        }
        if (active() && now - phaseStartedAt >= MENU_TIMEOUT
                && (phase == Phase.WAIT_GRIEFS || phase == Phase.WAIT_PAGE2)) {
            recoverNavigation(view, now, "Меню не завершило переход; открываю выбор текущего грифа заново.");
        }
        if (active() && phase != Phase.SCANNING && now >= nextStatus) {
            nextStatus = now + 5000;
            port.status("Full: гриф #" + grief + "/56 — " + phaseLabel() + ", продолжаю ожидание.");
        }
    }

    private static long requiredTabQuiet(View view) {
        // A just-created tab can contain only our own entry before the other players arrive.
        // Genuine empty servers are allowed, but only after a longer stable, loaded-world interval.
        return view.eligiblePlayers() == 0 ? 5000 : 750;
    }

    private boolean griefConfirmed(View view) {
        if (view.compass() || (view.scoreboardGrief() != 0 && view.scoreboardGrief() != grief)) return false;
        // Closing a menu alone is NOT a server transfer. Latency-only tab updates are not evidence either.
        return (view.scoreboardGrief() == grief && selectedScoreboard != grief) || view.worldEpoch() != selectedWorld
                || view.positionRevision() != selectedPosition || view.tabFingerprint() != selectedTabFingerprint;
    }

    private void recoverNavigation(View view, long now, String reason) {
        port.status("Гриф #" + grief + ": " + reason);
        stableSince = -1;
        transferSync = -1;
        if (view.compass()) {
            setPhase(Phase.WAIT_ROOT);
            nextAction = now + 1000;
            port.closeMenu();
            port.useCompass();
        } else begin(now);
    }

    private void returnToHubDuringScan(View view, long now) {
        partial.addAll(port.scanMatches());
        setPhase(Phase.WAIT_HUB); // Ignore the cancellation callback; keep this full run alive.
        port.cancelScan();
        recoverNavigation(view, now, "Сервер вернул в хаб; повторяю этот гриф.");
    }

    private void select(ServerMenus.Menu menu, View view, long now) {
        selectedAt = now;
        selectedWorld = view.worldEpoch();
        selectedTabRevision = view.tabRevision();
        selectedTabFingerprint = view.tabFingerprint();
        selectedPosition = view.positionRevision();
        selectedScoreboard = view.scoreboardGrief();
        transferAcknowledged = false;
        transferSync = menu.syncId();
        nextAction = now + 400;
        setPhase(Phase.WAIT_TRANSFER);
        port.click(menu.syncId(), ServerMenus.slotFor(grief));
    }

    public void menuClosed(int syncId) {
        if (phase == Phase.WAIT_TRANSFER && transferSync == syncId) transferAcknowledged = true;
    }

    private void startScan(long now) {
        attempted = true;
        setPhase(Phase.SCANNING);
        // Set the phase first: an empty tab may finish synchronously in a port implementation.
        if (!port.startScan() && phase == Phase.SCANNING) {
            retryAt = now + 1500;
            setPhase(Phase.RETRY_SCAN);
        }
    }

    public void scanFinished(boolean complete, List<String> names, String reason, long now) {
        if (phase != Phase.SCANNING) return;
        currentTime = now;
        partial.addAll(names);
        if (!complete) {
            retries++;
            retryAt = now + Math.max(1500, retryDrainMs);
            setPhase(Phase.RETRY_SCAN);
            stableSince = -1;
            port.status("Гриф #" + grief + ": проверка не завершена, повтор " + retries + " на этом же грифе. " + reason);
            return;
        }
        // A successful attempt is authoritative; a previous incomplete attempt is not merged into it.
        if (!names.isEmpty()) lines.add(format(grief, names));
        completed++;
        partial.clear();
        attempted = false;
        if (!port.persist(List.copyOf(lines))) { finish(false, "Не удалось записать результат."); return; }
        if (routeIndex + 1 == route.size()) {
            finish(true, route.size() == 56 ? "Все 56 грифов проверены."
                    : "Все выбранные грифы проверены. Всего: " + lines.size() + ".");
            return;
        }
        grief = route.get(++routeIndex);
        retries = 0;
        hubPending = true;
        setPhase(Phase.WAIT_HUB);
        nextAction = now + RETURN_QUIET_MS;
    }

    public void stop(String reason) {
        if (!active()) return;
        if (attempted) partial.addAll(port.scanMatches());
        phase = Phase.FINISHED; // Stop before cancel callbacks, so they cannot schedule a restart.
        cancelOnce();
        var saved = new ArrayList<>(lines);
        if (attempted && !partial.isEmpty()) saved.add(format(grief, List.copyOf(partial)));
        boolean written = port.persist(saved);
        port.finished(false, reason + " Сохранён частичный результат."
                + (written ? "" : " Ошибка записи файла."));
    }

    private void finish(boolean complete, String reason) {
        phase = Phase.FINISHED;
        cancelOnce();
        port.finished(complete, reason);
    }
    private void cancelOnce() { if (!cancelled) { cancelled = true; port.cancelScan(); } }
    private void setPhase(Phase next) {
        if (phase != next) phaseStartedAt = currentTime;
        phase = next;
        nextStatus = currentTime + 5000;
        port.status("Full: гриф #" + grief + "/56 — " + phaseLabel());
    }
    public String phaseLabel() {
        return switch (phase) {
            case WAIT_HUB -> "ожидание хаба";
            case WAIT_ROOT -> "выбор режима";
            case WAIT_GRIEFS -> "выбор грифа";
            case WAIT_PAGE2 -> "вторая страница";
            case WAIT_TRANSFER -> "подключение к грифу";
            case WAIT_TAB -> "ожидание загрузки таба";
            case SCANNING -> "проверка FP";
            case RETRY_SCAN -> "повтор проверки на текущем грифе";
            case FINISHED -> "завершено";
        };
    }
    public boolean active() { return phase != Phase.FINISHED; }
    public Phase phase() { return phase; }
    public int grief() { return grief; }
    public int completedGriefs() { return completed; }
    public List<String> lines() { return List.copyOf(lines); }
    public static String format(int grief, List<String> names) {
        return (names.isEmpty() ? "" : String.join(" ", names) + " ") + "- grief #" + grief;
    }
}
