package com.fennek.powerneopackloader.loadingPacks;

import com.fennek.powerneopackloader.PowerNeoPackLoader;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddPackFindersEvent;

/**
 * Adds every registered loader's packs to the game's pack repositories, for both sides. Subscribed
 * once under this library's own mod id - a consuming mod's packs are picked up here too, because
 * {@link PowerPackLoaderPacksLoader#ALL_LOADERS} holds every loader from every mod.
 * <p>
 * Capabilities a pack block entity needs are that mod's own business and belong on that mod's bus,
 * so there is deliberately no capability hook here. (An empty one used to sit in this class,
 * carried over from the mod this library was extracted from.)
 */
@EventBusSubscriber(modid = PowerNeoPackLoader.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public class PowerPackLoaderModBusEvents {

    @SubscribeEvent
    public static void onAddPackFinders(AddPackFindersEvent event) {
        // Capture the packType for THIS firing locally rather than writing it onto the shared
        // loader object - AddPackFindersEvent fires once per PackType (CLIENT_RESOURCES and
        // SERVER_DATA both, on an integrated server), and every loader is added to both. A
        // mutable field on the loader would get clobbered by whichever side fired second,
        // silently handing the OTHER repository a Pack built for the wrong side - which is
        // exactly what was making models/textures fail to load even though the block/item
        // itself registered fine. See PowerPackLoaderPacksLoader#discoverExtensions.
        PackType type = event.getPackType();
        for (PowerPackLoaderPacksLoader loader : PowerPackLoaderPacksLoader.ALL_LOADERS) {
            event.addRepositorySource(onLoad -> {
                Pack pack = loader.discoverExtensions(type);
                if (pack != null) {
                    onLoad.accept(pack);
                }
            });
        }
    }
}