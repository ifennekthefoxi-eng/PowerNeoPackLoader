package com.fennek.powerneopackloader.Registration;

import com.fennek.powerneopackloader.APIBridge.PackBlockContext;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The answer to "the library registered my pack blocks - now how do I get at one?".
 * <p>
 * Blocks loaded from a pack have no {@code public static final} field to reference, because they
 * don't exist until a pack is read: a mod cannot write {@code MyBlocks.V8_ENGINE} for a block whose
 * very existence depends on a folder the player can edit. Everything the register step created is
 * recorded here instead, keyed the way a pack author thinks about it - by pack id and block id -
 * so a mod can find its own blocks (for recipes, tags, tooltips, a contraption's block list, ...)
 * without re-reading the pack folder or reaching into a {@code DeferredRegister} it never held.
 * <pre>{@code
 * PowerPackLoaderRegistry.get(MOD_ID, "my_pack", "v8_engine")
 *         .map(PowerPackLoaderRegistry.PackBlockEntry::block)
 *         .ifPresent(block -> ...);
 *
 * for (var entry : PowerPackLoaderRegistry.byMod(MOD_ID)) { ... }
 * }</pre>
 * Holders, not live objects: entries are handed out as {@link DeferredBlock}/{@link DeferredItem},
 * which are safe to hold from the moment registration is queued. Calling {@code .get()} on one
 * before the registry event has fired throws, so resolve them when you use them, not when you look
 * them up.
 * <p>
 * Repopulated from scratch on every scan (see {@link #clear(String)}), so a block removed from a
 * pack leaves no stale entry behind.
 */
public final class PowerPackLoaderRegistry {

    /**
     * What the register step produced for one pack block. {@code entityType} is null for blocks
     * with no mapped block entity, and {@code item} is never null - every pack block gets a
     * {@code BlockItem}.
     */
    public record PackBlockEntry(PackBlockContext context,
                                 DeferredBlock<Block> block,
                                 DeferredItem<BlockItem> item,
                                 @Nullable DeferredHolder<BlockEntityType<?>, BlockEntityType<?>> entityType) {

        /** {@code <modId>:<packId>_<blockId>} - the block/item's real registry id. */
        public ResourceLocation id() {
            return context.registryId();
        }
    }

    /** modId -> packId -> blockId -> entry. Nested rather than flat so {@link #byPack} and
     *  {@link #byMod} are plain lookups; every level is a LinkedHashMap so listings come back in
     *  discovery order, which is also the order pack blocks appear in their creative tab. */
    private static final Map<String, Map<String, Map<String, PackBlockEntry>>> ENTRIES = new LinkedHashMap<>();

    /**
     * Guards {@link #ENTRIES}. FML constructs mods in PARALLEL, so several mods populate this
     * registry from different {@code modloading-worker} threads at once, while a mod's own setup
     * may already be reading it back. Plain synchronization rather than a concurrent map because
     * the nesting is three deep and every level is a {@link LinkedHashMap} - the insertion order
     * IS the contract here (it's the order blocks appear in their creative tab), and the
     * order-preserving concurrent map doesn't exist. Contention is nil: writes happen once per
     * pack block at startup.
     */
    private static final Object LOCK = new Object();

    private PowerPackLoaderRegistry() {
    }

    /** Called by the register step for each pack block it creates. */
    public static void record(PackBlockEntry entry) {
        synchronized (LOCK) {
            ENTRIES.computeIfAbsent(entry.context().modId(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(entry.context().packId(), k -> new LinkedHashMap<>())
                    .put(entry.context().blockId(), entry);
        }
    }

    /**
     * Drops everything recorded for one pack, so re-scanning it can't leave entries for blocks
     * that no longer exist. Called per pack rather than per mod because a mod may run several
     * loaders, each over a different folder - wiping the whole mod as each loader starts would
     * throw away the previous loader's entries along with the stale ones.
     */
    public static void clearPack(String modId, String packId) {
        synchronized (LOCK) {
            Map<String, Map<String, PackBlockEntry>> mod = ENTRIES.get(modId);
            if (mod != null) {
                mod.remove(packId);
            }
        }
    }

    /** Drops everything recorded for {@code modId}, across every pack and loader. */
    public static void clear(String modId) {
        synchronized (LOCK) {
            ENTRIES.remove(modId);
        }
    }

    /** One specific pack block, by the two ids a pack author actually knows: the pack's id from
     *  its meta json, and the block json's filename. */
    public static Optional<PackBlockEntry> get(String modId, String packId, String blockId) {
        synchronized (LOCK) {
            return Optional.ofNullable(ENTRIES.getOrDefault(modId, Map.of())
                    .getOrDefault(packId, Map.of())
                    .get(blockId));
        }
    }

    /** Every block one pack contributed, in discovery order. */
    public static List<PackBlockEntry> byPack(String modId, String packId) {
        synchronized (LOCK) {
            return List.copyOf(ENTRIES.getOrDefault(modId, Map.of())
                    .getOrDefault(packId, Map.of())
                    .values());
        }
    }

    /** Every pack block registered for {@code modId}, across all of its packs and loaders. */
    public static List<PackBlockEntry> byMod(String modId) {
        synchronized (LOCK) {
            List<PackBlockEntry> all = new ArrayList<>();
            for (Map<String, PackBlockEntry> pack : ENTRIES.getOrDefault(modId, Map.of()).values()) {
                all.addAll(pack.values());
            }
            return Collections.unmodifiableList(all);
        }
    }

    /**
     * Finds a pack block by the ids a pack author writes, without needing to know which mod
     * registered it.
     * <p>
     * For code that only ever sees pack-scoped ids - a resource id like {@code <packId>:<blockId>},
     * say - and has to get back to the block's REAL registry name, which is not derivable by string
     * concatenation: an {@code extend_original} pack drops the prefix, and a collision appends a
     * counter. See {@link com.fennek.powerneopackloader.CoreComponentes.PackRegistryNames}.
     */
    public static Optional<PackBlockEntry> findByPackAndBlock(String packId, String blockId) {
        synchronized (LOCK) {
            for (Map<String, Map<String, PackBlockEntry>> byPack : ENTRIES.values()) {
                Map<String, PackBlockEntry> blocks = byPack.get(packId);
                if (blocks != null && blocks.containsKey(blockId)) {
                    return Optional.of(blocks.get(blockId));
                }
            }
            return Optional.empty();
        }
    }

    /** The pack ids {@code modId} has registered blocks for, in discovery order. */
    public static List<String> packIds(String modId) {
        synchronized (LOCK) {
            return List.copyOf(ENTRIES.getOrDefault(modId, Map.of()).keySet());
        }
    }
}
