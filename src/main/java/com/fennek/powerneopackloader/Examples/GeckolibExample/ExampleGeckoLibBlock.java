package com.fennek.powerneopackloader.Examples.GeckolibExample;

import com.fennek.powerneopackloader.APIBridge.PackBlockContext;
import com.fennek.powerneopackloader.APIBridge.annotations.PackBlock;
import com.fennek.powerneopackloader.CoreComponentes.PowerPackLoaderCollisionLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * The reference example of a pack block, and of the only thing a mod has to write: this class, its
 * annotation, and the three classes named in it. Nothing registers it - the library finds it by the
 * annotation and registers one block per json in the packs that name {@code "class":
 * "ExampleGeckoLibBlock"}.
 * <p>
 * The three classes below are optional and independent. Declaring them here keeps the family in one
 * place; each could equally carry {@code @PackBlockEntity}/{@code @PackBlockItem}/{@code @PackRenderer}
 * itself. {@code renderer} naming a client-only class is safe - see {@code @PackBlock}.
 * <p>
 * All three happen to be GeckoLib classes here, and GeckoLib is an optional dependency of this
 * library: on an install without it they simply fail to resolve and are skipped with a warning,
 * while this block itself - which extends nothing but vanilla {@code Block} - still registers.
 */
@PackBlock(
        // Without this the block still registers on an install lacking GeckoLib, and then dies
        // inside the registry event when the JVM verifies newBlockEntity() below and resolves
        // ExampleGeckoLibBlockEntity's GeoBlockEntity supertype - a fatal mod-loading error for
        // every mod in the game, not just this example. See @PackBlock#requiredMods.
        requiredMods = "geckolib",
        entity = ExampleGeckoLibBlockEntity.class,
        item = ExampleGeckoLibBlockItem.class,
        renderer = ExampleGeckoLibBlockRenderer.class)
public class ExampleGeckoLibBlock extends Block implements EntityBlock {

    // Store the lazily-evaluated supplier provided by your API
    private final Supplier<BlockEntityType<? extends BlockEntity>> entityTypeSupplier;

    public ExampleGeckoLibBlock(BlockBehaviour.Properties properties) {
        super(properties);
        // The type this block will be paired with doesn't exist yet - it is registered moments
        // after this constructor returns, because it needs this block to already exist to list as
        // one of its valid blocks. So capture a supplier that resolves it later, not the type.
        // The fallback covers construction outside the pack loader entirely (by hand, in a test),
        // where there is no context to read; newBlockEntity below handles the resulting null.
        this.entityTypeSupplier = PackBlockContext.current()
                .<Supplier<BlockEntityType<? extends BlockEntity>>>map(
                        context -> context.blockEntityTypeSupplier(() -> null))
                .orElse(() -> null);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        // Resolve the actual BlockEntityType right as the entity is being created
        BlockEntityType<?> type = this.entityTypeSupplier.get(); //

        if (type == null) {
            return null; // Safety net in case the fallback was triggered
        }

        return new ExampleGeckoLibBlockEntity(type, pos, state);
    }

    // Loaded collision, same mechanism as LoadableModelHorizontalDirectionalBlock: a pack-shipped
    // data/<packId>/collisions/<blockId>.json if one exists, otherwise the plain full-block
    // default - see PowerPackLoaderCollisionLoader. This block has no FACING property, so there's
    // nothing to rotate the shape by - Direction.SOUTH is passed simply because it's the loader's
    // identity/reference orientation (an authored box comes back unrotated for SOUTH).
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        ResourceLocation registryId = BuiltInRegistries.BLOCK.getKey(this);
        return PowerPackLoaderCollisionLoader.getShape(registryId, Direction.SOUTH);
    }
}