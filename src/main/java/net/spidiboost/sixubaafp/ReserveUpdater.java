package net.spidiboost.sixubaafp;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Reserve before client entrypoints: older bundled coordinators must not ignore update off. */
public final class ReserveUpdater implements PreLaunchEntrypoint {
    @Override public void onPreLaunch(){
        var share=FabricLoader.getInstance().getObjectShare();var gate=new AtomicReference<Runnable>();
        var preferred=new AtomicReference<Runnable>();
        Runnable relay=()->{Runnable owner=preferred.get();if(owner!=null)owner.run();};
        // Older relocated copies see a occupied gate and cannot win the client initialization race.
        gate.set(relay);
        if(share.putIfAbsent("spidiboost:update-coordinator-v1",relay)==null){
            share.put("spidiboost:update-reservation-v2",List.of(relay,gate));
            share.put("spidiboost:update-preferred-sixubaafp-v3",preferred);
        }
    }
}
