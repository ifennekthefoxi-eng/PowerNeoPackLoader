package com.fennek.powerneopackloader.Examples.GeckolibExample;

import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.item.BlockItem;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.animatable.SingletonGeoAnimatable;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.animatable.client.GeoRenderProvider;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.function.Consumer;

/**
 * The GeckoLib counterpart to {@link ExampleGeckoLibBlock}'s vanilla-side registration: without this
 * class, {@code PowerNeoPackLoaderRegister} falls back to a plain {@code BlockItem} for i4_engine,
 * which CAN'T show the animated geo model - only ever a flat 2D icon at best (see
 * {@code PowerPackLoaderModelGenerator}'s convention-texture fallback) or, with no item model
 * json at all, the same missing/checker placeholder the BLOCK had before its own fix. "Using the
 * default Minecraft item" isn't a bug in the strict sense (it places fine, stacks fine, nothing
 * crashes) - it's just the wrong renderer for a GeckoLib-backed block's item: a vanilla
 * {@code BlockItem} never even looks at the {@link ExampleGeckoLibBlockBlockModel} the actual block
 * uses, so the two ends up visually disconnected from each other.
 * <p>
 * Mapped to {@link ExampleGeckoLibBlock} by that class's {@code @PackBlock(item = ...)} - see
 * {@code ItemPicker}'s class doc for why the mapping exists at all, and
 * {@code PowerNeoPackLoaderRegister} for how it's consumed (reflective
 * {@code (Block, Item.Properties)} construction, same pattern as the block/entity classes).
 */
public class ExampleGeckoLibBlockItem extends BlockItem implements GeoItem {

    // Reuses the SAME animation name as ExampleChestBlockEntity's idle controller - both draw
    // from the same geo/animation/texture triplet (see ExampleChestBlockItemModel), so there's
    // nothing item-specific to author here unless this item wants its own distinct held-item
    // animation later.
    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    public ExampleGeckoLibBlockItem(Block block, Item.Properties properties) {
        super(block, properties);
        // Minecraft only ever keeps ONE instance of a given Item (unlike entities/block entities,
        // which get one instance per placement) - this registers it as a "synced animatable" so
        // GeckoLib can correctly track animation state per-ItemStack instead of mixing up every
        // held/dropped copy of this item into one shared animation state.
        SingletonGeoAnimatable.registerSyncedAnimatable(this);
    }

    // 1.21.1+ NeoForge hook (matches the GeckoLib 4.9.2 build this mod ships) - GeckoLib calls
    // this itself to find the renderer; nothing else needs to register it separately the way
    // PowerPackLoaderClientEvents has to for block entity renderers.
    @Override
    public void createGeoRenderer(Consumer<GeoRenderProvider> consumer) {
        consumer.accept(new GeoRenderProvider() {
            private ExampleGeckoLibBlockItemRenderer renderer;

            @Override
            public BlockEntityWithoutLevelRenderer getGeoItemRenderer() {
                if (this.renderer == null) {
                    this.renderer = new ExampleGeckoLibBlockItemRenderer();
                }
                return this.renderer;
            }
        });
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "idle_controller", 0, this::idlePredicate));
    }

    private PlayState idlePredicate(AnimationState<ExampleGeckoLibBlockItem> state) {
        return state.setAndContinue(IDLE_ANIM);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}
