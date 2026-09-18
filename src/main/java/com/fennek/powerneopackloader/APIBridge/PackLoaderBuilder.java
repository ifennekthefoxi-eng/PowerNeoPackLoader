package com.fennek.powerneopackloader.APIBridge;

import com.fennek.powerneopackloader.CoreComponentes.ClassPicker;
import com.fennek.powerneopackloader.CoreComponentes.EntityPicker;
import com.fennek.powerneopackloader.CoreComponentes.ItemPicker;
import com.fennek.powerneopackloader.CoreComponentes.PackAnnotationScanner;
import com.fennek.powerneopackloader.CoreComponentes.RendererPicker;
import com.fennek.powerneopackloader.PowerNeoPackLoader;
import com.fennek.powerneopackloader.Registration.PowerNeoPackLoaderCreativeTabRegisterer;
import com.fennek.powerneopackloader.Registration.PowerNeoPackLoaderRegister;
import com.fennek.powerneopackloader.loadingPacks.GetJarResources;
import com.fennek.powerneopackloader.loadingPacks.PowerPackLoaderPacksLoader;
import net.minecraft.server.packs.PackType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Configures and runs one pack loader. Every setting has a default that works, so the short form is
 * the whole API for most mods:
 * <pre>{@code
 * @Mod(MOD_ID)
 * public class MyMod {
 *     public MyMod(IEventBus modBus, ModContainer container) {
 *         PowerPackLoaderApi.load(MOD_ID, modBus);
 *     }
 * }
 * }</pre>
 * That single line registers the loader, extracts every default pack the mod ships, discovers the
 * mod's {@code @PackBlock} classes, registers a block + item (+ block entity type, + renderer) for
 * every block json in every pack, generates the blockstate/model/lang json they need, and builds
 * their creative tabs.
 * <p>
 * The long form only exists for the parts a mod genuinely wants to differ:
 * <pre>{@code
 * PowerPackLoaderApi.loader(MOD_ID, modBus)
 *         .folder("engine_packs")
 *         .meta("engine.meta.json")
 *         .defaultPacks("cacw_default_engines")
 *         .creativeTabs(CreativeTabMode.PER_PACK)
 *         .load();
 * }</pre>
 * Call it once per loader; a mod wanting separate folders for, say, engines and body panels calls
 * it twice with different {@link #folder(String)} values. Everything must happen inside the mod
 * constructor - registration is queued onto the mod event bus, which has already fired by the time
 * anything else runs.
 */
public class PackLoaderBuilder {

    private final String modId;
    private final IEventBus modBus;

    private String folder = "packs";
    private String metaFileName = "pack.meta.json";
    private Class<?> resourceOwner;
    private List<String> defaultPackNames;
    private CreativeTabMode creativeTabMode = CreativeTabMode.BOTH;
    private boolean autoDiscover = true;

    private ClassPicker classPicker;
    private EntityPicker entityPicker;
    private ItemPicker itemPicker;

    /**
     * @param modId  the mod these blocks register under. Their ids become
     *               {@code <modId>:<packId>_<blockId>}, and this is also the mod whose
     *               {@code @PackBlock} classes get discovered.
     * @param modBus the event bus handed to your mod's constructor.
     * @param caller the class that called into the API - used to read default packs out of the
     *               right jar, and overridable with {@link #resourcesFrom(Class)}.
     */
    public PackLoaderBuilder(String modId, IEventBus modBus, Class<?> caller) {
        this.modId = Objects.requireNonNull(modId, "modId");
        this.modBus = Objects.requireNonNull(modBus, "modBus");
        this.resourceOwner = caller;
    }

    /**
     * The folder this loader's packs live in, under {@code <game dir>/powerpackloader/<modId>/}.
     * Defaults to {@code "packs"}. Players drop pack folders (or zips) in here.
     */
    public PackLoaderBuilder folder(String folder) {
        this.folder = Objects.requireNonNull(folder, "folder");
        return this;
    }

    /**
     * The per-pack descriptor filename this loader looks for; a folder without one is not a pack
     * and is skipped. Defaults to {@code "pack.meta.json"}, whose contents are
     * {@code {"name": "...", "id": "..."}} - the {@code id} being the pack's asset namespace and
     * the prefix of every block id it contributes.
     * <p>
     * Worth changing when a mod runs several loaders, so a pack can't be picked up by the wrong
     * one (e.g. {@code "engine.meta.json"} vs {@code "body.meta.json"}).
     */
    public PackLoaderBuilder meta(String metaFileName) {
        this.metaFileName = Objects.requireNonNull(metaFileName, "metaFileName");
        return this;
    }

    /**
     * Which folders under {@code assets/<modId>/default_packs/} to extract into the loader folder
     * on first run. By default every folder found there is extracted, which is usually what you
     * want - name them explicitly only to ship packs that one loader should take and another
     * shouldn't.
     * <p>
     * Extraction is content-addressed: an unchanged pack is left alone, and a pack the mod has
     * updated is re-extracted with the player's previous copy backed up first, so editing a default
     * pack in place is safe but not permanent. Treat these as "the mod's packs"; a player's own
     * packs belong in their own folders.
     */
    public PackLoaderBuilder defaultPacks(String... names) {
        this.defaultPackNames = List.of(names);
        return this;
    }

    /** Ships no default packs at all - the loader folder starts empty and only ever holds what
     *  players put there. */
    public PackLoaderBuilder noDefaultPacks() {
        this.defaultPackNames = List.of();
        return this;
    }

    /**
     * The class whose jar the default packs are read from. Defaults to whichever class called
     * {@code PowerPackLoaderApi.load(...)}/{@code .loader(...)}, which is right whenever that's
     * your mod's own class - set this when the call is made from a helper living in a different
     * jar.
     */
    public PackLoaderBuilder resourcesFrom(Class<?> resourceOwner) {
        this.resourceOwner = Objects.requireNonNull(resourceOwner, "resourceOwner");
        return this;
    }

    /** Which creative tabs to build for this loader's blocks. Defaults to
     *  {@link CreativeTabMode#BOTH}. */
    public PackLoaderBuilder creativeTabs(CreativeTabMode mode) {
        this.creativeTabMode = Objects.requireNonNull(mode, "mode");
        return this;
    }

    /**
     * Turns off the {@code @PackBlock} annotation scan, leaving the pickers exactly as supplied.
     * Only useful together with {@link #pickers}, for a mod that builds its class registries some
     * other way (from a config, at runtime, from another mod's classes).
     */
    public PackLoaderBuilder autoDiscover(boolean autoDiscover) {
        this.autoDiscover = autoDiscover;
        return this;
    }

    /**
     * Supplies the class registries instead of letting this builder create fresh ones.
     * <p>
     * Needed when several loaders must share one set of registries (so a class annotated once is
     * usable from every loader's packs), or to seed mappings by hand alongside the annotation scan
     * - the scan adds to whatever is already there, and never overwrites an existing mapping.
     */
    public PackLoaderBuilder pickers(ClassPicker classPicker, EntityPicker entityPicker, ItemPicker itemPicker) {
        this.classPicker = classPicker;
        this.entityPicker = entityPicker;
        this.itemPicker = itemPicker;
        return this;
    }

    /**
     * Runs everything, in the only order that works: discover classes, register the loader and
     * extract its default packs (the blocks can't be found before the packs are on disk), register
     * the blocks, then build the creative tabs (which read the items the block step just produced).
     *
     * @return the live loader, for {@code ResourceLocator} access to the pack files or for
     *         inspecting what was discovered.
     */
    public PowerPackLoaderPacksLoader load() {
        if (classPicker == null) classPicker = new ClassPicker();
        if (entityPicker == null) entityPicker = new EntityPicker();
        if (itemPicker == null) itemPicker = new ItemPicker();

        if (autoDiscover) {
            PackAnnotationScanner.scan(modId, classPicker, entityPicker, itemPicker, RendererPicker.GLOBAL);
        }

        PackType packType = FMLLoader.getDist().isClient() ? PackType.CLIENT_RESOURCES : PackType.SERVER_DATA;
        PowerPackLoaderPacksLoader loader = new PowerPackLoaderPacksLoader(packType, folder, modId, metaFileName);

        for (String packName : resolveDefaultPackNames()) {
            loader.addDefaultResource(resourceOwner, String.format("/assets/%s/default_packs/%s", modId, packName), packName);
        }
        // Extract now, during mod construction, rather than leaving it to the pack repository
        // scan: the block registration below reads these folders off disk immediately.
        loader.setupAndExtractDefaults();

        PowerNeoPackLoaderRegister.RegisterBlocksFromPackLoader(loader, classPicker, entityPicker, itemPicker, modId, modBus);

        new PowerNeoPackLoaderCreativeTabRegisterer().RegisterCreativeTabs(loader, modId, modBus, creativeTabMode);

        PowerNeoPackLoader.LOGGER.info("Pack loader '{}' ready for mod '{}' ({} pack folder(s) on disk).",
                folder, modId, loader.getRootFolder());
        return loader;
    }

    /** The explicitly named default packs, or - when none were named - every folder the mod ships
     *  under {@code assets/<modId>/default_packs/}. */
    private List<String> resolveDefaultPackNames() {
        if (defaultPackNames != null) {
            return defaultPackNames;
        }
        if (resourceOwner == null) {
            PowerNeoPackLoader.LOGGER.warn(
                    "No resource owner class for '{}' - skipping default pack extraction. "
                            + "Call resourcesFrom(YourMod.class) if this mod ships default packs.", modId);
            return List.of();
        }
        List<String> discovered = new ArrayList<>(
                GetJarResources.listChildDirectories(resourceOwner, "/assets/" + modId + "/default_packs"));
        if (!discovered.isEmpty()) {
            PowerNeoPackLoader.LOGGER.info("Found {} default pack(s) shipped by '{}': {}", discovered.size(), modId, discovered);
        }
        return discovered;
    }
}
