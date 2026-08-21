package com.fennek.powerneopackloader.loadingPacks;

import com.fennek.powerneopackloader.PowerNeoPackLoader;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.event.AddPackFindersEvent;

@EventBusSubscriber(modid = PowerNeoPackLoader.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public class PowerPackLoaderModBusEvents {

    @SubscribeEvent
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        // FourLineEngineBlockEntity.registerCapabilities(event);
    }

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