import net.spidiboost.sixubaafp.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Read-only replay of a caller-specified real debug.log; never connects to Minecraft. */
public class ReplaySavedLog {
    static final class Reply {
        String nick; boolean ended; List<String> lines=new ArrayList<>();
        Reply(String nick){this.nick=nick;}
    }
    static final class Port implements FpScan.Port {
        long time; String nick; List<String> sent=new ArrayList<>(); FpScan.Result result;
        FpScan scan=new FpScan(this,()->time);
        Port(String nick){this.nick=nick;}
        public void send(String command){sent.add(command);}
        public Collection<String> online(){return List.of(nick);}
        public void log(String message){}
        public void finish(FpScan.Result r){result=r;}
        void feed(String value){scan.accept(FpProtocol.Line.plain(value.replace("\\n","\n")));}
        void tick(long delta){time+=delta;scan.tick();}
    }
    static void require(boolean ok,String description){if(!ok)throw new AssertionError(description);}
    public static void main(String[] args)throws Exception {
        List<Reply> replies=new ArrayList<>();Reply current=null;
        for(String line:Files.readAllLines(Path.of(args[0]))) {
            if(line.contains("SEND /")) {
                current=null;
                Matcher send=Pattern.compile("SEND /hist (\\S+)").matcher(line);
                if(send.find()){current=new Reply(send.group(1));replies.add(current);}
            } else if(current!=null&&line.contains("RX phase=HIST")&&line.contains(" text=")) {
                current.lines.add(line.substring(line.indexOf(" text=")+6));
            } else if(current!=null&&line.contains("END phase=HIST"))current.ended=true;
        }
        int complete=0,empty=0,truncated=0;List<String> matches=new ArrayList<>();
        for(Reply r:replies) {
            if(!r.ended)continue;
            Port p=new Port(r.nick);p.scan.start(List.of(r.nick),true);
            p.feed("Сканирование "+r.nick+".\n§6"+r.nick);p.tick(5);
            require(p.sent.equals(List.of("dupeip "+r.nick,"hist "+r.nick+" ban 100")),"serialized setup "+r.nick);
            int expected=-1,records=0;boolean historyStarted=false;
            for(String line:r.lines){
                String clean=FpProtocol.Line.plain(line).clean().text();
                Matcher count=Pattern.compile("^История .*Лимит: (\\d+)").matcher(clean);
                if(count.find())expected=Integer.parseInt(count.group(1));
                if(clean.startsWith(r.nick+" был забанен"))records++;
                if(clean.matches("^[-—–]+\\s*\\[\\d{4}-.*")||clean.contains(" был забанен ")||clean.startsWith("По причине:"))historyStarted=true;
                p.feed(line);
            }
            if(expected>=0&&!historyStarted){
                p.tick(4);require(p.result==null,"normal queue gap "+r.nick);p.tick(1);
                require(p.scan.status().contains("hist: 1;"),"empty query already advanced at 5 ms "+r.nick);
                p.tick(95);empty++;
            }
            else {p.tick(4);require(p.result==null,"five ms grace "+r.nick);p.tick(1);if(p.result==null)p.tick(100);}
            if(p.result==null){p.scan.stop("replay genuinely unfinished reason");require(!p.result.complete(),"partial flag");truncated++;continue;}
            require(p.result!=null&&p.result.complete(),"full reply "+r.nick+" expected="+expected+" records="+records);
            require(p.sent.size()==2,"no extra sends "+r.nick);matches.addAll(p.result.names());complete++;
        }
        System.out.println("PASS real history replays="+(complete+truncated)+" complete="+complete+" headerOnlyEmpty="+empty+" incompleteDetected="+truncated);
        System.out.println("MATCH_COUNT "+matches.size());
    }
}
