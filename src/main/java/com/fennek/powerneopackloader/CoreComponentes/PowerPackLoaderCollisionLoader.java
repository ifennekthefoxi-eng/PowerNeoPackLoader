package com.fennek.powerneopackloader.CoreComponentes;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.fennek.powerneopackloader.PowerNeoPackLoader;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads {@code data/<packId>/collisions/<blockId>.json} - the collision counterpart to
 * {@code assets/<packId>/models/block/<blockId>.json} (see {@code PowerPackLoaderModelGenerator}).
 * Directly ported from CACW's {@code EngineCollisionLoader}, with one difference: CACW looks
 * its shapes up by a runtime-selectable "EngineId" (many engines share one registered Block),
 * while every PNPL pack block IS its own registered Block, so the natural lookup key here is
 * simply that block's own registry name - no per-instance/block-entity state needed.
 * <p>
 * The file is a plain block-model json, exactly like the visual model - only its top-level
 * "elements" array is read (from/to per element); textures, faces, per-element "rotation" and
 * everything else are ignored, since none of that means anything for a collision box.
 * <p>
 * Resolution chain:
 * <ol>
 *     <li>No {@code collisions/<blockId>.json} for this block at all (folder/pack missing, or
 *     the pack just didn't include one) - {@link #getShape} falls back to {@link #DEFAULT_SHAPE},
 *     a plain full 16x16x16 box, and nothing below runs.</li>
 *     <li>A file exists - its elements are read once here (on every resource/data reload) and
 *     converted into a VoxelShape, authored/interpreted as if the block were facing SOUTH (0
 *     degrees - matches {@code Direction.toYRot()}), then rotated into all 4 horizontal facings
 *     up front and cached, so a part authored sticking out the west side of the model stays on
 *     the model's own "left" no matter which way the block actually ends up facing in the
 *     world, instead of staying pinned to world-west.</li>
 * </ol>
 * The resource id Minecraft hands {@link #apply} is {@code <packId>:<blockId>} (this loader
 * targets the "collisions" directory across the whole merged resource stack, and each pack's own
 * data is exposed under its own packId namespace - see {@code NamespaceRewritingPackResources}).
 * That's rewritten here into {@code <modId>:<packId>_<blockId>} - the block's ACTUAL registry
 * name, exactly matching what {@code PowerNeoPackLoaderRegister} registered it as - so
 * {@link LoadableModelHorizontalDirectionalBlock#getShape} can look itself up with nothing more
 * than {@code BuiltInRegistries.BLOCK.getKey(this)}.
 */
public class PowerPackLoaderCollisionLoader extends SimpleJsonResourceReloadListener {
    private static final Gson GSON = new Gson();

    /** Fallback used for step 1 of the chain - untouched, plain full-block collision. */
    public static final VoxelShape DEFAULT_SHAPE = Shapes.block();

    // One entry per block that actually has a collisions json; each entry is pre-rotated for
    // all 4 horizontal facings so getShape() below is a flat map lookup, not per-call trig.
    private static final Map<ResourceLocation, EnumMap<Direction, VoxelShape>> SHAPES_BY_BLOCK = new HashMap<>();

    public PowerPackLoaderCollisionLoader() {
        // Targets the "data/<namespace>/collisions/" directory, across every namespace on the
        // merged resource stack (vanilla, every discovered pack, any other mod's data too - a
        // json that doesn't parse as a block-model-shaped file is just skipped, see apply()).
        super(GSON, "collisions");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> objectIn, ResourceManager resourceManagerIn, ProfilerFiller profilerIn) {
        SHAPES_BY_BLOCK.clear();
        objectIn.forEach((packScopedId, jsonElement) -> {
            try {
                CollisionModel model = GSON.fromJson(jsonElement, CollisionModel.class);
                List<AABB> southBoxes = toBoxes(model);
                if (southBoxes.isEmpty()) return; // no usable elements - same as no file at all

                ResourceLocation registryId = ResourceLocation.fromNamespaceAndPath(
                        PowerNeoPackLoader.MOD_ID, packScopedId.getNamespace() + "_" + packScopedId.getPath());

                EnumMap<Direction, VoxelShape> perFacing = new EnumMap<>(Direction.class);
                for (Direction facing : Direction.Plane.HORIZONTAL) {
                    VoxelShape shape = Shapes.empty();
                    for (AABB box : southBoxes) {
                        shape = Shapes.or(shape, Shapes.create(rotateHorizontal(box, facing)));
                    }
                    perFacing.put(facing, shape);
                }
                SHAPES_BY_BLOCK.put(registryId, perFacing);
            } catch (Exception e) {
                PowerNeoPackLoader.LOGGER.warn(
                        "Failed to parse collision model '{}', block will use the default collision box: {}",
                        packScopedId, e.getMessage());
            }
        });
    }

    /**
     * The resolved, facing-rotated collision shape for a block - {@link #DEFAULT_SHAPE} if
     * {@code registryId} is null or has no matching collisions json.
     */
    public static VoxelShape getShape(@Nullable ResourceLocation registryId, Direction facing) {
        if (registryId == null) return DEFAULT_SHAPE;

        EnumMap<Direction, VoxelShape> perFacing = SHAPES_BY_BLOCK.get(registryId);
        if (perFacing == null) return DEFAULT_SHAPE;

        VoxelShape shape = perFacing.get(facing);
        return shape != null ? shape : DEFAULT_SHAPE;
    }

    /**
     * Reads a model's "elements" into plain 0..1-space AABBs (min/max normalized so a pack
     * author swapping from/to on an axis can't produce an inverted box). These are always in
     * the SOUTH reference orientation - {@link #rotateHorizontal} does the per-facing work.
     */
    private static List<AABB> toBoxes(@Nullable CollisionModel model) {
        List<AABB> boxes = new ArrayList<>();
        if (model == null || model.elements == null) return boxes;

        for (CollisionModel.Element element : model.elements) {
            if (element == null || element.from == null || element.to == null
                    || element.from.length != 3 || element.to.length != 3) continue;

            double minX = Math.min(element.from[0], element.to[0]) / 16.0;
            double minY = Math.min(element.from[1], element.to[1]) / 16.0;
            double minZ = Math.min(element.from[2], element.to[2]) / 16.0;
            double maxX = Math.max(element.from[0], element.to[0]) / 16.0;
            double maxY = Math.max(element.from[1], element.to[1]) / 16.0;
            double maxZ = Math.max(element.from[2], element.to[2]) / 16.0;

            // Per-element "rotation" (Blockbench's cube-rotation gizmo) is deliberately ignored:
            // a VoxelShape box has to stay axis-aligned, and this is a collision box, not a
            // render element - a pack author who wants a rotated-looking collider should just
            // draw that element axis-aligned in the collisions model to begin with.
            boxes.add(new AABB(minX, minY, minZ, maxX, maxY, maxZ));
        }
        return boxes;
    }

    /**
     * Rotates a SOUTH-reference-orientation box (0..1 space) to the given horizontal facing -
     * SOUTH is the reference/identity direction because that's what angle 0 corresponds to.
     * Facing is always a multiple of 90 degrees, so this is an exact swap-and-mirror, not an
     * approximation - no precision is lost the way a general sin/cos rotation could.
     */
    private static AABB rotateHorizontal(AABB box, Direction facing) {
        return switch (facing) {
            case SOUTH -> box;
            case WEST -> new AABB(1 - box.maxZ, box.minY, box.minX, 1 - box.minZ, box.maxY, box.maxX);
            case NORTH -> new AABB(1 - box.maxX, box.minY, 1 - box.maxZ, 1 - box.minX, box.maxY, 1 - box.minZ);
            case EAST -> new AABB(box.minZ, box.minY, 1 - box.maxX, box.maxZ, box.maxY, 1 - box.minX);
            default -> box; // UP/DOWN never occur - FACING is HORIZONTAL_FACING only
        };
    }

    /**
     * Minimal mirror of a vanilla block model json - only what collision needs. Gson silently
     * ignores every other field a real model json has (textures, faces, display, credit...), so
     * pack authors can reuse/export the exact same kind of file they already make for
     * assets/&lt;packId&gt;/models/block/&lt;blockId&gt;.json.
     */
    private static class CollisionModel {
        List<Element> elements;

        private static class Element {
            float[] from;
            float[] to;
        }
    }
}
