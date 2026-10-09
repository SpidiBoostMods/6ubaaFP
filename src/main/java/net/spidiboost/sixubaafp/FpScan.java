package net.spidiboost.sixubaafp;

import java.util.*;
import java.util.function.LongSupplier;
import java.util.regex.*;

/** One command/response in flight, owned by the client thread. No checkban requests. */
public final class FpScan {
    public interface Port {
        void send(String command);
        Collection<String> online();
        void log(String message);
        void finish(Result result);
        default String currentName(){return "";}
        default void clearChat(){}
    }
    public record Result(List<String> names,List<String> unresolved,boolean complete,String reason,int players,int histories) {}
    private enum Phase { IDLE, DUPE, HIST }
    static final long COMMAND_GAP_MS=5;
    static final long FINAL_TAIL_MS=100;
    private static final int FLAGS=Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE|Pattern.UNICODE_CHARACTER_CLASS;
    private static final Pattern DUPE=Pattern.compile("^сканирование\\s+([A-Za-z0-9_]{1,16})(?=[. :]|$)",FLAGS);
    private static final Pattern HIST=Pattern.compile("^история(?: игрока)?\\s+([A-Za-z0-9_]{1,16})\\s*\\(лимит\\s*:\\s*(\\d+)\\)",FLAGS);
    private static final Pattern ENTRY=Pattern.compile("^([A-Za-z0-9_]{1,16})\\s+был\\s+(забанен|заткнут|предупрежден|предупреждён)(?:\\s|$)",FLAGS);
    private static final Pattern REASON=Pattern.compile("^(?:по причине|причина|reason)\\s*:\\s*(.*)",FLAGS);
    private static final Pattern UNBAN=Pattern.compile("^([A-Za-z0-9_]{1,16})\\s+was\\s+unbanned\\s+by\\s+(.+?)\\s*$",FLAGS);
    private static final Pattern BODY=Pattern.compile("^[A-Za-z0-9_]{1,16}(?:\\s*\\[[CH]])*(?:\\s*,\\s*[A-Za-z0-9_]{1,16}(?:\\s*\\[[CH]])*)*\\s*,?\\s*$",FLAGS);
    private final Port port; private final LongSupplier clock; private final Thread thread=Thread.currentThread();
    private FpRunLedger ledger;private final boolean sharedLedger;
    private final Set<String> redNames=new HashSet<>();
    private final Map<String,LinkedHashMap<String,String>> twins=new HashMap<>();
    private final List<FpRunLedger.Account> group=new ArrayList<>();
    private Phase phase=Phase.IDLE; private String target,reason=""; private boolean header,body,dupeComplete,recordComplete,historyStarted,banEntry,reasonSeen,reasonOpen;
    private int attempts,historyCount,recordCount,expected,dupeCount;
    private long sentAt,lastRelevant,nextSendAt,lastSent=-1000000;
    private final ArrayDeque<String> players=new ArrayDeque<>(),histories=new ArrayDeque<>();
    private final Set<String> visited=new HashSet<>();
    private final LinkedHashMap<String,String> matches=new LinkedHashMap<>(),dupeNames=new LinkedHashMap<>();
    private final LinkedHashSet<String> unresolved=new LinkedHashSet<>();
    // Header-only queries advance immediately. Retain their owners so a delayed
    // body can still be parsed while the next query is waiting for its response.
    private final Map<String,LateHistory> lateHistories=new HashMap<>();
    private final Map<String,HistoryEvidence> evidence=new HashMap<>();
    private String lateWire;
    private long tailUntil;
    private static final class LateHistory {
        final String nick;String reason="";boolean started,ban,seen,open;
        LateHistory(String nick){this.nick=nick;}
    }
    private boolean onlyLocal=true;
    public FpScan(Port port,LongSupplier clock) {this(port,clock,null);}
    public FpScan(Port port,LongSupplier clock,FpRunLedger ledger) {this.port=port;this.clock=clock;this.ledger=ledger;this.sharedLedger=ledger!=null;}
    private void own() {if(Thread.currentThread()!=thread) throw new IllegalStateException("off client thread");}
    public boolean active() {return phase!=Phase.IDLE;}
    public boolean exhausted(){return !active()&&target==null&&players.isEmpty()&&histories.isEmpty();}
    private boolean self(String nick){return nick!=null&&nick.equalsIgnoreCase(port.currentName());}
    public List<String> matches() {
        Map<String,String> online=redNames.isEmpty()?Map.of():local();List<String> result=new ArrayList<>();
        for(var entry:matches.entrySet()){
            if(self(entry.getValue()))continue;
            var associated=twins.getOrDefault(entry.getKey(),new LinkedHashMap<>());
            List<String> present=associated.keySet().stream().filter(online::containsKey).map(online::get).toList();
            result.add(entry.getValue()+(redNames.contains(entry.getKey())&&!present.isEmpty()?" (На сервере: "+String.join(", ",present)+")":""));
        }
        return List.copyOf(result);
    }
    public String status() {return "dupeip: "+dupeCount+"; hist: "+historyCount+"; найдено: "+matches.size()+"; осталось TAB: "+players.size()+"; запрос: "+(target==null?"—":target);}
    public record Progress(int processed,int total,int found,String target,String phase){}
    public Progress progress(){return new Progress(dupeCount,dupeCount+players.size()+(phase==Phase.DUPE&&target!=null?1:0),matches.size(),target==null?"":target,phase==Phase.HIST?"Проверяем историю бана":"Связанные аккаунты");}
    public void start(Collection<String> names,boolean onlyLocal) {
        own(); if(active()) throw new IllegalStateException("scan already active");
        if(!sharedLedger)ledger=new FpRunLedger();redNames.clear();twins.clear();group.clear();
        this.onlyLocal=onlyLocal; players.clear();histories.clear();visited.clear();matches.clear();unresolved.clear();lateHistories.clear();evidence.clear();lateWire=null;tailUntil=0;dupeCount=historyCount=0;
        LinkedHashMap<String,String> unique=new LinkedHashMap<>();for(String name:names)if(FpProtocol.valid(name))unique.putIfAbsent(FpProtocol.key(name),name);
        players.addAll(unique.values()); nextSendAt=Math.max(clock.getAsLong(),lastSent+COMMAND_GAP_MS);target=null;phase=Phase.DUPE;attempts=0;
        port.log("START tab="+players.size()+" localTabOnly="+onlyLocal+" palette=yellow/gold/red/dark-red commandGapMs="+COMMAND_GAP_MS);tick();
    }
    public void accept(FpProtocol.Line packet) {
        own();if(!active())return;
        port.log("RX phase="+phase+" target="+target+" text="+packet.text().replace("\n","\\n"));
        for(var line:packet.lines()) {
            if(!active()||line.text().isEmpty())continue;
            String text=line.text(),lower=FpProtocol.normalize(text);
            if(routeLate(text,lower))continue;
            if(target==null)continue;
            String refusal=lower.replaceFirst("^ошибка\\s*:\\s*","");
            if(refusal.matches("(?:игрок|player|цель) (?:не найден|не в сети|not found|not online)[.!]?")){
                lastRelevant=clock.getAsLong();doneTarget("unavailable");continue;
            }
            // Explicit server refusals, not arbitrary chat containing these words.
            if(lower.startsWith("нет прав")||lower.startsWith("недостаточно прав")||lower.startsWith("you do not have permission")
                    ||lower.startsWith("неизвестная или неполная команда")||lower.startsWith("unknown command")) {stop("Сервер отклонил команду: "+text);return;}
            if(lower.startsWith("слишком быстро")||lower.startsWith("подождите")||lower.startsWith("please wait")) {sentAt=clock.getAsLong()+1500;nextSendAt=sentAt;continue;}
            if((lower.startsWith("игрок ")||lower.startsWith("player ")||lower.startsWith("цель "))
                    && (lower.contains("не найден")||lower.contains("не в сети")||lower.contains("not found")||lower.contains("not online"))) {
                if(lower.contains(FpProtocol.key(target)) || lower.startsWith("цель не")) {lastRelevant=clock.getAsLong();doneTarget("unavailable");}continue;
            }
            if(phase==Phase.DUPE) {
                var m=DUPE.matcher(text);if(m.find()) {if(m.group(1).equalsIgnoreCase(target)){header=true;lastRelevant=clock.getAsLong();}continue;}
                if(!header||!BODY.matcher(text).matches())continue;
                body=true;dupeComplete=!text.stripTrailing().endsWith(",");lastRelevant=clock.getAsLong();var nick=FpProtocol.NICK.matcher(text);
                while(nick.find()) {
                    if(nick.start()>0 && text.charAt(nick.start()-1)=='[')continue;
                    boolean eligible=line.eligibleName(nick.start(),nick.end());
                    port.log("DUPE NAME nick="+nick.group(1)+" eligible-yellow-red="+eligible);
                    if(eligible){dupeNames.putIfAbsent(FpProtocol.key(nick.group(1)),nick.group(1));group.add(new FpRunLedger.Account(nick.group(1),line.redName(nick.start(),nick.end())));}
                }
            } else {
                var h=HIST.matcher(text);if(h.find()){if(h.group(1).equalsIgnoreCase(target)){header=true;expected=Math.min(10000,Integer.parseInt(h.group(2)));lastRelevant=clock.getAsLong();}continue;}
                if(lower.matches("^(?:у .+ )?(?:нет|не найдено) (?:истории|записей|наказаний).*")||lower.startsWith("no history")) {lastRelevant=clock.getAsLong();doneTarget("empty history");continue;}
                if(!header)continue;
                var unban=UNBAN.matcher(text);
                if(unban.matches()&&unban.group(1).equalsIgnoreCase(target)){
                    history(target).unban(unban.group(2));syncEvidence(target);recordComplete=recordCount>0&&reasonSeen;banEntry=reasonOpen=false;lastRelevant=clock.getAsLong();continue;
                }
                if(lower.matches("^[a-z0-9_]{1,16} was (?:unbanned|unmuted) by .+")) {
                    if(!lower.startsWith(FpProtocol.key(target)+" was "))continue;
                    finishEntry();recordComplete=recordCount>0&&reasonSeen;banEntry=reasonOpen=false;lastRelevant=clock.getAsLong();continue;
                }
                var e=ENTRY.matcher(text);
                if(e.find()) {historyStarted=true;finishEntry();banEntry=e.group(1).equalsIgnoreCase(target)&&e.group(2).equalsIgnoreCase("забанен");if(e.group(1).equalsIgnoreCase(target)){recordCount++;history(target).begin(banEntry);}else history(target).boundary();reason="";recordComplete=reasonSeen=reasonOpen=false;lastRelevant=clock.getAsLong();continue;}
                if(text.matches("^[-—–]+\\s*\\[\\d{4}-.*")){historyStarted=true;finishEntry();history(target).boundary();recordComplete=banEntry=false;lastRelevant=clock.getAsLong();continue;}
                var r=REASON.matcher(text);if(r.find()) {historyStarted=true;reason=r.group(1);reasonSeen=true;reasonOpen=!terminalStatus(text);recordComplete=recordCount>0&&!reasonOpen;lastRelevant=clock.getAsLong();finishEntry();continue;}
                if(lower.startsWith("окончание")){finishEntry();recordComplete=recordCount>0&&reasonSeen;banEntry=false;lastRelevant=clock.getAsLong();continue;}
                // True packet-wrapped reasons can continue; normal broadcasts do not.
                if(banEntry&&reasonSeen&&reasonOpen&&!text.contains("»")&&!text.startsWith("[")&&!lower.startsWith("администратор ")&&!lower.startsWith("куратор ")&&!lower.startsWith("ac ")) {
                    reason+=" "+text;lastRelevant=clock.getAsLong();reasonOpen=!terminalStatus(text);recordComplete=!reasonOpen;finishEntry();
                }
            }
        }
    }
    private static boolean terminalStatus(String text){return FpProtocol.normalize(text).matches(".*\\[(?:активный|истек|снят|неактивный|горит)]\\s*$");}
    private boolean routeLate(String text,String lower) {
        if(lower.startsWith("нет прав")||lower.startsWith("недостаточно прав")||lower.startsWith("you do not have permission")
                ||lower.startsWith("неизвестная или неполная команда")||lower.startsWith("unknown command")
                ||lower.startsWith("слишком быстро")||lower.startsWith("подождите")||lower.startsWith("please wait"))return false;
        if(DUPE.matcher(text).find()){lateWire=null;return false;}
        var h=HIST.matcher(text);
        if(h.find()) {
            String key=FpProtocol.key(h.group(1));
            lateWire=phase==Phase.HIST&&h.group(1).equalsIgnoreCase(target)?null:lateHistories.containsKey(key)?key:null;
            if(lateWire!=null){tailUntil=clock.getAsLong()+FINAL_TAIL_MS;return true;}
            return false;
        }
        var entry=ENTRY.matcher(text);
        var unban=UNBAN.matcher(text);
        if(unban.matches()){
            String key=FpProtocol.key(unban.group(1));
            if(phase==Phase.HIST&&unban.group(1).equalsIgnoreCase(target)){lateWire=null;return false;}
            if(lateHistories.containsKey(key)){lateWire=key;history(unban.group(1)).unban(unban.group(2));syncEvidence(unban.group(1));tailUntil=clock.getAsLong()+FINAL_TAIL_MS;port.log("LATE UNBAN nick="+unban.group(1)+" author="+unban.group(2));return true;}
            return false;
        }
        if(entry.find()) {
            String key=FpProtocol.key(entry.group(1));
            if(phase==Phase.HIST&&entry.group(1).equalsIgnoreCase(target)){lateWire=null;return false;}
            if(lateHistories.containsKey(key))lateWire=key;
            else {lateWire=null;return false;}
        }
        LateHistory late=lateWire==null?null:lateHistories.get(lateWire);
        if(late==null)return false;
        boolean relevant=true;
        if(entry.find(0)) {late.started=true;late.ban=entry.group(2).equalsIgnoreCase("забанен");history(late.nick).begin(late.ban);late.seen=late.open=false;late.reason="";}
        else if(text.matches("^[-—–]+\\s*\\[\\d{4}-.*")){history(late.nick).boundary();late.ban=false;late.open=false;}
        else if(lower.startsWith(FpProtocol.key(late.nick)+" was ")&&lower.matches(".* was (?:unbanned|unmuted) by .+")){late.ban=late.open=false;}
        else if(lower.startsWith("окончание")){late.ban=late.open=false;}
        else {
            var r=REASON.matcher(text);
            if(r.find()){late.reason=r.group(1);late.seen=true;late.open=!terminalStatus(text);}
            else if(late.ban&&late.seen&&late.open&&!text.contains("»")&&!text.startsWith("[")&&!lower.startsWith("администратор ")&&!lower.startsWith("куратор ")&&!lower.startsWith("ac ")){late.reason+=" "+text;late.open=!terminalStatus(text);}
            else relevant=false;
        }
        if(!relevant)return false;
        tailUntil=clock.getAsLong()+FINAL_TAIL_MS;
        if(late.ban&&late.seen){history(late.nick).reason(late.reason);syncEvidence(late.nick);}
        port.log("LATE HISTORY nick="+late.nick+" text="+text);
        return true;
    }
    private void finishEntry() {
        if(banEntry&&reasonSeen){history(target).reason(reason);syncEvidence(target);}
    }
    private HistoryEvidence history(String nick){return evidence.computeIfAbsent(FpProtocol.key(nick),k->new HistoryEvidence());}
    private void syncEvidence(String nick){String key=FpProtocol.key(nick);boolean valid=history(nick).matches()&&!self(nick);
        if(valid){ledger.findings.putIfAbsent(key,nick);if(matches.putIfAbsent(key,nick)==null)port.log("MATCH nick="+nick+" evidence=eligible FP ban");}
        else {ledger.findings.remove(key);if(matches.remove(key)!=null)port.log("RETRACT MATCH nick="+nick+" evidence=manual unban");}
    }
    private Map<String,String> local() {Map<String,String> result=new HashMap<>();for(String n:port.online())if(FpProtocol.valid(n))result.put(FpProtocol.key(n),n);return result;}
    private void resetResponse() {header=body=dupeComplete=recordComplete=historyStarted=banEntry=reasonSeen=reasonOpen=false;dupeNames.clear();group.clear();reason="";recordCount=expected=0;}
    private void useGroup(List<FpRunLedger.Account> accounts){
        Map<String,String> tab=onlyLocal||accounts.stream().anyMatch(FpRunLedger.Account::red)?local():Map.of();
        for(var account:accounts){
            String key=FpProtocol.key(account.nick());
            if(account.red()){
                redNames.add(key);var associated=twins.computeIfAbsent(key,k->new LinkedHashMap<>());
                for(var other:accounts)if(!other.red()&&!key.equals(FpProtocol.key(other.nick()))&&tab.containsKey(FpProtocol.key(other.nick())))associated.putIfAbsent(FpProtocol.key(other.nick()),tab.get(FpProtocol.key(other.nick())));
            }
            if(self(account.nick())){port.log("SKIP current account nick="+account.nick());continue;}
            if(onlyLocal&&!tab.containsKey(key))continue;
            if(ledger.findings.containsKey(key))matches.putIfAbsent(key,ledger.findings.get(key));
            if(!ledger.histSent.contains(key)&&visited.add(key))histories.add(account.nick());
        }
    }
    private void sendTarget() {
        resetResponse();attempts++;sentAt=lastRelevant=clock.getAsLong();long gap=sentAt-lastSent;lastSent=sentAt;nextSendAt=sentAt+COMMAND_GAP_MS;
        String command=(phase==Phase.DUPE?"dupeip ":"hist ")+target+(phase==Phase.HIST?" ban 100":"");
        port.log("SEND /"+command+" attempt="+attempts+" gapMs="+gap);port.send(command);
    }
    private void doneTarget(String why) {
        port.log("END phase="+phase+" nick="+target+" reason="+why+" records="+recordCount+" expected="+expected+" elapsedMs="+(clock.getAsLong()-lastSent));
        if(phase==Phase.DUPE) {
            dupeCount++;ledger.groups.put(FpProtocol.key(target),List.copyOf(group));useGroup(group);
            phase=histories.isEmpty()?Phase.DUPE:Phase.HIST;
        } else {
            finishEntry();historyCount++;
            // Keep EVERY completed owner, not just empty histories: the displayed
            // limit counts events differently, and later records may still arrive.
            LateHistory late=new LateHistory(target);late.started=historyStarted;late.ban=banEntry;late.reason=reason;late.seen=reasonSeen;late.open=reasonOpen;
            lateHistories.put(FpProtocol.key(target),late);lateWire=FpProtocol.key(target);
            if(header&&expected>0&&(!historyStarted||recordCount<expected))tailUntil=Math.max(tailUntil,lastRelevant+FINAL_TAIL_MS);
            if(++ledger.completed%20==0){port.log("CHAT CLEAR histories="+ledger.completed+" preserved="+matches.size());port.clearChat();}
            if(histories.isEmpty())phase=Phase.DUPE;
        }
        target=null;attempts=0;nextSendAt=Math.max(lastRelevant+COMMAND_GAP_MS,lastSent+COMMAND_GAP_MS);
    }
    public void tick() {
        own();if(!active())return;long now=clock.getAsLong();
        if(target!=null) {
            // The server's advertised limit is NOT its returned record count.
            // Advance after a structurally complete reason, never on the entry line.
            // Pump only after packet processing; never recursively send from accept().
            boolean ready=header&&(phase==Phase.DUPE?body&&dupeComplete:
                    expected==0&&!historyStarted||recordComplete);
            if(ready&&now-lastRelevant>=COMMAND_GAP_MS) {doneTarget("response complete");}
            // A header-only reply can have ANY limit. The limit is not evidence
            // that a history entry exists. Use only the normal queue gap, after
            // draining received packets; there is no separate empty-reply cooldown.
            else if(phase==Phase.HIST&&header&&!historyStarted&&now-lastRelevant>=COMMAND_GAP_MS) {
                lateHistories.put(FpProtocol.key(target),new LateHistory(target));lateWire=FpProtocol.key(target);
                tailUntil=Math.max(tailUntil,lastRelevant+FINAL_TAIL_MS);
                doneTarget("header-only empty history (normal 5 ms queue gap)");
            }
            else if(now-sentAt>=6000) {
                port.log("INCOMPLETE advance without duplicate request records="+recordCount+" advertisedLimit="+expected);
                unresolved.add((phase==Phase.DUPE?"dupeip ":"hist ")+target);doneTarget("no complete response; retained late owner");
            }
            if(target!=null)return;
        }
        if(now<nextSendAt)return;
        while(phase==Phase.HIST&&!histories.isEmpty()) {
            String name=histories.removeFirst();if(!self(name)&&(!onlyLocal||local().containsKey(FpProtocol.key(name)))&&ledger.histSent.add(FpProtocol.key(name))){target=name;break;}
            port.log("SKIP left TAB nick="+name);
        }
        if(phase==Phase.HIST&&target==null)phase=Phase.DUPE;
        while(target==null&&!players.isEmpty()){
            String name=players.removeFirst();String key=FpProtocol.key(name);
            if(self(name)){port.log("SKIP current account dupeip="+name);continue;}
            if(ledger.dupeSent.add(key)){target=name;break;}
            port.log("CACHE dupeip="+name);dupeCount++;useGroup(ledger.groups.getOrDefault(key,List.of()));
            if(!histories.isEmpty()){phase=Phase.HIST;nextSendAt=now;return;}
        }
        if(target==null){
            // Once at the end only; never a per-nickname cooldown. Other queries
            // normally cover this tail interval with their ordinary network RTT.
            if(now<tailUntil)return;
            for(LateHistory late:lateHistories.values())if(late.started&&(!late.seen||late.open))unresolved.add("hist "+late.nick);
            finish(unresolved.isEmpty(),unresolved.isEmpty()?"Проверка завершена.":"Есть неполные ответы; сохранён частичный результат.");return;
        }
        sendTarget();
    }
    public void stop(String why) {own();if(active()){
        if(target!=null)unresolved.add((phase==Phase.DUPE?"dupeip ":"hist ")+target);
        for(String name:histories)unresolved.add("hist "+name);for(String name:players)unresolved.add("dupeip "+name);
        finish(false,why);
    }}
    private void finish(boolean complete,String reason) {phase=Phase.IDLE;port.finish(new Result(matches(),List.copyOf(unresolved),complete,reason,dupeCount,historyCount));}
}
