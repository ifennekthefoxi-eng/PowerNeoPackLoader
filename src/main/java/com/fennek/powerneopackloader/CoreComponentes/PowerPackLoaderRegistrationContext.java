package com.fennek.powerneopackloader.CoreComponentes;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.function.Supplier;

/**
 * Bridges {@code PowerNeoPackLoaderRegister}'s reflective block construction
 * ({@code targetBlockClass.getConstructor(Properties.class).newInstance(...)}) with block classes
 * that need to know, at construction time, which {@link BlockEntityType} PowerNeoPackLoaderRegister
 * is about to register alongside them (e.g. {@code ChestLoadingExample}, which must hand its own
 * dynamically-registered type - not vanilla's shared {@code BlockEntityType.CHEST} - to
 * {@code ChestBlock}'s constructor).
 * <p>
 * A block subclass can't just capture {@code this} in a lambda passed up to {@code super(...)} -
 * the object doesn't exist yet at that point, and the compiler rejects it. This sidesteps that by
 * having {@code PowerNeoPackLoaderRegister} stash the target registry name in a {@link ThreadLocal}
 * immediately before it reflectively constructs the block, so the block's constructor can read
 * "what id am I being registered as" without ever needing a reference to itself.
 * <p>
 * Block registration in this loader runs single-threaded and each block's constructor call
 * completes fully before the next one starts, so a plain {@link ThreadLocal} (cleared in a
 * {@code finally} block by the caller) is enough - no risk of one pack block reading another's id.
 */
public final class PowerPackLoaderRegistrationContext {

    private static final ThreadLocal<ResourceLocation> CURRENT_ENTITY_TYPE_ID = new ThreadLocal<>();

    private PowerPackLoaderRegistrationContext() {}

    /** Called by PowerNeoPackLoaderRegister right before it reflectively constructs a block. */
    public static void setCurrentEntityTypeId(ResourceLocation id) {
        CURRENT_ENTITY_TYPE_ID.set(id);
    }

    /** Called by PowerNeoPackLoaderRegister right after, in a finally block, regardless of outcome. */
    public static void clearCurrentEntityTypeId() {
        CURRENT_ENTITY_TYPE_ID.remove();
    }

    /**
     * For use inside a block constructor's {@code super(...)} call. Captures whichever id was set
     * by PowerNeoPackLoaderRegister for THIS construction (reading the ThreadLocal now, before
     * returning), then returns a supplier that lazily resolves that id against the block entity
     * type registry each time it's asked - by which point PowerNeoPackLoaderRegister will have
     * finished registering it.
     * <p>
     * Falls back to {@code fallback} if no id was set (e.g. someone constructs this block class
     * directly rather than through the pack loader) or if nothing is registered under that id yet
     * when resolved.
     */
    @SuppressWarnings("unchecked")
    public static <T extends BlockEntity> Supplier<BlockEntityType<? extends T>> resolveEntityTypeSupplier(
            Supplier<BlockEntityType<? extends T>> fallback) {
        ResourceLocation id = CURRENT_ENTITY_TYPE_ID.get();
        if (id == null) {
            return fallback;
        }
        return () -> {
            BlockEntityType<?> type = BuiltInRegistries.BLOCK_ENTITY_TYPE.get(id);
            if (type == null) {
                return fallback.get();
            }
            return (BlockEntityType<? extends T>) type;
        };
    }
}
