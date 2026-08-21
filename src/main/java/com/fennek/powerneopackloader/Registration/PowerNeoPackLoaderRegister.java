package com.fennek.powerneopackloader.Registration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.fennek.powerneopackloader.CoreComponentes.ClassPicker;
import com.fennek.powerneopackloader.CoreComponentes.EntityPicker;
import com.fennek.powerneopackloader.CoreComponentes.PowerPackLoaderRegistrationContext;
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

import java.io.BufferedReader;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

public class PowerNeoPackLoaderRegister {

    /**
     * Every dynamically-created {@link BlockEntityType}'s registry id, mapped to whatever entity
     * class {@link EntityPicker} said it should be paired with (e.g. {@code ChestBlockEntity},
     * {@code LoadableFurnaceBlockEntity}).
     * <p>
     * This exists purely so client-side code (see {@code PowerPackLoaderClientEvents}) can decide,
     * at {@code EntityRenderersEvent.RegisterRenderers} time, which vanilla
     * {@code BlockEntityRenderer} (if any) belongs on each pack-generated type. Registering a
     * {@code BlockEntityType} does NOT register a renderer for it - those are two entirely separate
     * registries in vanilla, and nothing about the block/entity registration path above touches
     * rendering at all. Populated synchronously while blocks are being registered (well before
     * RegisterRenderers fires), so a plain static map is enough - no DeferredRegister needed here,
     * this isn't a registry object itself.
     */
    public static final Map<ResourceLocation, Class<?>> REGISTERED_ENTITY_CLASSES = new HashMap<>();

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
        try {
            Constructor<?> selfAwareCtor = targetEntityClass.getDeclaredConstructor(BlockEntityType.class, BlockPos.class, BlockState.class);
            selfAwareCtor.setAccessible(true);
            return (BlockEntity) selfAwareCtor.newInstance(selfType, pos, state);
        } catch (NoSuchMethodException noSelfAwareCtor) {
            try {
                return (BlockEntity) targetEntityClass.getConstructor(BlockPos.class, BlockState.class).newInstance(pos, state);
            } catch (Exception e) {
                throw new RuntimeException("Failed to instantiate block entity class: " + targetEntityClass.getName(), e);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate block entity class: " + targetEntityClass.getName(), e);
        }
    }

    public static void RegisterBlocksFromPackLoader(PowerPackLoaderPacksLoader loader, ClassPicker classPicker, EntityPicker entityPicker, String Mod_ID, IEventBus modBus) {
        Path rootFolder = loader.getRootFolder(); //[cite: 1]

        if (rootFolder == null || !Files.exists(rootFolder)) { //[cite: 1]
            return; //[cite: 1]
        }

        DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Mod_ID); //[cite: 1]
        DeferredRegister.Items ITEMS = DeferredRegister.createItems(Mod_ID); //[cite: 1]
        // 1. Added DeferredRegister for Block Entities
        DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Mod_ID);

        com.fennek.powerneopackloader.Registration.PowerPackLoaderModelGenerator.clear(loader); //[cite: 1]
        loader.clearPackItems(); //[cite: 1]

        try (Stream<Path> stream = Files.list(rootFolder)) { //[cite: 1]
            stream.filter(Files::isDirectory) //[cite: 1]
                    .forEach(packFolder -> { //[cite: 1]
                        Path metaFile = packFolder.resolve(loader.getMetaFile()); //[cite: 1]

                        if (Files.exists(metaFile) && Files.isRegularFile(metaFile)) { //[cite: 1]

                            try (BufferedReader metaReader = Files.newBufferedReader(metaFile)) { //[cite: 1]
                                JsonObject metaJson = JsonParser.parseReader(metaReader).getAsJsonObject(); //[cite: 1]

                                if (metaJson.has("id")) { //[cite: 1]
                                    String packId = metaJson.get("id").getAsString(); //[cite: 1]

                                    com.fennek.powerneopackloader.Registration.PowerPackLoaderLangGenerator.generate( //[cite: 1]
                                            loader, packFolder, packId); //[cite: 1]

                                    Path blocksFolder = packFolder.resolve("data").resolve(packId).resolve("blocks"); //[cite: 1]

                                    if (Files.exists(blocksFolder) && Files.isDirectory(blocksFolder)) { //[cite: 1]

                                        try (Stream<Path> blockFilesStream = Files.list(blocksFolder)) { //[cite: 1]
                                            blockFilesStream
                                                    .filter(Files::isRegularFile) //[cite: 1]
                                                    .filter(path -> path.toString().endsWith(".json")) //[cite: 1]
                                                    .forEach(blockFile -> { //[cite: 1]

                                                        try (BufferedReader blockReader = Files.newBufferedReader(blockFile)) { //[cite: 1]
                                                            JsonObject blockJson = JsonParser.parseReader(blockReader).getAsJsonObject(); //[cite: 1]

                                                            if (blockJson.has("class")) { //[cite: 1]
                                                                String fileName = blockFile.getFileName().toString(); //[cite: 1]
                                                                String blockId = fileName.endsWith(".json") //[cite: 1]
                                                                        ? fileName.substring(0, fileName.length() - ".json".length()) //[cite: 1]
                                                                        : fileName; //[cite: 1]
                                                                String displayName = blockJson.has("name") //[cite: 1]
                                                                        ? blockJson.get("name").getAsString() //[cite: 1]
                                                                        : blockId; //[cite: 1]
                                                                String blockNameIdFormated = packId + "_" + blockId; //[cite: 1]
                                                                String blockClass = blockJson.get("class").getAsString(); //[cite: 1]

                                                                PowerNeoPackLoader.LOGGER.warn("Successfully read block! File: " + blockFile.getFileName() + " | Class: " + blockClass); //[cite: 1]

                                                                Class<?> actualBlockClass = classPicker.getClassById(blockClass); //[cite: 1]

                                                                if (actualBlockClass != null && Block.class.isAssignableFrom(actualBlockClass)) { //[cite: 1]

                                                                    @SuppressWarnings("unchecked")
                                                                    Class<? extends Block> targetBlockClass = (Class<? extends Block>) actualBlockClass; //[cite: 1]

                                                                    // 2. Query the EntityPicker for a mapped entity class using your exact typo-method
                                                                    Class<?> targetEntityClass = entityPicker.getEnityByBlockClass(targetBlockClass); //[cite: 2]

                                                                    com.fennek.powerneopackloader.Registration.PowerPackLoaderModelGenerator.generate( //[cite: 1]
                                                                            loader, Mod_ID, packFolder, packId, blockId, blockNameIdFormated, targetBlockClass); //[cite: 1]

                                                                    // Some reflectively-instantiated block classes (e.g. ChestLoadingExample, which
                                                                    // extends vanilla ChestBlock) need to know, from inside their own
                                                                    // Properties-only constructor, which BlockEntityType this exact block is about
                                                                    // to be paired with below - see PowerPackLoaderRegistrationContext for why this
                                                                    // can't just be passed as a constructor argument.
                                                                    ResourceLocation entityTypeId = targetEntityClass != null
                                                                            ? ResourceLocation.fromNamespaceAndPath(Mod_ID, blockNameIdFormated)
                                                                            : null;

                                                                    DeferredBlock<Block> registeredBlock = BLOCKS.register(blockNameIdFormated, () -> { //[cite: 1]
                                                                        PowerPackLoaderRegistrationContext.setCurrentEntityTypeId(entityTypeId);
                                                                        try { //[cite: 1]
                                                                            return targetBlockClass.getConstructor(BlockBehaviour.Properties.class) //[cite: 1]
                                                                                    .newInstance(BlockBehaviour.Properties.of() //[cite: 1]
                                                                                            .strength(2.0f, 3.0f) //[cite: 1]
                                                                                            .lightLevel(state -> 0) //[cite: 1]
                                                                                            .noOcclusion() //[cite: 1]
                                                                                            .dynamicShape()); //[cite: 1]
                                                                        } catch (Exception e) { //[cite: 1]
                                                                            throw new RuntimeException("Failed to instantiate block class: " + blockClass, e); //[cite: 1]
                                                                        } finally { //[cite: 1]
                                                                            PowerPackLoaderRegistrationContext.clearCurrentEntityTypeId();
                                                                        } //[cite: 1]
                                                                    }); //[cite: 1]

                                                                    DeferredItem<BlockItem> registeredItem = ITEMS.register(blockNameIdFormated, //[cite: 1]
                                                                            () -> new BlockItem(registeredBlock.get(), new Item.Properties()) //[cite: 1]
                                                                    ); //[cite: 1]

                                                                    // 3. Evaluate mapping: register BlockEntityType if an entity was mapped
                                                                    if (targetEntityClass != null) {
                                                                        REGISTERED_ENTITY_CLASSES.put(entityTypeId, targetEntityClass);
                                                                        // Self-referencing box: the factory below needs to hand the entity class ITS
                                                                        // OWN about-to-be-registered BlockEntityType (not some other/vanilla one), but
                                                                        // that type doesn't exist yet while we're still building the Supplier that
                                                                        // creates it. Safe because DeferredRegister only invokes this outer supplier
                                                                        // later (at RegisterEvent time), by which point selfTypeHolder[0] below has
                                                                        // already been assigned.
                                                                        @SuppressWarnings({"unchecked", "rawtypes"})
                                                                        DeferredHolder<BlockEntityType<?>, BlockEntityType<?>>[] selfTypeHolder = new DeferredHolder[1];
                                                                        selfTypeHolder[0] = BLOCK_ENTITIES.register(blockNameIdFormated, () ->
                                                                                BlockEntityType.Builder.of((BlockPos pos, BlockState state) ->
                                                                                        instantiateBlockEntity(targetEntityClass, selfTypeHolder[0].get(), pos, state),
                                                                                        registeredBlock.get()).build(null)
                                                                        );
                                                                        PowerNeoPackLoader.LOGGER.info("Dynamically registered functional block, item, AND entity from pack: {} (id: {})", displayName, blockNameIdFormated);
                                                                    } else {
                                                                        // Fallback to normal logging without entity registration[cite: 1]
                                                                        PowerNeoPackLoader.LOGGER.info("Dynamically registered functional block & item from pack: {} (id: {})", displayName, blockNameIdFormated); //[cite: 1]
                                                                    }

                                                                    loader.registerPackItem(packId, registeredItem); //[cite: 1]

                                                                } else {
                                                                    PowerNeoPackLoader.LOGGER.error("Unknown or invalid block class in JSON: " + blockClass); //[cite: 1]
                                                                }

                                                            } else {
                                                                PowerNeoPackLoader.LOGGER.warn("Skipping block file (missing 'class' field): " + blockFile.getFileName()); //[cite: 1]
                                                            }
                                                        } catch (Exception ex) { //[cite: 1]
                                                            PowerNeoPackLoader.LOGGER.error("Failed to parse block file: " + blockFile.getFileName(), ex); //[cite: 1]
                                                        }

                                                    }); //[cite: 1]
                                        } catch (Exception e) { //[cite: 1]
                                            PowerNeoPackLoader.LOGGER.error("Failed to read blocks folder: " + blocksFolder, e); //[cite: 1]
                                        }

                                    } else {
                                        PowerNeoPackLoader.LOGGER.warn("No valid blocks folder found at: " + blocksFolder); //[cite: 1]
                                    }

                                } else {
                                    PowerNeoPackLoader.LOGGER.warn("Skipping folder: " + packFolder.getFileName() + " (functional.meta.json is missing the 'id' field)"); //[cite: 1]
                                }

                            } catch (Exception e) { //[cite: 1]
                                PowerNeoPackLoader.LOGGER.error("Failed to parse functional.meta.json in folder: " + packFolder.getFileName(), e); //[cite: 1]
                            }

                        } else {
                            PowerNeoPackLoader.LOGGER.warn("Skipping folder (no functional.meta.json found): " + packFolder.getFileName()); //[cite: 1]
                        }
                    }); //[cite: 1]
        } catch (Exception e) { //[cite: 1]
            PowerNeoPackLoader.LOGGER.error("Error scanning for functional blocks to register", e); //[cite: 1]
        }

        BLOCKS.register(modBus); //[cite: 1]
        ITEMS.register(modBus); //[cite: 1]

        // 4. Register all captured Block Entity Types to the Mod Event Bus
        BLOCK_ENTITIES.register(modBus);
    }
}