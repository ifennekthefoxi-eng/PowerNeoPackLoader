package com.fennek.powerneopackloader.Examples;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Same underlying trap as {@code ChestBlockEntity}, on the furnace side: vanilla's
 * {@code FurnaceBlockEntity} only exposes a public {@code (BlockPos, BlockState)} constructor,
 * which hardcodes {@code super(BlockEntityType.FURNACE, pos, state, RecipeType.SMELTING)}
 * internally - so {@code PowerNeoPackLoaderRegister}'s reflective instantiation would silently get
 * an entity typed as vanilla's shared FURNACE type again, the exact "Invalid block entity" crash
 * {@code ChestLoadingExample} was built to avoid.
 * <p>
 * Rather than fight that constructor, this skips {@code FurnaceBlockEntity} entirely and extends
 * the shared {@link AbstractFurnaceBlockEntity} base directly - the same class
 * {@code FurnaceBlockEntity}/{@code BlastFurnaceBlockEntity}/{@code SmokerBlockEntity} all extend,
 * which is where all the actual smelting/burn-time/XP logic lives. Declaring the
 * {@code (BlockEntityType, BlockPos, BlockState)} constructor directly on THIS class means
 * {@code PowerNeoPackLoaderRegister.instantiateBlockEntity}'s {@code getDeclaredConstructor} check
 * finds it immediately - no reflection fallback, no override needed on the entity side at all
 * (unlike the block side - see {@code FurnaceLoadingExample#newBlockEntity}).
 */
public class LoadableFurnaceBlockEntity extends AbstractFurnaceBlockEntity {

    public LoadableFurnaceBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state, RecipeType.SMELTING);
    }

    // Only used for the menu's title bar - purely cosmetic. Swap for your own translation key if
    // you don't want it to literally say "Furnace".
    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.furnace");
    }

    // AbstractFurnaceBlockEntity leaves menu creation abstract so FurnaceBlockEntity,
    // BlastFurnaceBlockEntity, and SmokerBlockEntity can each open their own menu class - reusing
    // vanilla's plain FurnaceMenu here is what makes this behave like a normal furnace GUI.
    // `dataAccess` is inherited from AbstractFurnaceBlockEntity and already wired up to sync
    // burn-time/cook-progress to the client - nothing to do there.
    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return new FurnaceMenu(containerId, inventory, this, this.dataAccess);
    }
}
