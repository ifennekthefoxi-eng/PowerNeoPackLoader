package com.fennek.powerneopackloader.Examples;

import com.mojang.serialization.MapCodec;
import com.fennek.powerneopackloader.PowerNeoPackLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jetbrains.annotations.Nullable;

/**
 * Second worked example for the loader API, deliberately picked as a contrast to
 * {@code ChestLoadingExample}: it extends vanilla's own {@link FurnaceBlock} the same way
 * {@code ChestLoadingExample} extends {@link net.minecraft.world.level.block.ChestBlock}, hits the
 * exact same "vanilla's block-entity class hardcodes a shared BlockEntityType" trap (see
 * {@link LoadableFurnaceBlockEntity}), and needs the exact same {@code newBlockEntity} override
 * pattern - but it needs NO entry in {@code PowerPackLoaderClientEvents}, because furnaces render
 * from a normal baked block model, not a {@code BlockEntityRenderer}. If a texture/lit-state issue
 * ever comes up for a pack furnace, look at the blockstate/model JSON first; if a pack chest looks
 * wrong, look at the client renderer registration first. Same loader, two different rendering
 * paths depending entirely on what the vanilla block being extended actually does.
 * <p>
 * Blockstate note: like {@code ChestBlock}, {@code FurnaceBlock} isn't assignable to
 * {@link HorizontalDirectionalBlock}, so {@code PowerPackLoaderModelGenerator}'s auto-generated
 * FACING-variant fallback doesn't apply here either - AND on top of that, the auto-generator has no
 * concept of the {@code LIT} boolean property at all. A pack using this class must ship its own
 * hand-authored {@code assets/<packId>/blockstates/<blockId>.json} with both FACING and LIT
 * variants (four rotations x lit/unlit = 8 variants), each pointing at a model with the
 * appropriate lit/unlit texture.
 */
public class FurnaceLoadingExample extends FurnaceBlock {

    public static final DeferredRegister<MapCodec<? extends Block>> CODECS =
            DeferredRegister.create(BuiltInRegistries.BLOCK_TYPE, PowerNeoPackLoader.MOD_ID);

    public static final DeferredHolder<MapCodec<? extends Block>, MapCodec<FurnaceLoadingExample>> CODEC =
            CODECS.register("loadable_furnace_example", () -> simpleCodec(FurnaceLoadingExample::new));

    // Reflection-instantiated via getConstructor(Properties.class), same as every other pack block
    // class - unlike ChestBlock, FurnaceBlock's own constructor takes just Properties, no
    // block-entity-type supplier, so there's no PowerPackLoaderRegistrationContext plumbing needed
    // here at construction time. The fix instead lives entirely in newBlockEntity/getTicker below.
    public FurnaceLoadingExample(Properties properties) {
        super(properties);
    }

    // Unlike ChestBlock, which leaves codec() ABSTRACT with a wildcard return
    // (`MapCodec<? extends ChestBlock>`) precisely so subclasses can plug in their own codec type,
    // FurnaceBlock already provides a CONCRETE codec() that returns the exact type
    // `MapCodec<FurnaceBlock>` - not a wildcard. Java's covariant-return-type rule requires the
    // override's return type to be a real subtype of the overridden one, and since generics are
    // invariant, `MapCodec<FurnaceLoadingExample>` is NOT a subtype of `MapCodec<FurnaceBlock>` -
    // so the override has to return the exact same type FurnaceBlock declared, not our own
    // narrower one. The double cast below is the standard way to get there: CODEC.get() really is
    // a MapCodec<FurnaceLoadingExample> (built from `FurnaceLoadingExample::new` above), and every
    // FurnaceLoadingExample really is a FurnaceBlock, so treating it as `MapCodec<FurnaceBlock>` is
    // safe at runtime even though the compiler can't verify it directly - hence routing through the
    // raw `MapCodec<?>` first to sidestep the unchecked-cast error.
    @SuppressWarnings("unchecked")
    @Override
    public MapCodec<FurnaceBlock> codec() {
        return (MapCodec<FurnaceBlock>) (MapCodec<?>) CODEC.get();
    }

    // Same fix as ChestLoadingExample#newBlockEntity, and needed for the same reason: vanilla
    // FurnaceBlock.newBlockEntity hardcodes `new FurnaceBlockEntity(pos, state)`, whose public
    // constructor always sets the entity's own type to vanilla's shared BlockEntityType.FURNACE.
    // Routing through this block's OWN registered type instead - via LoadableFurnaceBlockEntity,
    // which accepts it directly - is what avoids the "Invalid block entity minecraft:furnace"
    // crash.
    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        ResourceLocation registryId = BuiltInRegistries.BLOCK.getKey(this);
        BlockEntityType<?> ownType = BuiltInRegistries.BLOCK_ENTITY_TYPE.get(registryId);
        if (ownType != null) {
            return new LoadableFurnaceBlockEntity(ownType, pos, state);
        }
        PowerNeoPackLoader.LOGGER.error(
                "No matching BlockEntityType registered for furnace block {} - falling back to vanilla BlockEntityType.FURNACE, which will likely crash on placement.",
                registryId);
        return super.newBlockEntity(pos, state);
    }

    // A SECOND hardcoded-type trap that ChestBlock doesn't have: vanilla FurnaceBlock.getTicker
    // compares the type it's handed against BlockEntityType.FURNACE (via the inherited
    // createFurnaceTicker helper) to decide whether to hand back a server ticker at all. Since our
    // own type is never equal to vanilla's FURNACE type, that comparison would silently fail and
    // the furnace would sit there doing nothing - no smelting, ever, with no error or crash to
    // point at. Overriding this to compare against OUR OWN type instead is what makes it actually
    // cook items.
    @SuppressWarnings("unchecked")
    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        ResourceLocation registryId = BuiltInRegistries.BLOCK.getKey(this);
        BlockEntityType<?> ownType = BuiltInRegistries.BLOCK_ENTITY_TYPE.get(registryId);
        if (ownType == null) {
            return null;
        }
        return createFurnaceTicker(level, type, (BlockEntityType<? extends AbstractFurnaceBlockEntity>) ownType);
    }
}
