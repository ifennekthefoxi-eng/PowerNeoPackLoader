package com.fennek.powerneopackloader.APIBridge;

import com.fennek.powerneopackloader.CoreComponentes.ClassPicker;
import com.fennek.powerneopackloader.CoreComponentes.EntityPicker;
import com.fennek.powerneopackloader.CoreComponentes.ItemPicker;
import com.fennek.powerneopackloader.CoreComponentes.RendererPicker;
import com.fennek.powerneopackloader.Registration.PowerNeoPackLoaderCreativeTabRegisterer;
import com.fennek.powerneopackloader.Registration.PowerNeoPackLoaderRegister;
import com.fennek.powerneopackloader.loadingPacks.PowerPackLoaderPacksLoader;
import net.minecraft.server.packs.PackType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLLoader;

/**
 * The entry point a mod using this library touches. In the common case it is the only class from
 * this library a mod ever imports, and one line of it:
 * <pre>{@code
 * @Mod(MOD_ID)
 * public class MyMod {
 *     public MyMod(IEventBus modBus, ModContainer container) {
 *         PowerPackLoaderApi.load(MOD_ID, modBus);
 *     }
 * }
 * }</pre>
 *
 * <h2>What a mod has to provide</h2>
 * <ol>
 *     <li>Block classes, annotated with {@code @PackBlock} - see that annotation, and
 *     {@code @PackBlockEntity}/{@code @PackBlockItem}/{@code @PackRenderer} for the rest of a block
 *     family. Nothing has to be listed anywhere; they are discovered from the annotations.</li>
 *     <li>Optionally, default packs under {@code assets/<modId>/default_packs/<packName>/}, which
 *     are extracted into the player-editable loader folder on first run. Every folder found there
 *     is shipped automatically.</li>
 * </ol>
 * That's the whole contract. Registration of blocks, items, block entity types, creative tabs, and
 * generation of the blockstate/model/lang json they need is what this library is for.
 *
 * <h2>Where packs live</h2>
 * {@code <game dir>/powerpackloader/<modId>/<folder>/} - one subfolder (or zip) per pack, each with
 * a meta json giving the pack its {@code id}, and block definitions under
 * {@code data/<packId>/blocks/<blockId>.json}. Blocks register as
 * {@code <modId>:<packId>_<blockId>}.
 *
 * @see PackLoaderBuilder for the settings a mod can change
 * @see com.fennek.powerneopackloader.Registration.PowerPackLoaderRegistry for getting at the
 *      registered blocks afterwards
 * @see com.fennek.powerneopackloader.CoreComponentes.ResourceLocator for reading a pack's own files
 */
public class PowerPackLoaderApi {

    /**
     * Sets up one pack loader with every default: packs in
     * {@code powerpackloader/<modId>/packs/}, described by {@code pack.meta.json}, all shipped
     * default packs extracted, {@code @PackBlock} classes discovered automatically, and both
     * per-pack and combined creative tabs.
     * <p>
     * Call it from your mod's constructor - registration is queued onto the mod event bus, which
     * has already fired by the time anything later runs.
     */
    public static PowerPackLoaderPacksLoader load(String modId, IEventBus modBus) {
        return new PackLoaderBuilder(modId, modBus, callerClass()).load();
    }

    /**
     * The same, configurable - see {@link PackLoaderBuilder}. Finish with
     * {@link PackLoaderBuilder#load()}:
     * <pre>{@code
     * PowerPackLoaderApi.loader(MOD_ID, modBus)
     *         .folder("engine_packs")
     *         .meta("engine.meta.json")
     *         .load();
     * }</pre>
     */
    public static PackLoaderBuilder loader(String modId, IEventBus modBus) {
        return new PackLoaderBuilder(modId, modBus, callerClass());
    }

    /**
     * The class that called into this API, used to read default packs out of the right jar.
     * <p>
     * Taken from the call stack rather than asked for as a parameter, because it is always "the
     * class you are writing this in" and getting it wrong is silent (the packs simply don't
     * extract). {@link PackLoaderBuilder#resourcesFrom(Class)} overrides it for the rare call made
     * from a different jar than the mod's own.
     */
    private static Class<?> callerClass() {
        return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).getCallerClass();
    }

    // =========================================================================
    // Lower-level pieces. Everything above is built from these; they remain public for mods that
    // need to drive one step differently, and so that setups written before the builder existed
    // keep working unchanged.
    // =========================================================================

    /**
     * Registers and creates a new custom folder loader.
     * Generates folders under: {@code /powerpackloader/(modId)/(loaderFolder)/}
     */
    public static PowerPackLoaderPacksLoader registerLoader(String modId, String loaderFolder, String metaFileName) {
        PackType packType = FMLLoader.getDist().isClient() ? PackType.CLIENT_RESOURCES : PackType.SERVER_DATA;
        return new PowerPackLoaderPacksLoader(packType, loaderFolder, modId, metaFileName);
    }

    /**
     * Copies a default pack directory from {@code assets/(modId)/default_packs/(folderName)}
     * into the loader's destination directory if it doesn't already exist.
     *
     * @param loader                the target loader
     * @param modMainClass          main mod class, for JAR resource stream access
     * @param modId                 your mod ID
     * @param defaultPackFolderName folder name inside {@code assets/(modId)/default_packs/}
     */
    public static void copyDefaultPack(PowerPackLoaderPacksLoader loader, Class<?> modMainClass, String modId, String defaultPackFolderName) {
        String jarSourcePath = String.format("/assets/%s/default_packs/%s", modId, defaultPackFolderName);
        loader.addDefaultResource(modMainClass, jarSourcePath, defaultPackFolderName);

        // Force the extraction immediately during mod init, before world/game loads
        loader.setupAndExtractDefaults();
    }

    /**
     * {@link #registerLoader} + {@link #copyDefaultPack} in one call - the two always run back
     * to back for any given pack loader.
     */
    public static PowerPackLoaderPacksLoader registerAndLoad(String modId, String loaderFolder, String metaFileName,
                                                            Class<?> modMainClass, String defaultPackFolderName) {
        PowerPackLoaderPacksLoader loader = registerLoader(modId, loaderFolder, metaFileName);
        copyDefaultPack(loader, modMainClass, modId, defaultPackFolderName);
        return loader;
    }

    /**
     * The explicit, fully-specified form of {@link #load}: registers the loader, extracts one named
     * default pack, registers every block/item/block entity its packs define, and builds its
     * creative tabs - with class registries the caller supplies and fills itself.
     * <p>
     * Prefer {@link #load}/{@link #loader}, which discover block classes from their annotations
     * instead of requiring the pickers to be populated by hand.
     */
    public static PowerPackLoaderPacksLoader setupPackLoader(
            String modId, IEventBus modBus, String loaderFolder, String metaFileName,
            Class<?> modMainClass, String defaultPackFolderName,
            ClassPicker classPicker, EntityPicker entityPicker, ItemPicker itemPicker) {

        PowerPackLoaderPacksLoader loader = registerAndLoad(modId, loaderFolder, metaFileName, modMainClass, defaultPackFolderName);

        PowerNeoPackLoaderRegister.RegisterBlocksFromPackLoader(loader, classPicker, entityPicker, itemPicker, modId, modBus);

        new PowerNeoPackLoaderCreativeTabRegisterer().RegisterCreativeTabs(loader, modId, modBus, CreativeTabMode.BOTH);

        return loader;
    }

    /**
     * No longer needed: this library registers its own block-type codecs on its own mod bus, in its
     * own constructor. It has to - the codec registry entry is a single global object, so a mod
     * calling this would have registered it a second time and crashed the game with a duplicate
     * registration as soon as two mods used the library together.
     *
     * @deprecated remove the call; it does nothing.
     */
    @Deprecated
    public static void registerCoreCodecs(IEventBus modBus) {
    }

    /** A fresh, empty {@link ClassPicker}/{@link EntityPicker}/{@link ItemPicker} trio, for the
     *  manual {@link #setupPackLoader} path. {@link #load}/{@link #loader} create and fill their
     *  own, so nothing needs this. */
    public static Selectors createSelectors() {
        return new Selectors(new ClassPicker(), new EntityPicker(), new ItemPicker());
    }

    /**
     * The shared picker holding every pack block entity's renderer, for a renderer that can't be
     * declared with {@code @PackRenderer} - see {@link RendererPicker#GLOBAL}.
     */
    public static RendererPicker renderers() {
        return RendererPicker.GLOBAL;
    }

    public record Selectors(ClassPicker classPicker, EntityPicker entityPicker, ItemPicker itemPicker) {
    }
}
