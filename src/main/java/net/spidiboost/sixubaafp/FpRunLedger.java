package net.spidiboost.sixubaafp;

import java.util.*;

/** Client-thread state shared by all griefs of ONE user-initiated run. */
public final class FpRunLedger {
    public record Account(String nick, boolean red) {}
    final Set<String> dupeSent=new HashSet<>(), histSent=new HashSet<>();
    final Map<String,List<Account>> groups=new HashMap<>();
    final Map<String,String> findings=new LinkedHashMap<>();
    int completed;
}
