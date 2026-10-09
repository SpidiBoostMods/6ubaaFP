package net.spidiboost.sixubaafp;

import java.util.ArrayList;
import java.util.List;

/** A manual unban invalidates its ban, not a different FP ban of the same account. */
final class HistoryEvidence {
    static final class Entry {boolean ban,manual;String reason="";}
    private final List<Entry> entries=new ArrayList<>();
    Entry current;
    void begin(boolean ban){current=new Entry();current.ban=ban;entries.add(current);}
    void boundary(){current=null;}
    void reason(String text){if(current!=null)current.reason=text;}
    void unban(String author){if(current!=null)current.manual=!author.replaceFirst("[.!]+$","").strip().equalsIgnoreCase("ReallyWorld");}
    boolean matches(){return entries.stream().anyMatch(e->e.ban&&!e.manual&&FpProtocol.market(e.reason));}
}
