package com.fennek.powerneopackloader.Registration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.fennek.powerneopackloader.APIBridge.PackBlockContext;
import com.fennek.powerneopackloader.CoreComponentes.ClassPicker;
import com.fennek.powerneopackloader.CoreComponentes.EntityPicker;
import com.fennek.powerneopackloader.CoreComponentes.GeckoLibCompat;
import com.fennek.powerneopackloader.CoreComponentes.ItemPicker;
import com.fennek.powerneopackloader.CoreComponentes.PackAnnotationScanner;
import com.fennek.powerneopackloader.CoreComponentes.PackBlockProperties;
import com.fennek.powerneopackloader.CoreComponentes.PackRegistryNames;
import com.fennek.powerneopackloader.PowerNeoPackLoader;
import com.fennek.powerneopackloader.loadingPacks.PowerPackLoaderPacksLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import software.bernie.geckolib.animatable.GeoItem;

import java.io.BufferedReader;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Walks a loader's pack folders and turns every block json it finds into a real registered block,
 * item and (when one is mapped) block entity type.
 * <p>
 * Everything mod-specific arrives as a parameter - the mod id to register under, the event bus to
 * register on, and the three pickers saying which Java class each pack json's {@code "class"} field
 * means - so nothing here is tied to any one mod. The pickers are normally filled automatically
 * from {@code @PackBlock} and friends; see {@code PackAnnotationScanner}.
 */
public class PowerNeoPackLoaderRegister {

    /**
     * Every dynamically-created {@link BlockEntityType}'s registry id, mapped to whatever entity
     * class {@link EntityPicker} said it should be paired with (e.g. {@code ChestBlockEntity},
     * a mod's own {@code EngineBlockEntity}).
     * <p>
     * This exists purely so client-side code (see {@code PowerPackLoaderClientEvents}) can decide,
     * at {@code EntityRenderersEvent.RegisterRenderers} time, which {@code BlockEntityRenderer} (if
     * any) belongs on each pack-generated type. Registering a {@code BlockEntityType} does NOT
     * register a renderer for it - those are two entirely separate registries in vanilla, and
     * nothing about the block/entity registration path below touches rendering at all. Populated
     * synchronously while blocks are being registered (well before RegisterRenderers fires), so a
     * plain static map is enough - no DeferredRegister needed here, this isn't a registry object
     * itself.
     * <p>
     * Static and shared across mods deliberately: the renderer registration that consumes it runs
     * once, in this library, for every pack block in the game. Keys are full ResourceLocations, so
     * two mods can never collide.
     * <p>
     * Concurrent because FML constructs mods in PARALLEL, on several {@code modloading-worker}
     * threads - so with two or more mods using this library, two constructors populate this map at
     * the same time. A plain {@code HashMap} can lose entries or corrupt its internal table under
     * concurrent writes; the symptom would be a pack block that registers correctly but
     * intermittently renders as nothing, on some launches and not others. Everything else this
     * library shares between mods is guarded for the same reason.
     */
    public static final Map<ResourceLocation, Class<?>> REGISTERED_ENTITY_CLASSES = new ConcurrentHashMap<>();

    /**
     * Instantiates the mapped block entity class for a pack block, preferring a
     * {@code (BlockEntityType<?>, BlockPos, BlockState)} constructor over a plain
     * {@code (BlockPos, BlockState)} one whenever the class has both.
     * <p>
     * This matters for entity classes borrowed from vanilla or other mods (like
     * {@link net.minecraft.world.level.block.entity.ChestBlockEntity}) whose public 2-arg
     * constructor hardcodes a SPECIFIC shared {@code BlockEntityType} (e.g.
     * {@code BlockEntityType.CHEST}) rather than accepting the one this loader just registered
     * for this specific pack block. Using that 2-arg constructor silently gives the entity the
     * wrong type - one whose valid-blocks list doesn't include this pack's block - and the game
     * crashes the instant the block is placed ("Invalid block entity ... got Block{...}"), because
     * {@link net.minecraft.world.level.block.entity.BlockEntity}'s own constructor validates the
     * block against the type it was actually given. The 3-arg constructor exists on exactly these
     * classes so a subclass/reuse site CAN hand in its own type; it's usually {@code protected}
     * since callers aren't expected to reach for it directly, hence {@code getDeclaredConstructor}
     * + {@code setAccessible} rather than {@code getConstructor}.
     */
    private static BlockEntity instantiateBlockEntity(Class<?> targetEntityClass, BlockEntityType<?> selfType, BlockPos pos, BlockState state) {
        Constructor<?> selfAwareCtor = findConstructor(targetEntityClass, BlockEntityType.class, BlockPos.class, BlockState.class);
        try {
            if (selfAwareCtor != null) {
                return (BlockEntity) selfAwareCtor.newInstance(selfType, pos, state);
            }
            Constructor<?> plainCtor = findConstructor(targetEntityClass, BlockPos.class, BlockState.class);
            if (plainCtor == null) {
                throw new NoSuchMethodException("no (BlockEntityType, BlockPos, BlockState) or (BlockPos, BlockState) constructor");
            }
            return (BlockEntity) plainCtor.newInstance(pos, state);
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate block entity class: " + targetEntityClass.getName(), e);
        }
    }

    /**
     * A declared constructor with exactly {@code parameterTypes}, made accessible, or null if the
     * class has no such constructor.
     * <p>
     * {@code getDeclaredConstructor} rather than {@code getConstructor} throughout, so a pack class
     * doesn't have to make its constructor public just to be loadable - and returning null rather
     * than throwing, because "this class uses a different one of the supported signatures" is the
     * normal case here, not an error.
     */
    private static Constructor<?> findConstructor(Class<?> type, Class<?>... parameterTypes) {
        try {
            Constructor<?> ctor = type.getDeclaredConstructor(parameterTypes);
            ctor.setAccessible(true);
            return ctor;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /**
     * Builds the block, trying each supported constructor shape in turn (see {@code @PackBlock}):
     * {@code (Properties, PackBlockContext)}, then {@code (Properties)}, then {@code ()}.
     * <p>
     * The context is published to {@link PackBlockContext#current()} around the whole call
     * regardless of which shape matched, so even a block using the plain vanilla
     * {@code (Properties)} constructor can still read its own pack json - including from inside a
     * {@code super(...)} argument, where no constructor parameter could be reached anyway.
     */
    private static Block instantiateBlock(Class<? extends Block> blockClass, BlockBehaviour.Properties properties,
                                          PackBlockContext context) {
        PackBlockContext.setCurrent(context);
        try {
            Constructor<?> withContext = findConstructor(blockClass, BlockBehaviour.Properties.class, PackBlockContext.class);
            if (withContext != null) {
                return (Block) withContext.newInstance(properties, context);
            }
            Constructor<?> withProperties = findConstructor(blockClass, BlockBehaviour.Properties.class);
            if (withProperties != null) {
                return (Block) withProperties.newInstance(properties);
            }
            Constructor<?> noArgs = findConstructor(blockClass);
            if (noArgs != null) {
                return (Block) noArgs.newInstance();
            }
            throw new NoSuchMethodException("no (Properties, PackBlockContext), (Properties) or () constructor");
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate block class: " + blockClass.getName(), e);
        } finally {
            PackBlockContext.clearCurrent();
        }
    }

    /**
     * Builds the pack block's item: the {@link ItemPicker}-mapped class if there is one - trying
     * {@code (Block, Item.Properties, PackBlockContext)} then {@code (Block, Item.Properties)} -
     * and a plain vanilla {@link BlockItem} otherwise, which is correct for the great majority of
     * pack blocks.
     */
    private static BlockItem instantiateItem(Class<?> itemClass, Block block, PackBlockContext context) {
        if (itemClass == null) {
            return new BlockItem(block, new Item.Properties());
        }

        PackBlockContext.setCurrent(context);
        try {
            Constructor<?> withContext = findConstructor(itemClass, Block.class, Item.Properties.class, PackBlockContext.class);
            if (withContext != null) {
                return (BlockItem) withContext.newInstance(block, new Item.Properties(), context);
            }
            Constructor<?> plain = findConstructor(itemClass, Block.class, Item.Properties.class);
            if (plain != null) {
                return (BlockItem) plain.newInstance(block, new Item.Properties());
            }
            throw new NoSuchMethodException("no (Block, Item.Properties, PackBlockContext) or (Block, Item.Properties) constructor");
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate item class: " + itemClass.getName(), e);
        } finally {
            PackBlockContext.clearCurrent();
        }
    }

    public static void RegisterBlocksFromPackLoader(PowerPackLoaderPacksLoader loader, ClassPicker classPicker, EntityPicker entityPicker, ItemPicker itemPicker, String Mod_ID, IEventBus modBus) {
        Path rootFolder = loader.getRootFolder();

        if (rootFolder == null || !Files.exists(rootFolder)) {
            return;
        }

        DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Mod_ID);
        DeferredRegister.Items ITEMS = DeferredRegister.createItems(Mod_ID);
        DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Mod_ID);

        PowerPackLoaderModelGenerator.clear(loader);
        loader.clearPackItems();

        try (Stream<Path> stream = Files.list(rootFolder)) {
            stream.filter(Files::isDirectory)
                    .forEach(packFolder -> {
                        Path metaFile = packFolder.resolve(loader.getMetaFile());

                        if (Files.exists(metaFile) && Files.isRegularFile(metaFile)) {

                            try (BufferedReader metaReader = Files.newBufferedReader(metaFile)) {
                                JsonObject metaJson = JsonParser.parseReader(metaReader).getAsJsonObject();

                                if (metaJson.has("id")) {
                                    String packId = metaJson.get("id").getAsString();
                                    // A pack declaring itself part of the mod's own content rather
                                    // than an addition beside it - its blocks drop the pack prefix.
                                    // See PackRegistryNames.
                                    boolean extendOriginal = metaJson.has("extend_original")
                                            && metaJson.get("extend_original").getAsBoolean();

                                    PowerPackLoaderLangGenerator.generate(loader, packFolder, packId);
                                    PowerPackLoaderRegistry.clearPack(Mod_ID, packId);

                                    Path blocksFolder = packFolder.resolve("data").resolve(packId).resolve("blocks");

                                    if (Files.exists(blocksFolder) && Files.isDirectory(blocksFolder)) {

                                        try (Stream<Path> blockFilesStream = Files.list(blocksFolder)) {
                                            blockFilesStream
                                                    .filter(Files::isRegularFile)
                                                    .filter(path -> path.toString().endsWith(".json"))
                                                    .forEach(blockFile -> registerBlockFile(
                                                            loader, classPicker, entityPicker, itemPicker, Mod_ID,
                                                            BLOCKS, ITEMS, BLOCK_ENTITIES, packFolder, packId,
                                                            extendOriginal, blockFile));
                                        } catch (Exception e) {
                                            PowerNeoPackLoader.LOGGER.error("Failed to read blocks folder: " + blocksFolder, e);
                                        }

                                    } else {
                                        PowerNeoPackLoader.LOGGER.warn("No valid blocks folder found at: " + blocksFolder);
                                    }

                                } else {
                                    PowerNeoPackLoader.LOGGER.warn("Skipping folder: " + packFolder.getFileName()
                                            + " (" + loader.getMetaFile() + " is missing the 'id' field)");
                                }

                            } catch (Exception e) {
                                PowerNeoPackLoader.LOGGER.error("Failed to parse " + loader.getMetaFile()
                                        + " in folder: " + packFolder.getFileName(), e);
                            }

                        } else {
                            PowerNeoPackLoader.LOGGER.warn("Skipping folder (no " + loader.getMetaFile() + " found): "
                                    + packFolder.getFileName());
                        }
                    });
        } catch (Exception e) {
            PowerNeoPackLoader.LOGGER.error("Error scanning for functional blocks to register", e);
        }

        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
    }

    /**
     * One block json -> one registered block + item (+ block entity type when mapped).
     * <p>
     * Split out of the folder walk above so the per-block work reads as a flat sequence rather than
     * six levels of nesting inside three try-with-resources blocks. Anything that goes wrong is
     * logged and skips this one block - a single malformed json in a player-editable pack folder
     * must never take the game down with it.
     */
    private static void registerBlockFile(PowerPackLoaderPacksLoader loader, ClassPicker classPicker,
                                          EntityPicker entityPicker, ItemPicker itemPicker, String Mod_ID,
                                          DeferredRegister.Blocks BLOCKS, DeferredRegister.Items ITEMS,
                                          DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES,
                                          Path packFolder, String packId, boolean extendOriginal, Path blockFile) {
        try (BufferedReader blockReader = Files.newBufferedReader(blockFile)) {
            JsonObject blockJson = JsonParser.parseReader(blockReader).getAsJsonObject();

            if (!blockJson.has("class")) {
                PowerNeoPackLoader.LOGGER.warn("Skipping block file (missing 'class' field): " + blockFile.getFileName());
                return;
            }

            String fileName = blockFile.getFileName().toString();
            String blockId = fileName.substring(0, fileName.length() - ".json".length());
            String displayName = blockJson.has("name") ? blockJson.get("name").getAsString() : blockId;
            // Not simply packId + "_" + blockId any more: an extend_original pack registers under
            // the bare block id, and either form falls through to a counted name rather than
            // overwriting an existing one. See PackRegistryNames.
            String blockNameIdFormated = PackRegistryNames.allocate(Mod_ID, packId, blockId, extendOriginal);
            String blockClass = blockJson.get("class").getAsString();
            boolean isGeckoLib = blockJson.has("geckolib") && blockJson.get("geckolib").getAsBoolean();

            Class<?> actualBlockClass = classPicker.getClassById(blockClass);

            if (actualBlockClass == null || !Block.class.isAssignableFrom(actualBlockClass)) {
                // Distinguish "this block needs a mod you don't have" from "that class name is
                // wrong". The first is normal, expected degradation for a pack built around an
                // optional dependency; logging it at ERROR alongside real mistakes teaches people
                // to ignore the log.
                List<String> missingMods = PackAnnotationScanner.missingModsFor(Mod_ID, blockClass);
                if (!missingMods.isEmpty()) {
                    PowerNeoPackLoader.LOGGER.info(
                            "Skipping '{}' from pack '{}': its class '{}' requires missing mod(s) {}.",
                            blockId, packId, blockClass, missingMods);
                } else {
                    PowerNeoPackLoader.LOGGER.error(
                            "Unknown or invalid block class '{}' in {} - is the class annotated with @PackBlock, "
                                    + "and does it belong to mod '{}'?", blockClass, blockFile.getFileName(), Mod_ID);
                }
                return;
            }

            @SuppressWarnings("unchecked")
            Class<? extends Block> targetBlockClass = (Class<? extends Block>) actualBlockClass;

            Class<?> targetEntityClass = entityPicker.getEnityByBlockClass(targetBlockClass);

            // Same idea, for the item half: a block class with no entry here just gets a
            // plain BlockItem below, same as always. See ItemPicker's class doc.
            Class<?> targetItemClass = itemPicker.getItemByBlockClass(targetBlockClass);
            // GeckoLibCompat.LOADED short-circuits BEFORE GeoItem.class ever gets
            // evaluated/resolved - required so this line can't throw NoClassDefFoundError when
            // GeckoLib isn't installed.
            boolean isGeckoLibItem = GeckoLibCompat.LOADED && targetItemClass != null
                    && GeoItem.class.isAssignableFrom(targetItemClass);

            PowerPackLoaderModelGenerator.generate(loader, Mod_ID, packFolder, packId, blockId,
                    blockNameIdFormated, targetBlockClass, isGeckoLib, isGeckoLibItem, blockJson);

            // Blocks with a mapped entity get their own dedicated BlockEntityType, whose id the
            // block itself may need at construction time (a block extending a vanilla class that
            // demands one). It can't be passed as a constructor argument - the type doesn't exist
            // yet - so it travels inside the context instead; see PackBlockContext.
            ResourceLocation entityTypeId = targetEntityClass != null
                    ? ResourceLocation.fromNamespaceAndPath(Mod_ID, blockNameIdFormated)
                    : null;

            PackBlockContext context = new PackBlockContext(Mod_ID, packId, blockId, blockNameIdFormated,
                    entityTypeId, blockJson, packFolder);

            DeferredBlock<Block> registeredBlock = BLOCKS.register(blockNameIdFormated,
                    () -> instantiateBlock(targetBlockClass, PackBlockProperties.build(blockJson, blockId), context));

            DeferredItem<BlockItem> registeredItem = ITEMS.register(blockNameIdFormated,
                    () -> instantiateItem(targetItemClass, registeredBlock.get(), context));

            DeferredHolder<BlockEntityType<?>, BlockEntityType<?>> registeredEntityType = null;
            if (targetEntityClass != null) {
                REGISTERED_ENTITY_CLASSES.put(entityTypeId, targetEntityClass);
                // Self-referencing box: the factory below needs to hand the entity class ITS OWN
                // about-to-be-registered BlockEntityType (not some other/vanilla one), but that type
                // doesn't exist yet while we're still building the Supplier that creates it. Safe
                // because DeferredRegister only invokes this outer supplier later (at RegisterEvent
                // time), by which point selfTypeHolder[0] below has already been assigned.
                @SuppressWarnings({"unchecked", "rawtypes"})
                DeferredHolder<BlockEntityType<?>, BlockEntityType<?>>[] selfTypeHolder = new DeferredHolder[1];
                selfTypeHolder[0] = BLOCK_ENTITIES.register(blockNameIdFormated, () ->
                        BlockEntityType.Builder.of((BlockPos pos, BlockState state) ->
                                        instantiateBlockEntity(targetEntityClass, selfTypeHolder[0].get(), pos, state),
                                registeredBlock.get()).build(null)
                );
                registeredEntityType = selfTypeHolder[0];
                PowerNeoPackLoader.LOGGER.info("Dynamically registered block, item AND entity from pack: {} (id: {})",
                        displayName, blockNameIdFormated);
            } else {
                PowerNeoPackLoader.LOGGER.info("Dynamically registered block & item from pack: {} (id: {})",
                        displayName, blockNameIdFormated);
            }

            loader.registerPackItem(packId, registeredItem);
            PowerPackLoaderRegistry.record(new PowerPackLoaderRegistry.PackBlockEntry(
                    context, registeredBlock, registeredItem, registeredEntityType));

        } catch (Exception ex) {
            PowerNeoPackLoader.LOGGER.error("Failed to parse block file: " + blockFile.getFileName(), ex);
        }
    }
}
