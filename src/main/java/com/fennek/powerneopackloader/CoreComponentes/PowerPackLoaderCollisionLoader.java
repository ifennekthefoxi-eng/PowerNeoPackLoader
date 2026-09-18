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
import java.util.Locale;
import java.util.Map;

/**
 * Loads {@code data/<packId>/collisions/<blockId>.json} - the collision counterpart to
 * {@code assets/<packId>/models/block/<blockId>.json} (see {@code PowerPackLoaderModelGenerator}).
 * Directly ported from CACW's {@code EngineCollisionLoader}, with one difference: CACW looks
 * its shapes up by a runtime-selectable "EngineId" (many engines share one registered Block),
 * while every PNPL pack block IS its own registered Block, so the natural lookup key here is
 * simply that block's own registry name - no per-instance/block-entity state needed.
 * <p>
 * The file is a plain block-model json, exactly like the visual model: its {@code "elements"} are
 * read (each one's {@code from}/{@code to} and its {@code "rotation"}), while textures, faces,
 * display and the rest are ignored as meaningless for a collider.
 * <p>
 * An unrotated element becomes exactly the box it describes. A ROTATED element is rasterized onto a
 * voxel grid instead - see {@link #toBoxes} - because a {@link VoxelShape} is a union of
 * axis-aligned boxes and simply has no way to hold a rotated one.
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
                List<AABB> southBoxes = toBoxes(model, resolutionOf(model));
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

    /** Model-space units per block, along one axis - the 16 in "a 16x16x16 block model". */
    private static final double MODEL_UNITS = 16.0;

    /**
     * Voxel cells per block edge when rasterizing a rotated element. 16 puts one cell per model
     * unit, the same granularity the model itself is drawn on, which keeps the result recognisable
     * against the file and the box count sane. A pack can raise it per block with
     * {@code "collision_resolution"}.
     */
    private static final int DEFAULT_RESOLUTION = 16;

    /** Guard against a pack asking for a grid that would take meaningful time to fill and produce
     *  a VoxelShape with thousands of boxes in it. 64 is already 4x finer than the model grid. */
    private static final int MAX_RESOLUTION = 64;

    /**
     * Turns a model's {@code "elements"} into the boxes a {@link VoxelShape} is built from.
     *
     * <h2>Unrotated elements: exactly the file</h2>
     * An element with no rotation becomes precisely the box its {@code from}/{@code to} describe,
     * divided by 16. Nothing inferred, expanded, merged or snapped - so for the ordinary model,
     * reading the json tells you exactly what you will collide with. (The one liberty is
     * normalising min/max per axis, which repairs an element written back-to-front rather than
     * reinterpreting a valid one.)
     *
     * <h2>Rotated elements: rasterized</h2>
     * A {@link VoxelShape} is a union of AXIS-ALIGNED boxes - a rotated one does not exist - so a
     * rotated element cannot be represented directly, and the two obvious answers are both wrong:
     * ignoring the rotation leaves the box at the position the part occupied BEFORE it was rotated,
     * and taking the bounding box of the rotated corners is strictly bigger than the part, by ~40%
     * on two axes at 45 degrees.
     * <p>
     * So the rotated part is rasterized instead: the block is divided into a voxel grid, every cell
     * the rotated part actually overlaps is filled, and the filled cells are merged back into as
     * few boxes as possible. The same thing a renderer does turning a triangle into pixels - the
     * result is a stair-stepped approximation, but it is in the right PLACE and the right SIZE,
     * which neither alternative manages. Resolution is per block via {@code "collision_resolution"}.
     * <p>
     * Overlap is tested exactly, not by sampling points: a model element only ever rotates about a
     * single axis, so the test splits into an interval overlap along that axis and a 2D
     * separating-axis test in the plane - see {@link #cellOverlapsRotated}. Point sampling would
     * drop thin parts that pass between sample points.
     */
    private static List<AABB> toBoxes(@Nullable CollisionModel model, int resolution) {
        List<AABB> boxes = new ArrayList<>();
        if (model == null || model.elements == null) return boxes;

        List<CollisionModel.Element> rotated = new ArrayList<>();

        for (CollisionModel.Element element : model.elements) {
            if (element == null || element.from == null || element.to == null
                    || element.from.length != 3 || element.to.length != 3) continue;

            if (rotationOf(element) == null) {
                boxes.add(new AABB(
                        Math.min(element.from[0], element.to[0]) / MODEL_UNITS,
                        Math.min(element.from[1], element.to[1]) / MODEL_UNITS,
                        Math.min(element.from[2], element.to[2]) / MODEL_UNITS,
                        Math.max(element.from[0], element.to[0]) / MODEL_UNITS,
                        Math.max(element.from[1], element.to[1]) / MODEL_UNITS,
                        Math.max(element.from[2], element.to[2]) / MODEL_UNITS));
            } else {
                rotated.add(element);
            }
        }

        if (!rotated.isEmpty()) {
            boxes.addAll(rasterize(rotated, resolution));
        }
        return boxes;
    }

    /**
     * The voxel resolution to rasterize this model's rotated elements at, from its optional
     * {@code "collision_resolution"} key. Clamped to something sane: below the model's own 16-unit
     * grid the result stops resembling the shape, and far above it the box count and fill cost grow
     * with the cube of the number.
     */
    private static int resolutionOf(CollisionModel model) {
        if (model.collision_resolution <= 0) return DEFAULT_RESOLUTION;
        return Math.max(1, Math.min(MAX_RESOLUTION, model.collision_resolution));
    }

    /** An element's usable rotation, or null when it has none worth applying. */
    private static CollisionModel.Rotation rotationOf(CollisionModel.Element element) {
        CollisionModel.Rotation rotation = element.rotation;
        if (rotation == null || rotation.angle == 0 || rotation.axis == null
                || rotation.origin == null || rotation.origin.length != 3) {
            return null;
        }
        return switch (rotation.axis.toLowerCase(Locale.ROOT)) {
            case "x", "y", "z" -> rotation;
            default -> null; // Unrecognised axis - treat the element as unrotated rather than guess.
        };
    }

    /**
     * Fills a voxel grid with every cell the rotated elements overlap, then merges the filled cells
     * back into as few boxes as possible.
     * <p>
     * The grid is sized to the elements' own extent rather than to the block, so a part that hangs
     * outside the 0..16 cube still rasterizes correctly instead of being clipped.
     */
    private static List<AABB> rasterize(List<CollisionModel.Element> elements, int resolution) {
        double step = MODEL_UNITS / resolution;

        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (CollisionModel.Element element : elements) {
            double[] bounds = rotatedBounds(element);
            minX = Math.min(minX, bounds[0]); minY = Math.min(minY, bounds[1]); minZ = Math.min(minZ, bounds[2]);
            maxX = Math.max(maxX, bounds[3]); maxY = Math.max(maxY, bounds[4]); maxZ = Math.max(maxZ, bounds[5]);
        }

        int cellX0 = (int) Math.floor(minX / step), cellX1 = (int) Math.ceil(maxX / step);
        int cellY0 = (int) Math.floor(minY / step), cellY1 = (int) Math.ceil(maxY / step);
        int cellZ0 = (int) Math.floor(minZ / step), cellZ1 = (int) Math.ceil(maxZ / step);

        int countX = Math.max(0, cellX1 - cellX0);
        int countY = Math.max(0, cellY1 - cellY0);
        int countZ = Math.max(0, cellZ1 - cellZ0);
        if (countX == 0 || countY == 0 || countZ == 0) return List.of();

        boolean[][][] filled = new boolean[countX][countY][countZ];

        for (CollisionModel.Element element : elements) {
            double[] bounds = rotatedBounds(element);
            // Only the cells within this element's own rotated extent can possibly overlap it.
            int x0 = Math.max(cellX0, (int) Math.floor(bounds[0] / step));
            int y0 = Math.max(cellY0, (int) Math.floor(bounds[1] / step));
            int z0 = Math.max(cellZ0, (int) Math.floor(bounds[2] / step));
            int x1 = Math.min(cellX1, (int) Math.ceil(bounds[3] / step));
            int y1 = Math.min(cellY1, (int) Math.ceil(bounds[4] / step));
            int z1 = Math.min(cellZ1, (int) Math.ceil(bounds[5] / step));

            for (int cx = x0; cx < x1; cx++) {
                for (int cy = y0; cy < y1; cy++) {
                    for (int cz = z0; cz < z1; cz++) {
                        if (filled[cx - cellX0][cy - cellY0][cz - cellZ0]) continue;
                        if (cellOverlapsRotated(element, cx * step, cy * step, cz * step, step)) {
                            filled[cx - cellX0][cy - cellY0][cz - cellZ0] = true;
                        }
                    }
                }
            }
        }

        return mergeCells(filled, cellX0, cellY0, cellZ0, step);
    }

    /**
     * Greedily merges filled cells into boxes: grow along X, then along Y while the whole row
     * matches, then along Z while the whole slab matches.
     * <p>
     * Worth doing rather than emitting one box per cell, because every box is a separate volume
     * for {@code Shapes.or} to index and for collision to walk. A typical rotated part collapses
     * from hundreds of cells to a handful of boxes.
     */
    private static List<AABB> mergeCells(boolean[][][] filled, int cellX0, int cellY0, int cellZ0, double step) {
        int countX = filled.length, countY = filled[0].length, countZ = filled[0][0].length;
        boolean[][][] used = new boolean[countX][countY][countZ];
        List<AABB> boxes = new ArrayList<>();

        for (int x = 0; x < countX; x++) {
            for (int y = 0; y < countY; y++) {
                for (int z = 0; z < countZ; z++) {
                    if (!filled[x][y][z] || used[x][y][z]) continue;

                    int spanX = 1;
                    while (x + spanX < countX && filled[x + spanX][y][z] && !used[x + spanX][y][z]) spanX++;

                    int spanY = 1;
                    grow:
                    while (y + spanY < countY) {
                        for (int i = 0; i < spanX; i++) {
                            if (!filled[x + i][y + spanY][z] || used[x + i][y + spanY][z]) break grow;
                        }
                        spanY++;
                    }

                    int spanZ = 1;
                    growZ:
                    while (z + spanZ < countZ) {
                        for (int i = 0; i < spanX; i++) {
                            for (int j = 0; j < spanY; j++) {
                                if (!filled[x + i][y + j][z + spanZ] || used[x + i][y + j][z + spanZ]) break growZ;
                            }
                        }
                        spanZ++;
                    }

                    for (int i = 0; i < spanX; i++) {
                        for (int j = 0; j < spanY; j++) {
                            for (int k = 0; k < spanZ; k++) used[x + i][y + j][z + k] = true;
                        }
                    }

                    boxes.add(new AABB(
                            (cellX0 + x) * step / MODEL_UNITS,
                            (cellY0 + y) * step / MODEL_UNITS,
                            (cellZ0 + z) * step / MODEL_UNITS,
                            (cellX0 + x + spanX) * step / MODEL_UNITS,
                            (cellY0 + y + spanY) * step / MODEL_UNITS,
                            (cellZ0 + z + spanZ) * step / MODEL_UNITS));
                }
            }
        }
        return boxes;
    }

    /** The axis-aligned extent of an element once rotated - used only to bound the cell scan. */
    private static double[] rotatedBounds(CollisionModel.Element element) {
        double minA = Math.min(element.from[0], element.to[0]);
        double minB = Math.min(element.from[1], element.to[1]);
        double minC = Math.min(element.from[2], element.to[2]);
        double maxA = Math.max(element.from[0], element.to[0]);
        double maxB = Math.max(element.from[1], element.to[1]);
        double maxC = Math.max(element.from[2], element.to[2]);

        CollisionModel.Rotation rotation = rotationOf(element);
        if (rotation == null) {
            return new double[] {minA, minB, minC, maxA, maxB, maxC};
        }

        double[] out = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE,
                -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (int corner = 0; corner < 8; corner++) {
            double[] p = rotatePoint(rotation,
                    (corner & 1) == 0 ? minA : maxA,
                    (corner & 2) == 0 ? minB : maxB,
                    (corner & 4) == 0 ? minC : maxC);
            for (int axis = 0; axis < 3; axis++) {
                out[axis] = Math.min(out[axis], p[axis]);
                out[axis + 3] = Math.max(out[axis + 3], p[axis]);
            }
        }
        return out;
    }

    /**
     * Applies an element's rotation to a point, matching vanilla: a positive angle about the
     * positive axis, right-hand rule, exactly as {@code FaceBakery} rotates the element's corners.
     * {@code rescale} is honoured because vanilla grows the element that way too.
     */
    private static double[] rotatePoint(CollisionModel.Rotation rotation, double x, double y, double z) {
        double angle = Math.toRadians(rotation.angle);
        double sin = Math.sin(angle);
        double cos = Math.cos(angle);
        double scale = rotation.rescale ? 1.0 / Math.cos(angle) : 1.0;

        double ox = rotation.origin[0], oy = rotation.origin[1], oz = rotation.origin[2];
        double dx = x - ox, dy = y - oy, dz = z - oz;

        double nx = dx, ny = dy, nz = dz;
        switch (rotation.axis.toLowerCase(Locale.ROOT)) {
            case "x" -> { ny = (dy * cos - dz * sin) * scale; nz = (dy * sin + dz * cos) * scale; }
            case "y" -> { nx = (dx * cos + dz * sin) * scale; nz = (-dx * sin + dz * cos) * scale; }
            case "z" -> { nx = (dx * cos - dy * sin) * scale; ny = (dx * sin + dy * cos) * scale; }
            default -> { /* unreachable - rotationOf rejects other axes */ }
        }
        return new double[] {nx + ox, ny + oy, nz + oz};
    }

    /**
     * Whether a voxel cell overlaps a rotated element - exactly, with no sampling.
     * <p>
     * A model element rotates about ONE axis, so the 3D test splits cleanly: along the rotation
     * axis nothing moves, which is a plain interval overlap; in the perpendicular plane the element
     * is a rotated rectangle against the cell's square, which is a 2D separating-axis test over
     * four candidate axes. Exact matters here - sampling cell centres would drop any part thinner
     * than a cell, which is most of the detail in a typical model.
     */
    private static boolean cellOverlapsRotated(CollisionModel.Element element,
                                               double cellX, double cellY, double cellZ, double step) {
        CollisionModel.Rotation rotation = rotationOf(element);
        String axis = rotation.axis.toLowerCase(Locale.ROOT);

        double minX = Math.min(element.from[0], element.to[0]), maxX = Math.max(element.from[0], element.to[0]);
        double minY = Math.min(element.from[1], element.to[1]), maxY = Math.max(element.from[1], element.to[1]);
        double minZ = Math.min(element.from[2], element.to[2]), maxZ = Math.max(element.from[2], element.to[2]);

        // Split into "along the rotation axis" and "the plane it turns in".
        double alongMin, alongMax, cellAlong;
        double planeAMin, planeAMax, planeBMin, planeBMax, cellA, cellB, originA, originB;
        switch (axis) {
            case "x" -> {
                alongMin = minX; alongMax = maxX; cellAlong = cellX;
                planeAMin = minY; planeAMax = maxY; planeBMin = minZ; planeBMax = maxZ;
                cellA = cellY; cellB = cellZ;
                originA = rotation.origin[1]; originB = rotation.origin[2];
            }
            case "y" -> {
                alongMin = minY; alongMax = maxY; cellAlong = cellY;
                planeAMin = minX; planeAMax = maxX; planeBMin = minZ; planeBMax = maxZ;
                cellA = cellX; cellB = cellZ;
                originA = rotation.origin[0]; originB = rotation.origin[2];
            }
            default -> { // "z"
                alongMin = minZ; alongMax = maxZ; cellAlong = cellZ;
                planeAMin = minX; planeAMax = maxX; planeBMin = minY; planeBMax = maxY;
                cellA = cellX; cellB = cellY;
                originA = rotation.origin[0]; originB = rotation.origin[1];
            }
        }

        if (cellAlong + step <= alongMin || cellAlong >= alongMax) return false;

        double angle = Math.toRadians(rotation.angle);
        double sin = Math.sin(angle);
        double cos = Math.cos(angle);
        double scale = rotation.rescale ? 1.0 / Math.cos(angle) : 1.0;
        // Y turns the opposite way to X and Z in Minecraft's own element rotation.
        double signedSin = axis.equals("y") ? -sin : sin;

        double[] rectA = new double[4];
        double[] rectB = new double[4];
        double[] cornersA = {planeAMin, planeAMax, planeAMax, planeAMin};
        double[] cornersB = {planeBMin, planeBMin, planeBMax, planeBMax};
        for (int i = 0; i < 4; i++) {
            double da = cornersA[i] - originA;
            double db = cornersB[i] - originB;
            rectA[i] = originA + (da * cos - db * signedSin) * scale;
            rectB[i] = originB + (da * signedSin + db * cos) * scale;
        }

        double[] cellCornersA = {cellA, cellA + step, cellA + step, cellA};
        double[] cellCornersB = {cellB, cellB, cellB + step, cellB + step};

        return convexOverlap2D(rectA, rectB, cellCornersA, cellCornersB);
    }

    /**
     * Separating-axis test between two convex quads. They overlap unless some edge normal of either
     * separates them; touching exactly counts as not overlapping, so a cell merely flush against a
     * part isn't filled.
     */
    private static boolean convexOverlap2D(double[] ax, double[] ay, double[] bx, double[] by) {
        for (int poly = 0; poly < 2; poly++) {
            double[] px = poly == 0 ? ax : bx;
            double[] py = poly == 0 ? ay : by;
            for (int i = 0; i < 4; i++) {
                int next = (i + 1) & 3;
                // Edge normal: the edge direction turned 90 degrees.
                double nx = -(py[next] - py[i]);
                double ny = px[next] - px[i];
                if (nx == 0 && ny == 0) continue;

                double aMin = Double.MAX_VALUE, aMax = -Double.MAX_VALUE;
                double bMin = Double.MAX_VALUE, bMax = -Double.MAX_VALUE;
                for (int k = 0; k < 4; k++) {
                    double pa = ax[k] * nx + ay[k] * ny;
                    aMin = Math.min(aMin, pa);
                    aMax = Math.max(aMax, pa);
                    double pb = bx[k] * nx + by[k] * ny;
                    bMin = Math.min(bMin, pb);
                    bMax = Math.max(bMax, pb);
                }
                if (aMax <= bMin || bMax <= aMin) return false;
            }
        }
        return true;
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
        /** Optional voxel grid resolution for rotated elements - see {@link #resolutionOf}. */
        int collision_resolution;

        private static class Element {
            float[] from;
            float[] to;
            Rotation rotation;
        }

        /** A vanilla model element's rotation gizmo - what Blockbench writes for a rotated cube. */
        private static class Rotation {
            float angle;
            String axis;
            float[] origin;
            boolean rescale;
        }
    }
}
