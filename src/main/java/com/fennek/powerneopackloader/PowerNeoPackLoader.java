package com.fennek.powerneopackloader;

import com.fennek.powerneopackloader.APIBridge.PowerPackLoaderApi;
import com.fennek.powerneopackloader.CoreComponentes.LoadableModelHorizontalDirectionalBlock;
import com.fennek.powerneopackloader.CoreComponentes.ResourceLocator;
import com.fennek.powerneopackloader.loadingPacks.PowerPackLoaderPacksLoader;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * This library's own mod entry point - and, deliberately, an example of a mod using it: everything
 * below the codec registration is exactly what a consuming mod writes, with nothing extra available
 * to it for being the library itself.
 * <p>
 * The example pack it loads lives in {@code assets/powerneopackloader/default_packs/example_pack}
 * and is extracted to {@code powerpackloader/powerneopackloader/example_pack_loader/} on first run;
 * its block json names {@code ExampleGeckoLibBlock}, which is found by that class's
 * {@code @PackBlock} annotation rather than being registered here.
 */
@Mod(PowerNeoPackLoader.MOD_ID)
public class PowerNeoPackLoader {

    public static final String MOD_ID = "powerneopackloader";
    public static final Logger LOGGER = LogManager.getLogger();
    public static ModContainer container;

    public static PowerPackLoaderPacksLoader examplePackLoader;
    public static ResourceLocator examplePackResourceLocator;

    public PowerNeoPackLoader(IEventBus modBus, ModContainer container) {
        PowerNeoPackLoader.container = container;

        // The library's own block-type codecs, on the library's own bus. This is the one thing a
        // consuming mod must NOT do: the codec is a single global registry entry, so a second mod
        // registering it would crash the game with a duplicate registration the moment two mods
        // used this library together.
        LoadableModelHorizontalDirectionalBlock.CODECS.register(modBus);

        // --- from here down, this is ordinary consuming-mod code ---

        // One call: registers the loader, extracts the default pack, discovers this mod's
        // @PackBlock classes, registers a block/item/block entity + renderer for every block json
        // in every pack it finds, generates the blockstate/model/lang json they need, and builds
        // their creative tabs. The defaults would do for most mods; the folder and meta names are
        // spelled out here only because this example predates them.
        examplePackLoader = PowerPackLoaderApi.loader(MOD_ID, modBus)
                .folder("example_pack_loader")
                .meta("example.meta.json")
                .load();

        examplePackResourceLocator = new ResourceLocator(examplePackLoader);

        LOGGER.info("Successfully initialized {} pack loader(s) for {}", PowerPackLoaderPacksLoader.ALL_LOADERS.size(), MOD_ID);
    }
}
