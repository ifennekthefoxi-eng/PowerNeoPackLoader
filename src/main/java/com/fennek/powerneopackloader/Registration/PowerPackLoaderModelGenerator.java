package com.fennek.powerneopackloader.Registration;

import com.fennek.powerneopackloader.PowerNeoPackLoader;
import com.fennek.powerneopackloader.loadingPacks.PowerPackLoaderPacksLoader;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates the vanilla blockstate/item-model json a registered data-driven block needs to
 * actually render - never inventing content a pack doesn't provide:
 * <ol>
 *     <li><b>Blockstate chain:</b>
 *         <ol type="a">
 *             <li>if {@code assets/<packId>/blockstates/<blockId>.json} exists in the pack, it is
 *             copied VERBATIM into the generated output - a pack author gets full control
 *             (multipart, custom properties, whatever vanilla blockstates support), and this
 *             always wins over auto-generation;</li>
 *             <li>otherwise, if {@code assets/<packId>/models/block/<blockId>.json} exists,
 *             auto-generate a simple blockstate pointing at it (with FACING variants if the
 *             registered block class is directional, a single variant otherwise);</li>
 *             <li>otherwise, generate nothing at all for the block - with no blockstate json,
 *             vanilla falls back to its own default missing/checker model on its own, exactly as
 *             if this block had never been touched.</li>
 *         </ol>
 *     </li>
 *     <li><b>Item model chain:</b>
 *         <ol type="a">
 *             <li>if {@code assets/<packId>/models/item/<blockId>.json} exists, generate an
 *             item model pointing at it;</li>
 *             <li>otherwise, if a block model (step 1b) resolved, generate an item model that
 *             reuses it as its {@code "parent"} - the same trick a vanilla BlockItem with no
 *             dedicated item model uses, inheriting the block model's own "display" section for
 *             free;</li>
 *             <li>otherwise (neither exists), generate nothing - vanilla's own missing/checker
 *             item model applies untouched. This also applies when step 1a's hand-authored
 *             blockstate was used, since its model references aren't necessarily
 *             {@code models/block/<blockId>.json} at all - the pack author is expected to supply
 *             a matching item model themselves if they need one.</li>
 *         </ol>
 *     </li>
 * </ol>
 * Every path above is keyed off the block json's own FILENAME ({@code blockId}), never off its
 * free-text {@code "name"} display field - see {@code PowerNeoPackLoaderRegister} for why mixing
 * the two broke every pack block's rendering.
 * Output lands under {@link PowerPackLoaderPacksLoader#getGeneratedResourcesFolder()}, which
 * {@link PowerPackLoaderPacksLoader#discoverExtensions()} merges back into the client resource
 * stack unmapped (it's written directly under the mod's real namespace, matching how the block
 * is actually registered - no {@code NamespaceRewritingPackResources} involved here).
 */
public final class PowerPackLoaderModelGenerator {

    private PowerPackLoaderModelGenerator() {
    }

    /** Deletes any previously generated content for this loader, so renamed/removed pack
     *  blocks don't leave stale blockstate/item-model json behind. Call once per scan, before
     *  the per-block {@link #generate} calls. */
    public static void clear(PowerPackLoaderPacksLoader loader) {
        Path folder = loader.getGeneratedResourcesFolder();
        if (!Files.isDirectory(folder)) return;

        try (var walk = Files.walk(folder)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    PowerNeoPackLoader.LOGGER.warn("Failed to delete stale generated resource '{}': {}", path, e.getMessage());
                }
            });
        } catch (IOException e) {
            PowerNeoPackLoader.LOGGER.warn("Failed to clear generated resources folder '{}': {}", folder, e.getMessage());
        }
    }

    /**
     * @param modId        this mod's own real namespace (e.g. "powerneopackloader") - where the
     *                     block/item are actually registered, and so where the generated json
     *                     needs to live to be found for them.
     * @param packFolder   the specific pack's own root folder (e.g. ".../functional_pack").
     * @param packId       the pack's own id from its meta json - also its asset namespace.
     * @param blockId      the block definition's id - ALWAYS the block's json filename (e.g. a
     *                     file named "i4_engine.json" gives "i4_engine"), never the free-text
     *                     "name" display field. Every asset path below is built from this, so it
     *                     must exactly match the filenames a pack author actually used under
     *                     assets/&lt;packId&gt;/... - matching how CACW keys its engine packs off
     *                     each engine json's own filename rather than its display name.
     * @param registryName the block/item's actual registered name (e.g. "<packId>_<blockId>").
     * @param blockClass   the Java class the block was instantiated as, used only to decide
     *                     whether to generate FACING variants for the auto-generated fallback
     *                     blockstate (irrelevant if the pack supplies its own blockstate json).
     */
    public static void generate(PowerPackLoaderPacksLoader loader, String modId, Path packFolder,
                                 String packId, String blockId, String registryName, Class<?> blockClass) {
        Path packAssets = packFolder.resolve("assets").resolve(packId);
        Path modelsFolder = packAssets.resolve("models");
        boolean hasBlockModel = Files.isRegularFile(modelsFolder.resolve("block").resolve(blockId + ".json"));
        boolean hasItemModel = Files.isRegularFile(modelsFolder.resolve("item").resolve(blockId + ".json"));

        Path generatedAssets = loader.getGeneratedResourcesFolder().resolve("assets").resolve(modId);
        Path blockstateTarget = generatedAssets.resolve("blockstates").resolve(registryName + ".json");

        // A pack can hand-author its own assets/<packId>/blockstates/<blockId>.json - e.g. for
        // multipart blockstates, non-FACING properties, or anything else the simple auto-gen
        // below doesn't cover. When present it's copied VERBATIM (not regenerated) and always
        // wins over auto-generation: the model references inside it already read "<packId>:..."
        // which resolves fine, since this pack's own assets are exposed under that namespace.
        Path packBlockState = packAssets.resolve("blockstates").resolve(blockId + ".json");
        boolean hasPackBlockState = Files.isRegularFile(packBlockState);
        if (hasPackBlockState) {
            copyFile(packBlockState, blockstateTarget);
        } else if (hasBlockModel) {
            writeJson(blockstateTarget, buildBlockState(packId, blockId, blockClass));
        } else {
            PowerNeoPackLoader.LOGGER.info(
                    "No blockstates/{}.json or models/block/{}.json in pack '{}' - '{}' keeps the default missing model.",
                    blockId, blockId, packId, registryName);
        }

        if (hasItemModel) {
            writeJson(generatedAssets.resolve("models").resolve("item").resolve(registryName + ".json"),
                    parentModelJson(packId + ":item/" + blockId));
        } else if (hasBlockModel) {
            writeJson(generatedAssets.resolve("models").resolve("item").resolve(registryName + ".json"),
                    parentModelJson(packId + ":block/" + blockId));
        } else if (!hasPackBlockState) {
            PowerNeoPackLoader.LOGGER.info(
                    "No item or block model for '{}' in pack '{}' - '{}' keeps the default missing item model.",
                    blockId, packId, registryName);
        }
        // Note: if a pack supplies its own blockstates/<blockId>.json but no models/item/ or
        // models/block/ json (e.g. its blockstate references model paths directly), no item
        // model is generated either - the pack author is expected to also ship whatever
        // models/item/<blockId>.json their custom blockstate needs; nothing here can guess it.
    }

    private static String buildBlockState(String packId, String blockName, Class<?> blockClass) {
        String model = packId + ":block/" + blockName;
        if (HorizontalDirectionalBlock.class.isAssignableFrom(blockClass)) {
            return "{\n"
                    + "  \"variants\": {\n"
                    + "    \"facing=north\": { \"model\": \"" + model + "\" },\n"
                    + "    \"facing=east\":  { \"model\": \"" + model + "\", \"y\": 90 },\n"
                    + "    \"facing=south\": { \"model\": \"" + model + "\", \"y\": 180 },\n"
                    + "    \"facing=west\":  { \"model\": \"" + model + "\", \"y\": 270 }\n"
                    + "  }\n"
                    + "}\n";
        }
        return "{\n  \"variants\": {\n    \"\": { \"model\": \"" + model + "\" }\n  }\n}\n";
    }

    private static String parentModelJson(String parentModel) {
        return "{\n  \"parent\": \"" + parentModel + "\"\n}\n";
    }

    private static void writeJson(Path target, String content) {
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            PowerNeoPackLoader.LOGGER.error("Failed to write generated resource '{}': {}", target, e.getMessage());
        }
    }

    /** Copies a pack-authored file (e.g. a hand-written blockstate) into the generated folder
     *  verbatim - no rewriting, since it already references its own pack's namespace. */
    private static void copyFile(Path source, Path target) {
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            PowerNeoPackLoader.LOGGER.error("Failed to copy pack blockstate '{}' to '{}': {}", source, target, e.getMessage());
        }
    }
}
