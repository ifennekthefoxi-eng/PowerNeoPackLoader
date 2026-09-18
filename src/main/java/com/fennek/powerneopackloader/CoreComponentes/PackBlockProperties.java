package com.fennek.powerneopackloader.CoreComponentes;

import com.fennek.powerneopackloader.PowerNeoPackLoader;
import com.google.gson.JsonObject;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Locale;

/**
 * Builds a block's {@link BlockBehaviour.Properties} from the optional {@code "properties"} object
 * in its pack json, so hardness, light, sound and the rest are pack-author data rather than
 * something baked into a Java class - two packs can reuse the same {@code @PackBlock} class and
 * still get a soft wooden block and a tough metal one.
 * <p>
 * Every key is optional and anything absent keeps the loader's default, so a block json with no
 * {@code "properties"} at all behaves exactly as it did before this existed:
 * <pre>{@code
 * {
 *   "name": "V8 Engine",
 *   "class": "MyEngineBlock",
 *   "properties": {
 *     "strength": 4.0,
 *     "explosion_resistance": 6.0,
 *     "light": 7,
 *     "sound": "metal",
 *     "requires_tool": true
 *   }
 * }
 * }</pre>
 *
 * <h2>Defaults</h2>
 * {@code strength 2.0 / 3.0}, {@code light 0}, {@code no_occlusion true}, {@code dynamic_shape
 * true}. The last two are deliberately on: pack blocks are overwhelmingly non-cube models, and a
 * block that occludes when it shouldn't renders as a hole in the world - a far more confusing
 * failure than the small cost of leaving them enabled. A plain full-cube block can and should turn
 * both off (that restores vanilla shape caching).
 *
 * <h2>Named constants</h2>
 * {@code sound} and {@code map_color} accept any constant name from vanilla's {@link SoundType} and
 * {@link MapColor}, matched case-insensitively ({@code "metal"}, {@code "cherry_wood"},
 * {@code "color_light_blue"}). They're looked up reflectively over those classes' own public static
 * fields rather than through a hand-written switch, so every vanilla value works and no list here
 * can fall out of date. An unrecognised name logs a warning and leaves the default in place - a
 * typo in a pack must never crash the game.
 */
public final class PackBlockProperties {

    private PackBlockProperties() {
    }

    /**
     * @param definition the block's full json. The {@code "properties"} member is read if present;
     *                   anything else in the json is the block class's own business.
     * @param blockId    only used to make warnings traceable to a specific pack file.
     */
    public static BlockBehaviour.Properties build(JsonObject definition, String blockId) {
        BlockBehaviour.Properties properties = BlockBehaviour.Properties.of();

        JsonObject json = definition.has("properties") && definition.get("properties").isJsonObject()
                ? definition.getAsJsonObject("properties")
                : new JsonObject();

        // Hardness/blast resistance: "strength" sets both at once like the vanilla helper, and the
        // two specific keys override either half - so "strength": 3 alone is the common case, and
        // an unbreakable-but-soft block is still expressible.
        float destroyTime = getFloat(json, "strength", 2.0f);
        float explosionResistance = getFloat(json, "strength", 3.0f);
        destroyTime = getFloat(json, "destroy_time", destroyTime);
        explosionResistance = getFloat(json, "explosion_resistance", explosionResistance);
        properties.strength(destroyTime, explosionResistance);

        int light = getInt(json, "light", 0);
        properties.lightLevel(state -> light);

        if (getBoolean(json, "no_occlusion", true)) {
            properties.noOcclusion();
        }
        if (getBoolean(json, "dynamic_shape", true)) {
            properties.dynamicShape();
        }
        if (getBoolean(json, "no_collision", false)) {
            properties.noCollission();
        }
        if (getBoolean(json, "requires_tool", false)) {
            properties.requiresCorrectToolForDrops();
        }
        if (getBoolean(json, "random_ticks", false)) {
            properties.randomTicks();
        }
        if (getBoolean(json, "replaceable", false)) {
            properties.replaceable();
        }
        if (getBoolean(json, "ignited_by_lava", false)) {
            properties.ignitedByLava();
        }
        if (getBoolean(json, "no_loot_table", false)) {
            properties.noLootTable();
        }
        // force_solid is genuinely three-valued (on / off / let vanilla decide), so unlike the
        // booleans above it's only applied when the key is actually present.
        if (json.has("force_solid")) {
            if (getBoolean(json, "force_solid", true)) {
                properties.forceSolidOn();
            } else {
                properties.forceSolidOff();
            }
        }

        if (json.has("friction")) {
            properties.friction(getFloat(json, "friction", 0.6f));
        }
        if (json.has("speed_factor")) {
            properties.speedFactor(getFloat(json, "speed_factor", 1.0f));
        }
        if (json.has("jump_factor")) {
            properties.jumpFactor(getFloat(json, "jump_factor", 1.0f));
        }

        SoundType sound = constant(SoundType.class, SoundType.class, getString(json, "sound"), blockId, "sound");
        if (sound != null) {
            properties.sound(sound);
        }

        MapColor mapColor = constant(MapColor.class, MapColor.class, getString(json, "map_color"), blockId, "map_color");
        if (mapColor != null) {
            properties.mapColor(mapColor);
        }

        String pushReaction = getString(json, "push_reaction");
        if (pushReaction != null) {
            try {
                properties.pushReaction(PushReaction.valueOf(pushReaction.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                PowerNeoPackLoader.LOGGER.warn("Unknown push_reaction '{}' on block '{}' - keeping the default.",
                        pushReaction, blockId);
            }
        }

        return properties;
    }

    /**
     * Looks {@code name} up among {@code holder}'s public static fields of type {@code type},
     * case-insensitively. Returns null (and warns) for an unknown name, so a bad value in a pack
     * degrades to the default instead of throwing.
     */
    private static <T> T constant(Class<?> holder, Class<T> type, String name, String blockId, String what) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (Field field : holder.getFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || !type.isAssignableFrom(field.getType())) {
                continue;
            }
            if (field.getName().equalsIgnoreCase(name)) {
                try {
                    return type.cast(field.get(null));
                } catch (IllegalAccessException e) {
                    break;
                }
            }
        }
        PowerNeoPackLoader.LOGGER.warn("Unknown {} '{}' on block '{}' - keeping the default.", what, name, blockId);
        return null;
    }

    private static boolean getBoolean(JsonObject json, String key, boolean fallback) {
        return has(json, key) ? json.get(key).getAsBoolean() : fallback;
    }

    private static float getFloat(JsonObject json, String key, float fallback) {
        return has(json, key) ? json.get(key).getAsFloat() : fallback;
    }

    private static int getInt(JsonObject json, String key, int fallback) {
        return has(json, key) ? json.get(key).getAsInt() : fallback;
    }

    private static String getString(JsonObject json, String key) {
        return has(json, key) ? json.get(key).getAsString() : null;
    }

    private static boolean has(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull();
    }
}
