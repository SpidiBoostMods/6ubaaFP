package net.spidiboost.sixubaafp;

import java.util.*;
import java.util.function.LongSupplier;

/** Serial targets. Retries stay with the same owner; repeated windows are drained before advancing. */
public final class InventoryScan {
    public interface Port {
        void send(String command);
        Collection<String> online();
        void close(int sync);
        boolean checkpoint(List<String> matches);
        void log(String value);
        void finish(FpScan.Result result);
        default void notice(String kind,String nick,int attempt) {}
    }
    private enum Phase { NEXT, OPEN, CONTENT, DRAIN, FINISHED }
    private final Port port;private final LongSupplier clock;private final InventoryQuery query;
    private final Thread owner=Thread.currentThread();
    private final Queue<String> pending=new ArrayDeque<>();
    private final LinkedHashMap<String,String> found=new LinkedHashMap<>();
    private final List<String> unresolved=new ArrayList<>();
    private Phase phase=Phase.FINISHED;private String target;private int sync=-1,processed,total,attempts,skipped;
    private long sentAt,nextAt,contentsAt,drainAt;private List<ServerMenus.Item> contents;
    private final Set<Integer> retired=new HashSet<>();
    private static final long RETRY_BASE_MS=1500, RETRY_MAX_MS=5000, RETRY_DRAIN_MS=2000;
    public InventoryScan(Port port,LongSupplier clock,InventoryQuery query){this.port=port;this.clock=clock;this.query=query;}
    private void own(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Inventory scanner must run on the client thread");}
    public void start(Collection<String> names){
        own();if(active())throw new IllegalStateException("Already scanning");
        pending.clear();found.clear();unresolved.clear();retired.clear();processed=skipped=attempts=0;target=null;sync=-1;contents=null;
        var unique=new LinkedHashMap<String,String>();for(String name:names)if(FpProtocol.valid(name))unique.putIfAbsent(FpProtocol.key(name),name);
        pending.addAll(unique.values());total=pending.size();phase=Phase.NEXT;nextAt=clock.getAsLong();tick();
    }
    public void tick(){
        own();if(!active())return;long now=clock.getAsLong();
        if(phase==Phase.NEXT&&now>=nextAt){
            while(!pending.isEmpty()) {
                target=pending.remove();
                if(!online()){processed++;skipped++;port.log("INV SKIP left-tab nick="+target);port.notice("skip",target,0);continue;}
                attempts=0;send(now);return;
            }
            finish(true,"Инвентари проверены.");return;
        }
        if(phase==Phase.DRAIN){
            if(now>=drainAt){phase=Phase.NEXT;target=null;nextAt=now+5;}
            return;
        }
        if((phase==Phase.OPEN||phase==Phase.CONTENT)&&!online()){skip(now,"left TAB");return;}
        if(phase==Phase.CONTENT&&contents!=null&&now-contentsAt>=5){
            boolean match=query.matches(contents);
            if(match)found.putIfAbsent(FpProtocol.key(target),target);
            port.log("INV RESULT nick="+target+" sync="+sync+" slots="+contents.size()+" match="+match+" requirements="+query.describe(contents));
            if(!port.checkpoint(matches())){stop("Не удалось сохранить результат.");return;}
            port.notice(match?"match":"checked",target,attempts);
            done(now,attempts>1);return;
        }
        if((phase==Phase.OPEN||phase==Phase.CONTENT)&&now-sentAt>=retryDelay()){
            int closing=sync;sync=-1;phase=Phase.OPEN;contents=null;
            closeSafely(closing);send(now);
        }
    }
    private boolean online(){return port.online().stream().anyMatch(n->n.equalsIgnoreCase(target));}
    private long retryDelay(){return Math.min(RETRY_MAX_MS,RETRY_BASE_MS*Math.max(1,Math.min(attempts,4)));}
    private void send(long now){
        phase=Phase.OPEN;sync=-1;contents=null;sentAt=now;attempts++;
        port.log("INV SEND nick="+target+" attempt="+attempts+" processed="+processed+"/"+total);
        port.notice(attempts==1?"query":"retry",target,attempts);
        try{port.send("invsee "+target);}catch(RuntimeException e){
            port.log("INV SEND ERROR nick="+target+" type="+e.getClass().getSimpleName());
            stop("Не удалось отправить /invsee; сохранён частичный результат.");
        }
    }
    public void opened(int id,String title){
        own();if(!active())return;
        if(id<=0||!FpProtocol.normalize(title).equals("player")){port.log("INV IGNORE WINDOW sync="+id+" title="+title);return;}
        if(phase==Phase.DRAIN||phase==Phase.NEXT){
            retired.add(id);phase=Phase.DRAIN;drainAt=clock.getAsLong()+RETRY_DRAIN_MS;
            port.log("INV DRAIN repeated window sync="+id+" owner="+target);closeSafely(id);return;
        }
        if(phase!=Phase.OPEN&&phase!=Phase.CONTENT)return;
        // A repeated attempt may replace the first window. Both still belong to this target.
        if(sync>=0&&id!=sync)retired.add(sync);
        contents=null;
        sync=id;phase=Phase.CONTENT;port.log("INV OPEN nick="+target+" sync="+id+"; awaiting full inventory packet");
    }
    /** Called only AFTER vanilla applies a complete InventoryS2CPacket for this exact window. */
    public void contents(int id,List<ServerMenus.Item> items){
        own();if(retired.contains(id)&&id!=sync){if(phase==Phase.DRAIN)drainAt=clock.getAsLong()+RETRY_DRAIN_MS;return;}if(phase!=Phase.CONTENT||id!=sync)return;
        contents=List.copyOf(items);contentsAt=clock.getAsLong();port.log("INV CONTENT nick="+target+" sync="+id+" slots="+items.size());
    }
    /** Slot updates are snapshots only after a full baseline; they cannot make an unloaded menu 'empty'. */
    public void changed(int id,List<ServerMenus.Item> items){own();if(phase==Phase.CONTENT&&id==sync&&contents!=null){contents=List.copyOf(items);contentsAt=clock.getAsLong();}}
    public void closed(int id){own();if(phase==Phase.CONTENT&&sync==id){sync=-1;contents=null;phase=Phase.OPEN;sentAt=clock.getAsLong()-retryDelay()+250;port.notice("retry",target,attempts+1);port.log("INV CLOSED before contents; retry same owner="+target);}}
    public void message(FpProtocol.Line packet){
        own();if(!active()||phase==Phase.NEXT||phase==Phase.DRAIN)return;
        for(var line:packet.lines()) {
            String text=FpProtocol.normalize(line.text());
            text=text.replaceFirst("^(?:ошибка|error)\\s*:\\s*","");
            if(text.startsWith("нет прав")||text.startsWith("недостаточно прав")||text.startsWith("you do not have permission")||text.startsWith("неизвестная или неполная команда")||text.startsWith("unknown command")){
                stop("Сервер отклонил /invsee: "+line.text());return;
            }
            boolean unavailable=text.contains("не найден")||text.contains("не в сети")||text.contains("не онлайн")||text.contains("not found")||text.contains("not online")||text.contains("offline");
            if(unavailable&&(text.startsWith("игрок ")||text.startsWith("player ")||text.startsWith("цель "))
                    &&(Arrays.stream(text.split("[^a-z0-9_]+" )).anyMatch(w->w.equals(FpProtocol.key(target)))||text.startsWith("цель не")||text.matches("игрок (?:не найден|не в сети|не онлайн)[.!]?")||text.matches("player (?:not found|not online|is offline)[.!]?"))){
                skip(clock.getAsLong(),line.text());return;
            }
        }
    }
    private void skip(long now,String reason){skipped++;port.log("INV SKIP unavailable nick="+target+" response="+reason);port.notice("skip",target,attempts);done(now,attempts>1||reason.equals("left TAB"));}
    private void done(long now,boolean drain){
        int closing=sync;if(closing>=0)retired.add(closing);
        processed++;sync=-1;contents=null;phase=drain?Phase.DRAIN:Phase.NEXT;
        if(drain)drainAt=now+RETRY_DRAIN_MS;else target=null;
        nextAt=now+5;closeSafely(closing);
    }
    private void closeSafely(int closing){if(closing>=0)try{port.close(closing);}catch(RuntimeException e){port.log("INV CLOSE ERROR "+e.getClass().getSimpleName());}}
    public void stop(String reason){own();if(active())finish(false,reason);}
    private void finish(boolean complete,String reason){
        boolean unfinished=phase==Phase.OPEN||phase==Phase.CONTENT;int closing=sync;phase=Phase.FINISHED;sync=-1;
        closeSafely(closing);
        if(!complete&&target!=null&&unfinished)unresolved.add(target);
        port.log("INV END processed="+processed+"/"+total+" complete="+complete+" unresolved="+unresolved);
        port.finish(new FpScan.Result(matches(),List.copyOf(unresolved),complete,reason,total,processed));
    }
    public boolean active(){return phase!=Phase.FINISHED;}
    public List<String> matches(){return List.copyOf(found.values());}
    public record Progress(int processed,int total,int found,int skipped,int attempts,String target,String phase) {}
    public Progress progress(){return new Progress(processed,total,found.size(),skipped,attempts,target==null?"":target,switch(phase){case NEXT->"Следующий игрок";case OPEN->attempts>1?"Повторяем запрос":"Открываем инвентарь";case CONTENT->"Проверяем предметы";case DRAIN->"Завершаем повторные ответы";case FINISHED->"Завершено";});}
    public String status(){var p=progress();return "Инвентари · "+processed+"/"+total+" · совпадений "+found.size()+" · пропущено "+skipped+(target==null?"":" · "+target)+" · "+p.phase();}
}
