package net.spidiboost.sixubaafp;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Reserve before client entrypoints: older bundled coordinators must not ignore update off. */
public final class ReserveUpdater implements PreLaunchEntrypoint {
    @Override public void onPreLaunch(){
        var share=FabricLoader.getInstance().getObjectShare();var gate=new AtomicReference<Runnable>();
        Runnable relay=()->{Runnable owner=gate.get();if(owner!=null)owner.run();};
        if(share.putIfAbsent("spidiboost:update-coordinator-v1",relay)==null)
            share.put("spidiboost:update-reservation-v2",List.of(relay,gate));
    }
}
