package com.fennek.powerneopackloader.Examples;

import com.mojang.serialization.MapCodec;
import com.fennek.powerneopackloader.CoreComponentes.PowerPackLoaderCollisionLoader;
import com.fennek.powerneopackloader.CoreComponentes.PowerPackLoaderRegistrationContext;
import com.fennek.powerneopackloader.PowerNeoPackLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jetbrains.annotations.Nullable;

/**
 * Example of a second, independent "functional block" base for the loader API - built the way
 * chest-variant mods (e.g. More Chests: https://github.com/Nibaru/More-Chests) actually do it:
 * by extending vanilla's own {@link ChestBlock} directly, instead of re-deriving {@code
 * AbstractChestBlock} from scratch.
 * <p>
 * The original sketch this was requested from tried to {@code extends
 * LoadableModelHorizontalDirectionalBlock, AbstractChestBlock<ChestBlockEntity>} - Java has no
 * multiple inheritance of classes, so a block can't extend both. {@code ChestBlock} isn't {@code
 * final}, so subclassing it directly (rather than reimplementing its double-chest merging, menu
 * provider, lid-animation ticker, and waterlogging by hand) is both simpler and correct by
 * construction - this example only has to add what the loader API actually needs on top: its own
 * codec (required for the reflective {@code Properties}-only constructor {@code
 * PowerNeoPackLoaderRegister} instantiates every pack block through) and the pack-driven
 * collision lookup.
 * <p>
 * Blockstate note: {@code PowerPackLoaderModelGenerator}'s auto-generated blockstate fallback
 * only emits FACING variants for blocks assignable to {@link HorizontalDirectionalBlock} -
 * {@code ChestBlock} (and so this class) isn't one, so a pack using this class must ship its own
 * hand-authored {@code assets/<packId>/blockstates/<blockId>.json} (with its own facing
 * variants) to get visual rotation - see {@code PowerPackLoaderModelGenerator}'s class doc for
 * that fallback chain.
 */
public class ChestLoadingExample extends ChestBlock {

    public static final DeferredRegister<MapCodec<? extends Block>> CODECS =
            DeferredRegister.create(BuiltInRegistries.BLOCK_TYPE, PowerNeoPackLoader.MOD_ID);

    public static final DeferredHolder<MapCodec<? extends Block>, MapCodec<ChestLoadingExample>> CODEC =
            CODECS.register("loadable_chest_example", () -> simpleCodec(ChestLoadingExample::new));

    // Reflection-instantiated by PowerNeoPackLoaderRegister via getConstructor(Properties.class),
    // exactly like LoadableModelHorizontalDirectionalBlock - so this MUST stay a single-arg
    // constructor.
    //
    // ChestBlock's own constructor also wants a block-entity-type supplier, and this MUST NOT be
    // vanilla's own `() -> BlockEntityType.CHEST`: every method on ChestBlock that opens the
    // container or creates the block entity goes through this supplier, and vanilla's
    // BlockEntityType.CHEST only accepts Blocks.CHEST/Blocks.TRAPPED_CHEST as valid owners
    // (BlockEntityType.Builder.of(..., Blocks.CHEST, Blocks.TRAPPED_CHEST)). Handing it to a
    // different block crashes the instant the block entity is constructed:
    // "Invalid block entity minecraft:chest ... got Block{<this pack block>}" - because the
    // entity's own `type` field ends up set to CHEST (ChestBlockEntity's public 2-arg
    // constructor hardcodes `this(BlockEntityType.CHEST, pos, state)`), and BlockEntity's own
    // constructor then rejects any block that CHEST doesn't recognize.
    //
    // Instead this resolves its OWN dynamically-registered BlockEntityType - the one
    // PowerNeoPackLoaderRegister registers under this exact block's own registry name, whose
    // valid-blocks list is this block and only this block (see PowerNeoPackLoaderRegister) - via
    // PowerPackLoaderRegistrationContext. `this` can't be captured here (the object doesn't exist
    // until super() returns), so the context instead hands back a supplier keyed off the registry
    // id PowerNeoPackLoaderRegister is registering this construction under.
    public ChestLoadingExample(Properties properties) {
        super(properties, PowerPackLoaderRegistrationContext.resolveEntityTypeSupplier(() -> BlockEntityType.CHEST));
    }

    // ChestBlock already declares this as public (not protected), so the override has to match -
    // narrowing visibility here would fail to compile.
    @Override
    public MapCodec<? extends ChestBlock> codec() {
        return CODEC.get();
    }

    // Facing-aware, per-pack-block collision, identical in spirit to
    // LoadableModelHorizontalDirectionalBlock#getShape: data/<packId>/collisions/<blockId>.json
    // if this block's pack shipped one (rotated to match this block's actual FACING), otherwise a
    // plain full-block default. ChestBlock's own getShape() just returns a fixed constant, which
    // this replaces entirely.
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        ResourceLocation registryId = BuiltInRegistries.BLOCK.getKey(this);
        return PowerPackLoaderCollisionLoader.getShape(registryId, state.getValue(FACING));
    }

    // THE ACTUAL FIX for the placement crash. Passing a different supplier to super(...) above
    // is not enough on its own: vanilla ChestBlock.newBlockEntity(BlockPos, BlockState) does NOT
    // consult that supplier when constructing the entity - it hardcodes `new ChestBlockEntity(pos,
    // state)` directly (confirmed by the crash stack trace, which jumps straight from
    // ChestBlock.newBlockEntity to ChestBlockEntity's constructor with no BlockEntityType.create
    // frame in between). That constructor's public 2-arg form always sets the entity's own `type`
    // field to vanilla's shared BlockEntityType.CHEST, no matter what supplier this block was
    // built with - which is why the crash kept saying "Invalid block entity minecraft:chest" even
    // after a distinct BlockEntityType was registered for this exact block.
    //
    // So this must override newBlockEntity itself and route around ChestBlock's hardcoded path
    // entirely, building the entity through this block's OWN registered BlockEntityType (whose
    // valid-blocks list is this block and only this block).
    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        ResourceLocation registryId = BuiltInRegistries.BLOCK.getKey(this);
        BlockEntityType<?> ownType = BuiltInRegistries.BLOCK_ENTITY_TYPE.get(registryId);
        if (ownType != null) {
            BlockEntity entity = ownType.create(pos, state);
            if (entity != null) {
                return entity;
            }
        }
        // Only reached if PowerNeoPackLoaderRegister somehow didn't register a matching
        // BlockEntityType for this block (shouldn't happen for anything going through the pack
        // loader) - log it loudly instead of silently falling back to vanilla's CHEST type, which
        // is exactly what caused the original crash.
        PowerNeoPackLoader.LOGGER.error(
                "No matching BlockEntityType registered for chest block {} - falling back to vanilla BlockEntityType.CHEST, which will likely crash on placement.",
                registryId);
        return super.newBlockEntity(pos, state);
    }
}