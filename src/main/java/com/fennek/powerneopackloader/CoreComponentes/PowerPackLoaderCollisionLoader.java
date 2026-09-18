package com.fennek.powerneopackloader.CoreComponentes;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.fennek.powerneopackloader.PowerNeoPackLoader;
import com.fennek.powerneopackloader.Registration.PowerPackLoaderRegistry;
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

                // Ask the registry what this block was ACTUALLY registered as, rather than
                // rebuilding the name. Two things made the old
                // "<this library's mod id>:<packId>_<blockId>" guess wrong: it named THIS library
                // instead of the mod that owns the block, so a consuming mod's collision shapes
                // were filed under an id nothing would ever look up; and the prefixed form is only
                // one of the names a block can end up with - an extend_original pack drops the
                // prefix, a collision adds a counter. See PackRegistryNames.
                ResourceLocation registryId = PowerPackLoaderRegistry
                        .findByPackAndBlock(packScopedId.getNamespace(), packScopedId.getPath())
                        .map(PowerPackLoaderRegistry.PackBlockEntry::id)
                        .orElse(null);
                if (registryId == null) {
                    // A collisions json with no matching block - another mod's data, or a leftover
                    // file for a block that was renamed or removed. Not an error.
                    return;
                }

                EnumMap<Direction, VoxelShape> perFacing = new EnumMap<>(Direction.class);
                for (Direction facing : Direction.Plane.HORIZONTAL) {
                    VoxelShape shape = Shapes.empty();
                    for (AABB box : southBoxes) {
                        shape = Shapes.or(shape, Shapes.create(rotateHorizontal(box, facing)));
                    }
                    perFacing.put(facing, shape);
                }
                SHAPES_BY_BLOCK.put(registryId, perFacing);

                // Logged because "is my collisions json actually being read?" is otherwise
                // unanswerable from outside the game: a file that never loads and a file that
                // loads but produces an unexpected shape look identical when you stand in front
                // of the block. These are the unrotated (south) bounds in block space, so they
                // can be compared straight against the json's own from/to divided by 16.
                VoxelShape southShape = perFacing.get(Direction.SOUTH);
                PowerNeoPackLoader.LOGGER.info(
                        "Loaded {} collision box(es) for {} - bounds x {}..{}, y {}..{}, z {}..{}",
                        southBoxes.size(), registryId,
                        southShape.min(Direction.Axis.X), southShape.max(Direction.Axis.X),
                        southShape.min(Direction.Axis.Y), southShape.max(Direction.Axis.Y),
                        southShape.min(Direction.Axis.Z), southShape.max(Direction.Axis.Z));
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
     * Reads a model's {@code "elements"} into 0..1-space AABBs, ONE BOX PER ELEMENT, exactly as
     * the json writes it.
     *
     * <h2>The rule: 1:1 with the file</h2>
     * Every element becomes the box its own {@code from}/{@code to} describe, divided by 16.
     * Nothing is inferred, expanded, merged, approximated or corrected. If a collisions json and a
     * model json are the same file, the collider is the model's boxes - so what you draw is what
     * you collide with, and a pack author can reason about the collider by reading the file rather
     * than by reading this class.
     * <p>
     * The one liberty taken is normalising min/max per axis, so an element written with {@code to}
     * smaller than {@code from} on some axis still yields a real box instead of an inverted,
     * zero-volume one. That is repairing a malformed element, not reinterpreting a valid one.
     *
     * <h2>Why {@code "rotation"} is not applied</h2>
     * A {@link VoxelShape} is a union of axis-aligned boxes; there is no such thing as a rotated
     * one. Honouring an element's rotate gizmo therefore cannot reproduce the rotated part - the
     * closest possible is the axis-aligned BOUNDING box of the rotated corners, which is strictly
     * BIGGER than what the file says, growing a 45-degree part by up to ~40% on two axes. That was
     * tried, and it makes the collider visibly larger than the model rather than matching it.
     * Taking the element as written keeps the boxes the size the author drew them.
     * <p>
     * The consequence is honest and worth knowing: a rotated element collides at the position it
     * occupies BEFORE its rotation. A model built mostly from rotated parts will have a collider
     * that does not follow its visible geometry, and the fix is to draw the collisions json (which
     * need not be the same file as the model) from axis-aligned boxes.
     */
    private static List<AABB> toBoxes(@Nullable CollisionModel model) {
        List<AABB> boxes = new ArrayList<>();
        if (model == null || model.elements == null) return boxes;

        for (CollisionModel.Element element : model.elements) {
            if (element == null || element.from == null || element.to == null
                    || element.from.length != 3 || element.to.length != 3) continue;

            boxes.add(new AABB(
                    Math.min(element.from[0], element.to[0]) / 16.0,
                    Math.min(element.from[1], element.to[1]) / 16.0,
                    Math.min(element.from[2], element.to[2]) / 16.0,
                    Math.max(element.from[0], element.to[0]) / 16.0,
                    Math.max(element.from[1], element.to[1]) / 16.0,
                    Math.max(element.from[2], element.to[2]) / 16.0));
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
