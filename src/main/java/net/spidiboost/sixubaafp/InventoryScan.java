package net.spidiboost.sixubaafp;

import java.util.*;
import java.util.function.LongSupplier;

/** One request in flight: generic 'Player' titles do not identify the owner. Never guess after timeout. */
public final class InventoryScan {
    public interface Port {
        void send(String command);
        Collection<String> online();
        void close(int sync);
        boolean checkpoint(List<String> matches);
        void log(String value);
        void finish(FpScan.Result result);
    }
    private enum Phase { NEXT, OPEN, CONTENT, FINISHED }
    private final Port port;private final LongSupplier clock;private final InventoryQuery query;
    private final Thread owner=Thread.currentThread();
    private final Queue<String> pending=new ArrayDeque<>();
    private final LinkedHashMap<String,String> found=new LinkedHashMap<>();
    private final List<String> unresolved=new ArrayList<>();
    private Phase phase=Phase.FINISHED;private String target;private int sync=-1,processed,total;
    private long sentAt,nextAt,contentsAt;private List<ServerMenus.Item> contents;
    public InventoryScan(Port port,LongSupplier clock,InventoryQuery query){this.port=port;this.clock=clock;this.query=query;}
    private void own(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Inventory scanner must run on the client thread");}
    public void start(Collection<String> names){
        own();if(active())throw new IllegalStateException("Already scanning");
        pending.clear();found.clear();unresolved.clear();processed=0;target=null;sync=-1;contents=null;
        var unique=new LinkedHashMap<String,String>();for(String name:names)if(FpProtocol.valid(name))unique.putIfAbsent(FpProtocol.key(name),name);
        pending.addAll(unique.values());total=pending.size();phase=Phase.NEXT;nextAt=clock.getAsLong();tick();
    }
    public void tick(){
        own();if(!active())return;long now=clock.getAsLong();
        if(phase==Phase.NEXT&&now>=nextAt){
            while(!pending.isEmpty()) {
                target=pending.remove();
                if(port.online().stream().noneMatch(n->n.equalsIgnoreCase(target))){processed++;port.log("INV SKIP left-tab nick="+target);continue;}
                sync=-1;contents=null;phase=Phase.OPEN;sentAt=now;
                port.log("INV SEND nick="+target+" processed="+processed+"/"+total);
                port.send("invsee "+target);return;
            }
            finish(true,"Инвентари проверены.");return;
        }
        if(phase==Phase.CONTENT&&contents!=null&&now-contentsAt>=5){
            boolean match=contents.stream().anyMatch(query::matches);
            if(match)found.putIfAbsent(FpProtocol.key(target),target);
            port.log("INV RESULT nick="+target+" sync="+sync+" slots="+contents.size()+" match="+match);
            if(!port.checkpoint(matches())){stop("Не удалось сохранить результат.");return;}
            int closing=sync;done(now);port.close(closing);return;
        }
        if((phase==Phase.OPEN||phase==Phase.CONTENT)&&now-sentAt>=8000){
            unresolved.add(target);stop("Сервер отклонил/не завершил /invsee "+target+"; остановка без подмены владельца запоздалого окна.");
        }
    }
    public void opened(int id,String title){
        own();if(!active())return;
        if(phase==Phase.CONTENT&&id!=sync){unresolved.add(target);stop("Сервер отклонил /invsee: вместо ожидаемого инвентаря открыто другое окно.");return;}
        if(phase==Phase.NEXT){stop("Сервер отклонил /invsee: неожиданное окно между запросами; сохранён частичный результат.");return;}
        if(phase!=Phase.OPEN)return;
        if(id<=0||!FpProtocol.normalize(title).equals("player")){port.log("INV IGNORE WINDOW sync="+id+" title="+title);return;}
        sync=id;phase=Phase.CONTENT;port.log("INV OPEN nick="+target+" sync="+id+"; awaiting full inventory packet");
    }
    /** Called only AFTER vanilla applies a complete InventoryS2CPacket for this exact window. */
    public void contents(int id,List<ServerMenus.Item> items){
        own();if(phase!=Phase.CONTENT||id!=sync)return;
        contents=List.copyOf(items);contentsAt=clock.getAsLong();port.log("INV CONTENT nick="+target+" sync="+id+" slots="+items.size());
    }
    /** Slot updates are snapshots only after a full baseline; they cannot make an unloaded menu 'empty'. */
    public void changed(int id,List<ServerMenus.Item> items){own();if(phase==Phase.CONTENT&&id==sync&&contents!=null){contents=List.copyOf(items);contentsAt=clock.getAsLong();}}
    public void closed(int id){own();if(phase==Phase.CONTENT&&sync==id){unresolved.add(target);stop("Сервер отклонил /invsee: окно закрылось до завершения проверки "+target);}}
    public void message(FpProtocol.Line packet){
        own();if(!active()||phase==Phase.NEXT)return;
        for(var line:packet.lines()) {
            String text=FpProtocol.normalize(line.text());
            if(text.startsWith("нет прав")||text.startsWith("недостаточно прав")||text.startsWith("you do not have permission")||text.startsWith("неизвестная или неполная команда")||text.startsWith("unknown command")){
                stop("Сервер отклонил /invsee: "+line.text());return;
            }
            boolean unavailable=text.contains("не найден")||text.contains("не в сети")||text.contains("не онлайн")||text.contains("not found")||text.contains("not online")||text.contains("offline");
            if(unavailable&&(text.startsWith("игрок ")||text.startsWith("player ")||text.startsWith("цель "))
                    &&(Arrays.stream(text.split("[^a-z0-9_]+" )).anyMatch(w->w.equals(FpProtocol.key(target)))||text.startsWith("цель не")||text.matches("игрок (?:не найден|не в сети|не онлайн)[.!]?")||text.matches("player (?:not found|not online|is offline)[.!]?"))){
                port.log("INV SKIP unavailable nick="+target+" response="+line.text());int closing=sync;done(clock.getAsLong());if(closing>=0)port.close(closing);return;
            }
        }
    }
    private void done(long now){processed++;phase=Phase.NEXT;target=null;sync=-1;contents=null;nextAt=now+5;}
    public void stop(String reason){own();if(active())finish(false,reason);}
    private void finish(boolean complete,String reason){
        int closing=sync;phase=Phase.FINISHED;sync=-1;
        if(closing>=0)try{port.close(closing);}catch(RuntimeException e){port.log("INV CLOSE ERROR "+e.getClass().getSimpleName()+"; results will still be saved");}
        port.log("INV END processed="+processed+"/"+total+" complete="+complete+" unresolved="+unresolved);
        port.finish(new FpScan.Result(matches(),List.copyOf(unresolved),complete,reason,total,processed));
    }
    public boolean active(){return phase!=Phase.FINISHED;}
    public List<String> matches(){return List.copyOf(found.values());}
    public String status(){return "Инвентари: "+processed+"/"+total+", найдено "+found.size()+(target==null?"":"; "+target)+" ("+phase+")";}
}
