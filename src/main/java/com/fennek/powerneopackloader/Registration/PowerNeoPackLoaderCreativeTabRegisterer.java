package com.fennek.powerneopackloader.Registration;

import com.fennek.powerneopackloader.APIBridge.CreativeTabMode;
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
     * Creates whichever tabs {@code mode} asks for, in a single {@link DeferredRegister} pass.
     * <p>
     * Must run after {@code PowerNeoPackLoaderRegister.RegisterBlocksFromPackLoader} for the same
     * loader: the tabs are built from the packId -&gt; items map that step populates, so calling it
     * first produces silently empty tabs rather than an error.
     */
    public void RegisterCreativeTabs(PowerPackLoaderPacksLoader loader, String modId, IEventBus modBus, CreativeTabMode mode) {
        if (mode == CreativeTabMode.NONE) {
            return;
        }

        DeferredRegister<CreativeModeTab> creativeModeTabs = DeferredRegister.create(BuiltInRegistries.CREATIVE_MODE_TAB, modId);

        if (mode.perPack()) {
            registerPerPackTabs(loader, creativeModeTabs);
        }
        if (mode.combined()) {
            registerCombinedTab(loader, creativeModeTabs);
        }

        creativeModeTabs.register(modBus);
    }

    /**
     * One creative tab per pack id, each containing only that pack's own content - a pack with id
     * "artics_blocks" gets its own tab, "clays_blocks" gets a separate one, and neither shows the
     * other's blocks.
     */
    private void registerPerPackTabs(PowerPackLoaderPacksLoader loader, DeferredRegister<CreativeModeTab> creativeModeTabs) {
        for (Map.Entry<String, List<DeferredItem<? extends Item>>> entry : loader.getPackIdToItems().entrySet()) {
            String packId = entry.getKey();
            List<DeferredItem<? extends Item>> packItems = entry.getValue();

            creativeModeTabs.register(packId + "_tab", () -> CreativeModeTab.builder()
                    .icon(() -> iconFor(packItems))
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
    }

    /** A single tab holding every block this loader registered, whichever pack it came from. */
    private void registerCombinedTab(PowerPackLoaderPacksLoader loader, DeferredRegister<CreativeModeTab> creativeModeTabs) {
        String tabName = loader.getDirectoryName() + "_all_tab";

        creativeModeTabs.register(tabName, () -> CreativeModeTab.builder()
                .icon(() -> loader.getPackIdToItems().values().stream()
                        .findFirst().map(PowerNeoPackLoaderCreativeTabRegisterer::iconFor)
                        .orElseGet(() -> new ItemStack(Items.OAK_LOG)))
                .title(Component.literal(humanize(loader.getDirectoryName())))
                .displayItems((parameters, output) -> {
                    for (List<DeferredItem<? extends Item>> packItems : loader.getPackIdToItems().values()) {
                        for (DeferredItem<? extends Item> item : packItems) {
                            output.accept(item.get());
                        }
                    }
                })
                .build());

        PowerNeoPackLoader.LOGGER.info("Registered combined creative tab '{}' for loader '{}'", tabName, loader.getDirectoryName());
    }

    /**
     * A pack's first registered block as its tab icon, falling back to an oak log for an empty
     * pack. Using real pack content means a modder gets a recognisable tab without configuring
     * anything, and the fallback only ever shows for a tab that has nothing in it anyway.
     */
    private static ItemStack iconFor(List<DeferredItem<? extends Item>> packItems) {
        if (packItems.isEmpty()) {
            return new ItemStack(Items.OAK_LOG);
        }
        return new ItemStack(packItems.get(0).get());
    }

    /** "engine_packs" -&gt; "Engine Packs" */
    private static String humanize(String value) {
        String[] words = value.replace('-', '_').split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (result.length() > 0) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) result.append(word.substring(1));
        }
        return result.length() == 0 ? value : result.toString();
    }

    /**
     * @deprecated use {@link #RegisterCreativeTabs} with {@link CreativeTabMode#PER_PACK}. Kept so
     *             existing callers keep working; note that calling this AND
     *             {@link #RegisterCreativeTabForAllBlocksInLoader} separately creates two
     *             DeferredRegisters where one now suffices.
     */
    @Deprecated
    public void RegisterCreativeTabsForEachPackId(PowerPackLoaderPacksLoader loader, String modId, IEventBus modBus) {
        RegisterCreativeTabs(loader, modId, modBus, CreativeTabMode.PER_PACK);
    }

    /**
     * @deprecated use {@link #RegisterCreativeTabs} with {@link CreativeTabMode#COMBINED}.
     */
    @Deprecated
    public void RegisterCreativeTabForAllBlocksInLoader(PowerPackLoaderPacksLoader loader, String modId, IEventBus modBus) {
        RegisterCreativeTabs(loader, modId, modBus, CreativeTabMode.COMBINED);
    }
}
