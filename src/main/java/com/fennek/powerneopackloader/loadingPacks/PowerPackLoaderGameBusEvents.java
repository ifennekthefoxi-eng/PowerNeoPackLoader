package com.fennek.powerneopackloader.loadingPacks;

import com.fennek.powerneopackloader.CoreComponentes.PowerPackLoaderCollisionLoader;
import com.fennek.powerneopackloader.PowerNeoPackLoader;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

@EventBusSubscriber(modid = PowerNeoPackLoader.MOD_ID) // Defaults to Game/Forge Bus
public class PowerPackLoaderGameBusEvents {

    @SubscribeEvent
    public static void onServerWorldTick(net.neoforged.neoforge.event.tick.LevelTickEvent.Post event) {
        Level world = event.getLevel();
        if (world.isClientSide()) return;
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        event.getEntity().getPersistentData().remove("IsUsingSteeringWheel");
    }

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        // Backs LoadableModelHorizontalDirectionalBlock#getShape - see
        // PowerPackLoaderCollisionLoader's class doc for the full resolution chain.
        event.addListener(new PowerPackLoaderCollisionLoader());
    }


}