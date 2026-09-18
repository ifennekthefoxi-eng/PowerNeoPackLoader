package com.fennek.powerneopackloader.APIBridge.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code Block} subclass as loadable from a pack. This is the ONLY thing a modder has to
 * do to make one of their own block classes usable from pack json - no manual
 * {@code ClassPicker.AddClass(...)} call, no list to keep in sync, no registration boilerplate
 * anywhere in their mod's main class.
 * <p>
 * The library finds every class carrying this annotation through NeoForge's own mod-file
 * annotation scan (the same mechanism {@code @Mod} and {@code @EventBusSubscriber} use), so
 * discovery costs nothing at runtime and never needs a classpath walk - see
 * {@code PackAnnotationScanner}. Only classes belonging to the mod file that called
 * {@code PowerPackLoaderApi.load(...)} are picked up, so two mods can safely use the same
 * {@link #id()} without colliding.
 * <p>
 * A pack's block json then refers to this class by {@link #id()}:
 * <pre>{@code
 * // MyEngineBlock.java
 * @PackBlock
 * public class MyEngineBlock extends Block {
 *     public MyEngineBlock(BlockBehaviour.Properties properties) { super(properties); }
 * }
 *
 * // data/<packId>/blocks/v8_engine.json
 * { "name": "V8 Engine", "class": "MyEngineBlock" }
 * }</pre>
 *
 * <h2>Constructors</h2>
 * The block is constructed reflectively, trying these signatures in order (first match wins):
 * <ol>
 *     <li>{@code (BlockBehaviour.Properties, PackBlockContext)} - use this when the block needs to
 *     read its own json (custom fields, per-block stats, ...); see {@code PackBlockContext};</li>
 *     <li>{@code (BlockBehaviour.Properties)} - the normal vanilla block constructor shape;</li>
 *     <li>{@code ()} - a no-arg constructor, for classes that build their own properties.</li>
 * </ol>
 * A non-public constructor is fine - the loader uses {@code getDeclaredConstructor}. Even with
 * signature 2 or 3 the block can still read {@code PackBlockContext.current()} from inside its
 * constructor (including from within a {@code super(...)} argument), which is how a block that
 * must hand its own {@code BlockEntityType} up to a vanilla superclass gets at it.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface PackBlock {

    /**
     * The name packs use in their block json's {@code "class"} field. Defaults to the class's
     * simple name (so {@code MyEngineBlock} is referred to as {@code "MyEngineBlock"}), which is
     * almost always what you want - set this only to expose a different/shorter name, or to keep
     * an old pack working after renaming the class.
     */
    String id() default "";

    /**
     * The {@code BlockEntity} subclass to pair with this block, if it needs one. Equivalent to
     * putting {@code @PackBlockEntity(ThisBlock.class)} on that entity class - declaring the whole
     * family here keeps everything in one place, the separate annotation is for when you don't
     * control the block class.
     * <p>
     * Every pack block using this class gets its OWN dedicated {@code BlockEntityType} registered
     * for it (a shared one can't work - vanilla validates the placed block against the type's
     * valid-blocks list), so the entity class needs either a
     * {@code (BlockEntityType<?>, BlockPos, BlockState)} constructor, or a plain
     * {@code (BlockPos, BlockState)} one if it hardcodes nothing type-specific.
     */
    Class<?> entity() default void.class;

    /**
     * The {@code BlockItem} subclass to register for this block instead of a plain
     * {@code BlockItem}. Needs a {@code (Block, Item.Properties)} constructor (optionally with a
     * trailing {@code PackBlockContext}). Equivalent to {@code @PackBlockItem(ThisBlock.class)} on
     * that item class.
     */
    Class<?> item() default void.class;

    /**
     * The {@code BlockEntityRenderer} to draw {@link #entity()} with. Needs a
     * {@code (BlockEntityRendererProvider.Context)} constructor. Equivalent to
     * {@code @PackRenderer(ThisEntity.class)} on the renderer class.
     * <p>
     * Safe to name a client-only class here: the loader reads this value out of NeoForge's
     * annotation scan data (a plain string, never a live {@code Class}) and only ever resolves it
     * on the client, so a dedicated server never loads the renderer class.
     */
    Class<?> renderer() default void.class;
}
