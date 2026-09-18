package com.fennek.powerneopackloader.APIBridge.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code BlockEntityRenderer} as the renderer for {@link #value()}'s block entity class.
 * The standalone form of {@code @PackBlock(renderer = ...)}.
 * <p>
 * <b>This is not optional decoration - without it, a pack block whose visual comes from a block
 * entity renderer draws nothing at all.</b> Registering a {@code BlockEntityType} and registering a
 * renderer FOR that type are two entirely separate registries in vanilla; the block, its entity and
 * its type can all be registered and ticking correctly while the block renders as empty space (or
 * as the missing-model checkerboard). Since every pack block gets its own freshly-registered
 * {@code BlockEntityType}, it can never inherit a renderer that vanilla or another mod wired up for
 * some other type - it needs its own registration, which this annotation performs at
 * {@code EntityRenderersEvent.RegisterRenderers} time. See {@code PowerPackLoaderClientEvents}.
 * <p>
 * Blocks drawn from an ordinary baked model (blockstate json + textures) need nothing here.
 *
 * <h2>Constructors</h2>
 * Needs a {@code (BlockEntityRendererProvider.Context)} constructor - the shape every vanilla
 * {@code BlockEntityRenderer} already uses. Non-public is fine.
 *
 * <h2>Client-only classes</h2>
 * Renderers live on the client only, and so may this class. Both this annotation and
 * {@code @PackBlock(renderer = ...)} are read from NeoForge's annotation scan data as plain
 * strings, and resolved to a real {@code Class} only on the client, so a dedicated server never
 * loads a renderer class. The annotated class itself is still scanned on both sides (that's just
 * reading bytes), which is why a {@code @PackRenderer} class must never be referenced from
 * common-side code.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface PackRenderer {

    /** The block entity class this renderer draws. */
    Class<?> value();
}
