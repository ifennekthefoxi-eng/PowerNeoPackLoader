package com.fennek.powerneopackloader.CoreComponentes;

import java.util.HashMap;
import java.util.Map;

/**
 * Block entity class -&gt; {@code BlockEntityRenderer} class, the third member of the
 * {@link ClassPicker}/{@link EntityPicker}/{@link ItemPicker} family, and the one that decides
 * whether a pack block whose visual lives in a renderer is visible at all.
 * <p>
 * Registering a {@code BlockEntityType} and registering a renderer FOR it are separate registries
 * in vanilla, and every pack block gets its own freshly-created type - so no pack block ever
 * inherits an existing renderer registration, and one with no entry here renders as nothing while
 * being otherwise completely functional. {@code PowerPackLoaderClientEvents} reads this map at
 * {@code EntityRenderersEvent.RegisterRenderers} time and performs that registration.
 *
 * <h2>Why this stores classes, not factories</h2>
 * Values are raw {@link Class} objects rather than
 * {@code Function<BlockEntityRendererProvider.Context, BlockEntityRenderer<?>>} factories on
 * purpose: {@code BlockEntityRendererProvider} is a client-only type, and this class is reachable
 * from common code (the scanner populates it, the register step is handed it). Naming a client type
 * anywhere in this file's signatures would make it unloadable on a dedicated server. The client-only
 * {@code PowerPackLoaderClientEvents} does the actual reflective construction, where client types
 * are safe to touch.
 * <p>
 * For the same reason this map is only ever POPULATED on the client (see
 * {@code PackAnnotationScanner}) - resolving a renderer class name to a {@code Class} on a
 * dedicated server would fail on the first client type in its hierarchy.
 */
public class RendererPicker {

    /**
     * The one picker every mod's renderers land in.
     * <p>
     * Unlike the block/entity/item pickers, this one is shared rather than per-mod, because the
     * thing that reads it is shared: {@code EntityRenderersEvent.RegisterRenderers} is handled once
     * by this library for every pack block that exists, from any mod. Keys are block entity
     * CLASSES, which are already globally unique, so there is nothing for two mods to collide over.
     * <p>
     * A modder can add to this directly - {@code RendererPicker.GLOBAL.AddRenderer(MyBE.class,
     * MyRenderer.class)} - for a renderer that can't be declared with {@code @PackRenderer} (one
     * built at runtime, or for an entity class from another mod). Do it from client-side setup, and
     * before {@code RegisterRenderers} fires.
     */
    public static final RendererPicker GLOBAL = new RendererPicker();

    /** Guarded by {@code this} - {@link #GLOBAL} is written by several mods' constructors at once,
     *  since FML constructs mods in parallel, and read later from the client's renderer
     *  registration. */
    private final Map<Class<?>, Class<?>> rendererRegistry = new HashMap<>();

    /**
     * Maps {@code entityClass} to {@code rendererClass}, unless that entity class already has a
     * renderer - first registration wins, matching {@link EntityPicker}/{@link ItemPicker}, so an
     * explicit early mapping can't be clobbered by a later scan.
     */
    public synchronized void AddRenderer(Class<?> entityClass, Class<?> rendererClass) {
        if (getRendererByEntityClass(entityClass) == null) {
            rendererRegistry.put(entityClass, rendererClass);
        }
    }

    /**
     * The renderer class mapped to {@code entityClass}, or null if none - in which case the
     * caller falls back to whatever built-in handling fits the entity class (a vanilla
     * {@code ChestRenderer}, GeckoLib's {@code GeoBlockRenderer}, or nothing at all).
     */
    public synchronized Class<?> getRendererByEntityClass(Class<?> entityClass) {
        return rendererRegistry.get(entityClass);
    }

    public synchronized boolean isEmpty() {
        return rendererRegistry.isEmpty();
    }
}
