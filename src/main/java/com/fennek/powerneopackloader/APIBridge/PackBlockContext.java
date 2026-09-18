package com.fennek.powerneopackloader.APIBridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Everything the loader knows about the one pack block it is constructing right now: which pack it
 * came from, what it's being registered as, and - the part that makes data-driven blocks actually
 * possible - the block's own json, unparsed and complete.
 * <p>
 * This is what turns "the library registers your class" into "the library registers your class
 * WITH its pack's data". A single {@code @PackBlock} class can back any number of distinct pack
 * blocks, each with different stats/behaviour, by reading its own {@link #definition()}:
 * <pre>{@code
 * @PackBlock
 * public class MyEngineBlock extends Block {
 *     private final int power;
 *
 *     public MyEngineBlock(BlockBehaviour.Properties properties, PackBlockContext context) {
 *         super(properties);
 *         this.power = context.getInt("power", 100);
 *     }
 * }
 * }</pre>
 * Declaring that second parameter is all it takes - see {@code @PackBlock} for the full list of
 * constructor shapes the loader tries.
 *
 * <h2>Reading it without a constructor parameter</h2>
 * {@link #current()} returns the same object anywhere inside the block's (or its item's)
 * construction, including from within a {@code super(...)} argument expression, where {@code this}
 * doesn't exist yet and a constructor parameter can't be captured in a lambda. That is the only
 * way a block class can hand its own about-to-be-registered {@code BlockEntityType} up to a vanilla
 * superclass that demands one - see {@link #blockEntityTypeSupplier(Supplier)}.
 *
 * @param modId             the mod this block is registered under - the real namespace of its
 *                          block/item/block-entity ids, NOT the pack's asset namespace.
 * @param packId            the pack's own id from its meta json. Also the namespace the pack's
 *                          assets (models, textures, lang) live under.
 * @param blockId           the block definition's id, which is always its json FILENAME without
 *                          the extension (a file {@code blocks/v8_engine.json} gives
 *                          {@code "v8_engine"}) - never the free-text {@code "name"} display
 *                          field. Every asset path the library resolves is keyed off this.
 * @param registryName      what the block and item are actually registered as:
 *                          {@code <packId>_<blockId>}.
 * @param blockEntityTypeId the id of the {@code BlockEntityType} being registered alongside this
 *                          block, or null if this block has no mapped block entity. The type
 *                          itself does not exist yet while the block is being constructed, which
 *                          is why this is an id and not the object.
 * @param definition        the block's json, exactly as the pack author wrote it. Custom fields
 *                          are yours to read; the library only looks at {@code "class"},
 *                          {@code "name"}, {@code "geckolib"} and {@code "properties"}.
 * @param packFolder        the pack's own root folder on disk, for reading sibling files the
 *                          block json points at.
 */
public record PackBlockContext(String modId,
                               String packId,
                               String blockId,
                               String registryName,
                               @Nullable ResourceLocation blockEntityTypeId,
                               JsonObject definition,
                               Path packFolder) {

    /**
     * The context for the construction happening on this thread, or null outside one.
     * <p>
     * Registration is single-threaded and each block's construction completes before the next one
     * starts, so a plain {@link ThreadLocal} is enough here - there's no way for one pack block to
     * observe another's context. The loader clears it in a {@code finally} block regardless of
     * outcome.
     */
    private static final ThreadLocal<PackBlockContext> CURRENT = new ThreadLocal<>();

    /**
     * The block currently being constructed by the loader, or {@link Optional#empty()} if this
     * class is being constructed some other way (directly, by another mod, in a test, ...) - which
     * is exactly why this returns an Optional rather than a bare nullable: a {@code @PackBlock}
     * class must still work when someone news it up by hand, so always supply a default.
     */
    public static Optional<PackBlockContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** Called by the loader immediately before it reflectively constructs a block or its item. */
    public static void setCurrent(@Nullable PackBlockContext context) {
        if (context == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(context);
        }
    }

    /** Called by the loader immediately after, in a {@code finally} block. */
    public static void clearCurrent() {
        CURRENT.remove();
    }

    /**
     * A supplier that lazily resolves {@link #blockEntityTypeId()} against the block entity type
     * registry, for handing to a superclass constructor that demands a
     * {@code Supplier<BlockEntityType<? extends T>>}.
     * <p>
     * Lazy on purpose: the type is registered moments AFTER the block object exists (the block has
     * to exist first, to be listed as one of the type's valid blocks), so anything that resolves it
     * eagerly at construction time necessarily gets nothing. By the time a placed block actually
     * asks for its entity type, registration is long finished.
     *
     * @param fallback used when this block has no mapped entity type, or when nothing is
     *                 registered under its id yet at resolve time.
     */
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> Supplier<BlockEntityType<? extends T>> blockEntityTypeSupplier(
            Supplier<BlockEntityType<? extends T>> fallback) {
        ResourceLocation id = this.blockEntityTypeId;
        if (id == null) {
            return fallback;
        }
        return () -> {
            BlockEntityType<?> type = BuiltInRegistries.BLOCK_ENTITY_TYPE.get(id);
            return type == null ? fallback.get() : (BlockEntityType<? extends T>) type;
        };
    }

    // =========================================================================
    // Convenience readers over `definition` - the same null-safe "value or default" shape for
    // each json type, so a block class never has to write the has()/get()/getAs() dance itself.
    // =========================================================================

    public boolean has(String key) {
        return definition.has(key) && !definition.get(key).isJsonNull();
    }

    public String getString(String key, String fallback) {
        return has(key) ? definition.get(key).getAsString() : fallback;
    }

    public int getInt(String key, int fallback) {
        return has(key) ? definition.get(key).getAsInt() : fallback;
    }

    public float getFloat(String key, float fallback) {
        return has(key) ? definition.get(key).getAsFloat() : fallback;
    }

    public double getDouble(String key, double fallback) {
        return has(key) ? definition.get(key).getAsDouble() : fallback;
    }

    public boolean getBoolean(String key, boolean fallback) {
        return has(key) ? definition.get(key).getAsBoolean() : fallback;
    }

    /** A nested json object from the definition (e.g. a {@code "gears"} block of your own). */
    public Optional<JsonObject> getObject(String key) {
        if (!has(key) || !definition.get(key).isJsonObject()) {
            return Optional.empty();
        }
        return Optional.of(definition.getAsJsonObject(key));
    }

    /** Any raw element, for fields the typed readers above don't cover (arrays, mixed types). */
    public Optional<JsonElement> get(String key) {
        return has(key) ? Optional.of(definition.get(key)) : Optional.empty();
    }

    /** The block/item's full registry id: {@code <modId>:<registryName>}. */
    public ResourceLocation registryId() {
        return ResourceLocation.fromNamespaceAndPath(modId, registryName);
    }

    /**
     * Resolves a path inside this pack's own asset namespace, e.g.
     * {@code assetPath("textures/block/" + blockId() + ".png")}. Returns the path whether or not
     * it exists - check with {@code Files.exists} yourself.
     */
    public Path assetPath(String relativePath) {
        return packFolder.resolve("assets").resolve(packId).resolve(relativePath);
    }

    /** The same, for the pack's data namespace ({@code data/<packId>/...}). */
    public Path dataPath(String relativePath) {
        return packFolder.resolve("data").resolve(packId).resolve(relativePath);
    }
}
