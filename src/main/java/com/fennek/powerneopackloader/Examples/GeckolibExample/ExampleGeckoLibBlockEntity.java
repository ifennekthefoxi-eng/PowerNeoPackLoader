package com.fennek.powerneopackloader.Examples.GeckolibExample;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

public class ExampleGeckoLibBlockEntity extends BlockEntity implements GeoBlockEntity {

    // Must match an animation name inside
    // assets/functional_default/animations/geckolib_example_block.animation.json - GeckoLib looks this up
    // by name at runtime, so a typo here just silently never plays instead of erroring.
    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    // The API will reflectively call this constructor and pass the dynamic type
    public ExampleGeckoLibBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void registerControllers(final AnimatableManager.ControllerRegistrar controllers) {
        // A single always-on looping controller is enough for this example. Split into more
        // controllers (e.g. "movement_controller", "action_controller") once this block needs
        // animations that can run independently of each other (idle vs. a one-shot open animation).
        controllers.add(new AnimationController<>(this, "idle_controller", 0, this::idlePredicate));
    }

    private PlayState idlePredicate(AnimationState<ExampleGeckoLibBlockEntity> state) {
        return state.setAndContinue(IDLE_ANIM);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }
}