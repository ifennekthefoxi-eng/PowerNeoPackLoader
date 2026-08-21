package com.fennek.powerneopackloader.CoreComponentes;

import java.util.HashMap;
import java.util.Map;

public class ClassPicker {

    // A Map links a String key directly to a Class<?> value
    private final Map<String, Class<?>> classRegistry = new HashMap<>();

    public ClassPicker() {
        // Register all your available classes here
        classRegistry.put("LoadableModelHorizontalDirectionalBlock", LoadableModelHorizontalDirectionalBlock.class);

        // Example of adding more in the future:
        // classRegistry.put("SomeOtherBlock", SomeOtherBlock.class);
    }

    public void AddClass(String id,Class<?> Class){
        Class<?> check = getClassById(id);
        if(check == null){
            classRegistry.put(id,Class);
        }
    }

    /**
     * Pass the string from your JSON file into this method to get the correct Class back.
     * Returns null if the string doesn't match any registered class.
     */
    public Class<?> getClassById(String classId) {
        return classRegistry.get(classId);
    }
}