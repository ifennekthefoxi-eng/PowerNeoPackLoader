package com.fennek.powerneopackloader.Registration;

import com.fennek.powerneopackloader.PowerNeoPackLoader;
import com.fennek.powerneopackloader.loadingPacks.PowerPackLoaderPacksLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;
import java.util.Map;

public class PowerNeoPackLoaderCreativeTabRegisterer {

    /**
     * Creates one creative tab PER pack id that {@code loader} has registered blocks/items for,
     * each tab containing only that pack's own content - e.g. a pack with id "artics_blocks"
     * gets its own tab, a pack with id "clays_blocks" gets a separate one, and neither shows the
     * other's blocks.
     * <p>
     * Uses the packId -> items map that {@code PowerNeoPackLoaderRegister} builds while it
     * registers each pack's blocks, so this must be called after
     * {@code PowerNeoPackLoaderRegister.RegisterBlocksFromPackLoader} has run for {@code loader}.
     */
    public void RegisterCreativeTabsForEachPackId(PowerPackLoaderPacksLoader loader, String modId, IEventBus modBus) {
        DeferredRegister<CreativeModeTab> creativeModeTabs = DeferredRegister.create(BuiltInRegistries.CREATIVE_MODE_TAB, modId);

        for (Map.Entry<String, List<DeferredItem<? extends Item>>> entry : loader.getPackIdToItems().entrySet()) {
            String packId = entry.getKey();
            List<DeferredItem<? extends Item>> packItems = entry.getValue();

            creativeModeTabs.register(packId + "_tab", () -> CreativeModeTab.builder()
                    // TODO: swap for real per-pack tab configuration (icon, title, ordering...) later.
                    .icon(() -> new ItemStack(Items.OAK_LOG))
                    // Loaded from assets/<packId>/lang/<locale>.json, exactly like vanilla
                    // (en_us.json, es_es.json, ...) - see PowerPackLoaderLangGenerator for the
                    // fallback used when a pack doesn't ship its own translations.
                    .title(Component.translatable("itemGroup." + packId))
                    .displayItems((parameters, output) -> {
                        for (DeferredItem<? extends Item> item : packItems) {
                            output.accept(item.get());
                        }
                    })
                    .build());

            PowerNeoPackLoader.LOGGER.info("Registered creative tab '{}' with {} item(s) for pack id '{}'",
                    packId + "_tab", packItems.size(), packId);
        }

        creativeModeTabs.register(modBus);
    }

    /**
     * Creates a SINGLE creative tab containing every block/item that {@code loader} has
     * registered, regardless of which pack id each one came from.
     * <p>
     * Like the per-pack variant above, this reads from the packId -> items map
     * {@code PowerNeoPackLoaderRegister} builds, so it must be called after
     * {@code PowerNeoPackLoaderRegister.RegisterBlocksFromPackLoader} has run for {@code loader}.
     */
    public void RegisterCreativeTabForAllBlocksInLoader(PowerPackLoaderPacksLoader loader, String modId, IEventBus modBus) {
        DeferredRegister<CreativeModeTab> creativeModeTabs = DeferredRegister.create(BuiltInRegistries.CREATIVE_MODE_TAB, modId);

        String tabName = loader.getDirectoryName() + "_all_tab";

        creativeModeTabs.register(tabName, () -> CreativeModeTab.builder()
                // TODO: swap for real tab configuration (icon, title, ordering...) later.
                .icon(() -> new ItemStack(Items.OAK_LOG))
                .title(Component.literal(loader.getDirectoryName().replace("_", " ") + " All blocks"))
                .displayItems((parameters, output) -> {
                    for (List<DeferredItem<? extends Item>> packItems : loader.getPackIdToItems().values()) {
                        for (DeferredItem<? extends Item> item : packItems) {
                            output.accept(item.get());
                        }
                    }
                })
                .build());

        PowerNeoPackLoader.LOGGER.info("Registered combined creative tab '{}' for loader '{}'", tabName, loader.getDirectoryName());

        creativeModeTabs.register(modBus);
    }
}
