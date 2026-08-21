package com.fennek.powerneopackloader.CoreComponentes;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.minecraft.core.registries.BuiltInRegistries;
import com.fennek.powerneopackloader.PowerNeoPackLoader;

public class LoadableModelHorizontalDirectionalBlock extends HorizontalDirectionalBlock {

    public static final DeferredRegister<MapCodec<? extends Block>> CODECS =
            DeferredRegister.create(BuiltInRegistries.BLOCK_TYPE, PowerNeoPackLoader.MOD_ID);

    public static final DeferredHolder<MapCodec<? extends Block>, MapCodec<LoadableModelHorizontalDirectionalBlock>> CODEC =
            CODECS.register("loadable_model_horizontal", () -> simpleCodec(LoadableModelHorizontalDirectionalBlock::new));

    public LoadableModelHorizontalDirectionalBlock(Properties properties) {
        super(properties);
        // A Horizontal block must define a default direction to avoid crashes
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC.get();
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        // Appends the FACING property to the block state
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // Faces the block towards the player when placed
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    // Facing-aware, per-pack-block collision: data/<packId>/collisions/<blockId>.json if this
    // block's pack shipped one (rotated to match this block's actual FACING - see
    // PowerPackLoaderCollisionLoader), otherwise a plain full-block default. Every pack block is
    // its OWN registered Block instance (unlike CACW's single shared engine block), so - unlike
    // CACW, which resolves this per block-entity via a runtime "EngineId" - the lookup key here
    // is simply this block's own registry name.
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        ResourceLocation registryId = BuiltInRegistries.BLOCK.getKey(this);
        return PowerPackLoaderCollisionLoader.getShape(registryId, state.getValue(FACING));
    }
}