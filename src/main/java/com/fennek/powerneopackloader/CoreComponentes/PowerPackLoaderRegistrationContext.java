package com.fennek.powerneopackloader.CoreComponentes;

import com.fennek.powerneopackloader.APIBridge.PackBlockContext;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * The original, entity-type-only view of what is now {@link PackBlockContext}.
 * <p>
 * Kept so block classes written against it keep compiling and behaving identically; everything it
 * offers is a strict subset of {@link PackBlockContext}, which also carries the block's own pack
 * json, its pack and block ids, and its pack folder. New code should use that directly:
 * <pre>{@code
 * // then
 * super(PowerPackLoaderRegistrationContext.resolveEntityTypeSupplier(() -> BlockEntityType.CHEST));
 * // now
 * super(PackBlockContext.current()
 *         .map(c -> c.<ChestBlockEntity>blockEntityTypeSupplier(() -> BlockEntityType.CHEST))
 *         .orElse(() -> BlockEntityType.CHEST));
 * }</pre>
 *
 * @deprecated use {@link PackBlockContext} instead.
 */
@Deprecated
public final class PowerPackLoaderRegistrationContext {

    private PowerPackLoaderRegistrationContext() {}

    /**
     * No longer does anything: the loader now publishes a whole {@link PackBlockContext} (which
     * includes the entity type id) around block construction, so there is no separate id to set.
     *
     * @deprecated the loader manages the context itself; calling this has no effect.
     */
    @Deprecated
    public static void setCurrentEntityTypeId(@Nullable ResourceLocation id) {
    }

    /**
     * No longer does anything - see {@link #setCurrentEntityTypeId}.
     *
     * @deprecated the loader manages the context itself; calling this has no effect.
     */
    @Deprecated
    public static void clearCurrentEntityTypeId() {
    }

    /**
     * For use inside a block constructor's {@code super(...)} call: a supplier that lazily resolves
     * the {@link BlockEntityType} being registered for the block currently under construction,
     * falling back to {@code fallback} when there is none (or when this class is constructed
     * outside the loader entirely).
     *
     * @deprecated use {@link PackBlockContext#blockEntityTypeSupplier(Supplier)}.
     */
    @Deprecated
    public static <T extends BlockEntity> Supplier<BlockEntityType<? extends T>> resolveEntityTypeSupplier(
            Supplier<BlockEntityType<? extends T>> fallback) {
        return PackBlockContext.current()
                .map(context -> context.<T>blockEntityTypeSupplier(fallback))
                .orElse(fallback);
    }
}
