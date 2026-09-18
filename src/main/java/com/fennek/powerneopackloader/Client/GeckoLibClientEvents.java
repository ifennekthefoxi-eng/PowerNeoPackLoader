package com.fennek.powerneopackloader.Client;

import com.fennek.powerneopackloader.PowerNeoPackLoader;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import software.bernie.geckolib.animatable.GeoBlockEntity;

/**
 * The GeckoLib-aware tail of {@link PowerPackLoaderClientEvents}'s renderer dispatch, kept in its
 * own class so that class only ever loads it behind a {@code GeckoLibCompat.LOADED} check - a
 * direct GeckoLib reference in {@link PowerPackLoaderClientEvents} itself would make its static
 * initializer resolve GeckoLib types on every install, including ones without GeckoLib.
 * <p>
 * There is nothing to register here: GeckoLib renderers are declared like any other, with
 * {@code @PackRenderer} or {@code @PackBlock(renderer = ...)}, and the caller has already tried
 * that before reaching this class. What's left is the diagnosis - a {@code GeoBlockEntity} with no
 * declared renderer is a specific, easy mistake with a completely silent symptom (the block works
 * in every respect but draws nothing at all, because GeckoLib entities have no baked model to fall
 * back on), so it gets a message naming the class instead of nothing.
 */
final class GeckoLibClientEvents {

    private GeckoLibClientEvents() {
    }

    /**
     * Reports a GeckoLib block entity that reached renderer registration without one. Only ever
     * called once the caller has confirmed GeckoLib is loaded and found no declared renderer.
     *
     * @return true if {@code entityClass} was a GeckoLib entity (whether or not anything could be
     *         done about it), so the caller can tell "handled" from "not mine".
     */
    static boolean tryRegister(EntityRenderersEvent.RegisterRenderers event, BlockEntityType<?> ownType,
                               Class<?> entityClass, ResourceLocation registryId) {
        if (!GeoBlockEntity.class.isAssignableFrom(entityClass)) {
            return false;
        }

        PowerNeoPackLoader.LOGGER.warn(
                "{} is a GeckoLib block entity with no renderer, so {} will render as nothing. "
                        + "Add @PackRenderer(pointing at a GeoBlockRenderer) to your renderer class, "
                        + "or renderer = ... to the block's @PackBlock.",
                entityClass.getName(), registryId);
        return true;
    }
}
