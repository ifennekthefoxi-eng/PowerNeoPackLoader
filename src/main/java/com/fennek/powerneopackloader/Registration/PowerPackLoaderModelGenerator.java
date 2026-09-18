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
 * actually render - never inventing content a pack doesn't provide (with one deliberate
 * exception, see step 1c below):
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
 *             <li>otherwise, if {@code isGeckoLib} is true, auto-generate an ELEMENTLESS block
 *             model (own file, under this mod's namespace - not the pack's) plus a matching
 *             single-variant blockstate. GeckoLib blocks are expected to have no baked model at
 *             all - their real geometry is drawn by a {@code GeoBlockRenderer} block entity
 *             renderer - so leaving them with NO blockstate (the pre-existing behavior) meant
 *             vanilla's own missing/checker model rendered on top of/instead of the GeoBlockRenderer
 *             output. A model with no {@code "elements"} key bakes to zero quads, so it draws
 *             nothing itself and just gives the blockstate a valid target;</li>
 *             <li>otherwise (a non-GeckoLib block with neither file), generate nothing at all -
 *             with no blockstate json, vanilla falls back to its own default missing/checker
 *             model on its own, exactly as if this block had never been touched.</li>
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
 *             <li>otherwise, if {@code isGeckoLibItem} is true (the block class has a mapped
 *             {@code GeoItem} via {@code ItemPicker}), generate {@code {"parent":
 *             "builtin/entity"}} - the exact marker GeckoLib's own item-render dispatch requires
 *             to hand rendering off to that GeoItem's GeoItemRenderer. Nothing else is valid here:
 *             any texture/layer0 content would make GeckoLib skip the animated render entirely;</li>
 *             <li>otherwise, if a {@code textures/block/<blockId>.png} convention texture was
 *             found (used for step 1c's particle texture too), generate a flat
 *             {@code minecraft:item/generated} icon from it - NOT a parent onto step 1c's
 *             elementless model, which would make the item itself invisible in inventory/hand.
 *             This is the fallback for a GeckoLib block with no mapped GeoItem;</li>
 *             <li>otherwise (nothing resolved), generate nothing - vanilla's own missing/checker
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
     *                     file named "geckolib_example_block.json" gives "i4_engine"), never the free-text
     *                     "name" display field. Every asset path below is built from this, so it
     *                     must exactly match the filenames a pack author actually used under
     *                     assets/&lt;packId&gt;/... - matching how CACW keys its engine packs off
     *                     each engine json's own filename rather than its display name.
     * @param registryName the block/item's actual registered name (e.g. "<packId>_<blockId>").
     * @param blockClass   the Java class the block was instantiated as, used only to decide
     *                     whether to generate FACING variants for the auto-generated fallback
     *                     blockstate (irrelevant if the pack supplies its own blockstate json).
     * @param isGeckoLib   true if this block's json set {@code "geckolib": true}. Forces the plain
     *                     non-directional single-variant blockstate regardless of any pack-authored
     *                     blockstate.json or the block class's own type - see the comment above the
     *                     blockstate chain below for why FACING variants don't apply to these.
     * @param isGeckoLibItem true if the block class resolved to a mapped item class (via
     *                     {@code ItemPicker}) that itself implements GeckoLib's {@code GeoItem} -
     *                     see the item-model chain below for why this needs a completely different
     *                     generated item model than every other case handles.
     */
    public static void generate(PowerPackLoaderPacksLoader loader, String modId, Path packFolder,
                                 String packId, String blockId, String registryName, Class<?> blockClass,
                                 boolean isGeckoLib, boolean isGeckoLibItem) {
        Path packAssets = packFolder.resolve("assets").resolve(packId);
        Path modelsFolder = packAssets.resolve("models");
        boolean hasBlockModel = Files.isRegularFile(modelsFolder.resolve("block").resolve(blockId + ".json"));
        boolean hasItemModel = Files.isRegularFile(modelsFolder.resolve("item").resolve(blockId + ".json"));

        // GeckoLib blocks are never expected to ship a models/block/<blockId>.json - their real
        // geometry comes from the GeoModel (geo/animation/texture triplet), not a baked model - so
        // "no block model" is the NORMAL case for them, not a pack authoring mistake. The one thing
        // we can still auto-detect by convention is a breaking-particle/inventory-icon texture at
        // textures/block/<blockId>.png, matching the same <blockId>-keyed convention as every other
        // path in this file (see the i4_engine example pack: textures/block/geckolib_example_block.png).
        Path conventionTexture = packAssets.resolve("textures").resolve("block").resolve(blockId + ".png");
        boolean hasConventionTexture = isGeckoLib && Files.isRegularFile(conventionTexture);
        String conventionTextureId = hasConventionTexture ? packId + ":block/" + blockId : null;

        Path generatedAssets = loader.getGeneratedResourcesFolder().resolve("assets").resolve(modId);
        Path blockstateTarget = generatedAssets.resolve("blockstates").resolve(registryName + ".json");

        // A pack can hand-author its own assets/<packId>/blockstates/<blockId>.json - e.g. for
        // multipart blockstates, non-FACING properties, or anything else the simple auto-gen
        // below doesn't cover. When present it's copied VERBATIM (not regenerated) and always
        // wins over auto-generation: the model references inside it already read "<packId>:..."
        // which resolves fine, since this pack's own assets are exposed under that namespace.
        //
        // GeckoLib entity blocks are the one exception: their real visual comes from the
        // BlockEntityRenderer (GeoBlockRenderer), not from FACING-variant baked-model rotation,
        // and the block classes behind them (see ExampleChestBlock) are typically plain Blocks
        // with no FACING property at all. A hand-authored blockstate that still uses
        // "facing=north/east/south/west" variant keys - the natural thing to copy-paste from a
        // directional block's pack - would then never match the block's actual state (which has
        // no "facing" value to match against), and EVERY placed instance renders as the
        // missing-model checkerboard, not just a visual glitch. "geckolib": true in the block json
        // opts out of the pack-authored blockstate entirely and always falls through to the plain
        // single-variant auto-gen path below, exactly like a non-directional block.
        Path packBlockState = packAssets.resolve("blockstates").resolve(blockId + ".json");
        boolean hasPackBlockState = !isGeckoLib && Files.isRegularFile(packBlockState);
        if (hasPackBlockState) {
            copyFile(packBlockState, blockstateTarget);
        } else if (hasBlockModel) {
            writeJson(blockstateTarget, buildBlockState(packId + ":block/" + blockId,
                    !isGeckoLib && HorizontalDirectionalBlock.class.isAssignableFrom(blockClass)));
        } else if (isGeckoLib) {
            // SECONDARY PIPELINE for GeckoLib blocks: without this branch, a GeckoLib block that
            // ships no baked model (the normal case - see above) fell all the way through to the
            // "generate nothing" branch below, so vanilla rendered its own missing/checker model
            // OVER wherever the GeoBlockRenderer draws - the block entity, type, and renderer are
            // all registered and working (nothing wrong with the GeckoLib side at all), the block
            // just also has a big neon "missing model" cube competing with it.
            //
            // The fix is to generate our OWN elementless model (no "elements" key => bakes to zero
            // quads => nothing drawn by the block's own baked mesh) so there's a valid model for
            // the blockstate to point at, and the GeoBlockRenderer becomes the only thing actually
            // drawing the block. Written under this mod's own namespace (modId), NOT the pack's -
            // this file doesn't exist in the pack at all, we're inventing it, so there's nothing to
            // reference under packId.
            writeJson(generatedAssets.resolve("models").resolve("block").resolve(registryName + ".json"),
                    invisibleModelJson(conventionTextureId));
            writeJson(blockstateTarget, buildBlockState(modId + ":block/" + registryName, false));
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
        } else if (isGeckoLibItem) {
            // GeckoLib only takes over an item's rendering when its item model json's "parent" is
            // exactly "builtin/entity" - that's the literal switch GeckoLib's own item render
            // dispatch checks for (see the wiki's "My item is completely invisible" FAQ: a GeoItem
            // with a normal/missing item model just silently never gets its GeoItemRenderer
            // called). A flat minecraft:item/generated icon here - the hasConventionTexture branch
            // below - would be valid JSON and LOOK fine at a glance, but would silently disable
            // the animated 3D render for an item class that's otherwise fully wired for it.
            writeJson(generatedAssets.resolve("models").resolve("item").resolve(registryName + ".json"),
                    builtinEntityItemJson());
        } else if (hasConventionTexture) {
            // Parenting the item model to the invisible block model above (like the hasBlockModel
            // branch does) would give the ITEM zero quads too - an invisible icon in inventory and
            // hand, which is arguably more confusing than the missing-texture icon it replaces.
            // This is the fallback for a "geckolib": true block that has NO GeoItem mapped for it
            // (isGeckoLibItem false) - i.e. it's still using a plain BlockItem - so the best
            // available icon is a flat 2D one from the same convention texture used for breaking
            // particles above, not a 3D render nothing here is set up to produce.
            writeJson(generatedAssets.resolve("models").resolve("item").resolve(registryName + ".json"),
                    flatItemJson(conventionTextureId));
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

    /** @param model the full "namespace:block/path" model reference the blockstate should point
     *               at - already fully built by the caller, since the two callers now source it
     *               from different namespaces (the pack's, for a pack-supplied model; this mod's
     *               own, for the auto-generated invisible GeckoLib model). */
    private static String buildBlockState(String model, boolean directional) {
        if (directional) {
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

    /** An elementless block model: no "elements" key means the baked model has zero quads, so
     *  nothing is drawn by the block's own mesh - the GeoBlockRenderer becomes the sole source of
     *  the block's visual. {@code particleTexture} (nullable) only affects break/step particles;
     *  omitted entirely when no convention texture was found, rather than guessing a path that
     *  might not exist and trading the checkerboard block for a checkerboard particle instead. */
    private static String invisibleModelJson(String particleTexture) {
        if (particleTexture == null) {
            return "{\n}\n";
        }
        return "{\n  \"textures\": {\n    \"particle\": \"" + particleTexture + "\"\n  }\n}\n";
    }

    /** A flat 2D inventory/hand icon parented to vanilla's generated-item base, for GeckoLib
     *  blocks that have no models/item/&lt;blockId&gt;.json of their own. This is deliberately NOT
     *  a 3D render of the geo model - that needs the block class to also implement GeoItem with
     *  its own GeoItemRenderer, which is a bigger per-block opt-in, not something this generator
     *  can produce generically. */
    private static String flatItemJson(String texture) {
        return "{\n  \"parent\": \"minecraft:item/generated\",\n  \"textures\": {\n    \"layer0\": \"" + texture + "\"\n  }\n}\n";
    }

    /** The item model json GeckoLib itself requires (not a convention of this generator's own) to
     *  hand rendering off to a GeoItem's GeoItemRenderer instead of drawing flat 2D quads. No
     *  textures/elements/anything else belongs in this file - GeckoLib's dispatch never reads
     *  them, and the model's actual look comes entirely from the GeoItemRenderer's GeoModel. */
    private static String builtinEntityItemJson() {
        return "{\n  \"parent\": \"builtin/entity\"\n}\n";
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
