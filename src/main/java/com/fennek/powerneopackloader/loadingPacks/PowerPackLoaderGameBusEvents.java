package com.fennek.powerneopackloader.loadingPacks;

import com.fennek.powerneopackloader.CoreComponentes.PowerPackLoaderCollisionLoader;
import com.fennek.powerneopackloader.PowerNeoPackLoader;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

/**
 * Game-bus listeners the library needs on behalf of every mod using it. Subscribed once, under this
 * library's own mod id - consuming mods add nothing.
 * <p>
 * A general-purpose library has no business touching player state or ticking levels, so this is
 * deliberately down to the one listener that pack blocks actually require. (An empty level-tick
 * hook and a player-login hook clearing a "IsUsingSteeringWheel" flag used to live here, both
 * carried over from the mod this library was extracted from; neither has anything to do with
 * loading blocks from packs, and the login one was silently editing every player's persistent data
 * in any game that merely had this library installed.)
 */
@EventBusSubscriber(modid = PowerNeoPackLoader.MOD_ID) // Defaults to the game/NeoForge bus
public class PowerPackLoaderGameBusEvents {

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        // Backs LoadableModelHorizontalDirectionalBlock#getShape - see
        // PowerPackLoaderCollisionLoader's class doc for the full resolution chain. One listener
        // covers every pack from every mod: it reads data/<packId>/collisions/ across the whole
        // merged data stack and keys what it finds by each block's real registry name.
        event.addListener(new PowerPackLoaderCollisionLoader());
    }
}
