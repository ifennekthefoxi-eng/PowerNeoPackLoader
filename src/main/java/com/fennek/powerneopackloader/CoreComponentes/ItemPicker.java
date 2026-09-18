package com.fennek.powerneopackloader.CoreComponentes;

import java.util.HashMap;
import java.util.Map;

/**
 * Block class -&gt; custom {@code BlockItem} subclass, the exact same shape as {@link EntityPicker}
 * (block class -&gt; block entity class) but for the item half of a pack block.
 * <p>
 * Without an entry here, {@code PowerNeoPackLoaderRegister} registers a plain vanilla
 * {@code BlockItem} for every pack block - correct for ordinary blocks, but wrong for a GeckoLib
 * block: a plain {@code BlockItem} can only ever show a flat 2D icon (see
 * {@code PowerPackLoaderModelGenerator}'s convention-texture fallback), never the animated 3D
 * {@code GeoItemRenderer} render GeckoLib provides. Mapping a block class to a {@code GeoItem}
 * subclass here (e.g. {@code ExampleChestBlock.class -> ExampleChestBlockItem.class}) is what
 * tells the register step to reflectively construct THAT class instead - see
 * {@code ExampleChestBlockItem} for the class itself.
 */
public class ItemPicker {

    private final Map<Class<?>, Class<?>> itemRegistry = new HashMap<>();

    public void AddItem(Class<?> BlockClass, Class<?> ItemClass) {
        Class<?> check = getItemByBlockClass(BlockClass);
        if (check == null) {
            itemRegistry.put(BlockClass, ItemClass);
        }
    }

    /**
     * Pass a registered block class in to get its mapped {@code BlockItem} subclass back, or
     * null if none was mapped - in which case the caller falls back to a plain {@code BlockItem}.
     */
    public Class<?> getItemByBlockClass(Class<?> BlockClass) {
        return itemRegistry.get(BlockClass);
    }
}
