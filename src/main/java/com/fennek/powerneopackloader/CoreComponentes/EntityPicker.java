package com.fennek.powerneopackloader.CoreComponentes;

import net.minecraft.world.level.block.entity.ChestBlockEntity;

import java.util.HashMap;
import java.util.Map;

public class EntityPicker {

    // A Map links a String key directly to a Class<?> value
    private final Map<Class<?>, Class<?>> EntityRegistry = new HashMap<>();

    public EntityPicker() {
        // Register all your available classes here
        EntityRegistry.put(LoadableModelHorizontalDirectionalBlock.class, null);

        // Example of adding more in the future:
        // classRegistry.put("SomeOtherBlock", SomeOtherBlock.class);
    }

    public void AddEntity(Class<?> BlockClass,Class<?> EntityClass){
        Class<?> check = getEnityByBlockClass(BlockClass);
        if(check == null){
            EntityRegistry.put(BlockClass,EntityClass);
        }
    }

    /**
     * Pass the string from your JSON file into this method to get the correct Class back.
     * Returns null if the string doesn't match any registered class.
     */
    public Class<?> getEnityByBlockClass(Class<?> BlockClass) {
        return EntityRegistry.get(BlockClass);
    }
}