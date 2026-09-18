package com.fennek.powerneopackloader.CoreComponentes;

import com.fennek.powerneopackloader.PowerNeoPackLoader;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Decides what each pack block is actually registered as, and guarantees no two ever collide.
 *
 * <h2>The normal name</h2>
 * {@code <packId>_<blockId>}. Prefixing with the pack id is what lets two packs both ship a
 * {@code v8_engine.json} without either one silently winning.
 *
 * <h2>Extending the original set</h2>
 * A pack whose meta json sets {@code "extend_original": true} is declaring itself part of the mod's
 * own content rather than an addition alongside it, and its blocks drop the prefix:
 * {@code <blockId>}. That is how a mod moves a block it used to register in Java into a pack
 * without changing its id - so existing worlds, recipes, tags and loot tables keep working, because
 * as far as the game is concerned it is the same block it always was.
 *
 * <h2>When a name is already taken</h2>
 * Never overwrite, always fall through to the next candidate:
 * <ol>
 *     <li>{@code <blockId>} - only attempted for an {@code extend_original} pack;</li>
 *     <li>{@code <packId>_<blockId>} - the ordinary prefixed name;</li>
 *     <li>{@code <packId>_<blockId>_1}, {@code _2}, {@code _3}, … - counting up until one is free.</li>
 * </ol>
 * So two packs both extending the original with a {@code v8_engine} give
 * {@code v8_engine} to whichever is read first and {@code coolpack_v8_engine} to the second; a
 * third pack that ALSO happens to be called {@code coolpack} (or that already used that exact
 * prefixed name) gets {@code coolpack_v8_engine_1}, and so on. The same rule applies at every step,
 * including to names that are themselves the result of a previous fallback, so the chain can never
 * dead-end or loop.
 *
 * <h2>What "taken" means</h2>
 * Taken by another PACK BLOCK - every name this class has handed out, across every pack and every
 * loader belonging to that mod. It deliberately does not consult the block registry, because at the
 * point pack blocks are being registered that registry is still empty: mod constructors only QUEUE
 * registrations, and nothing is actually in the registry until the registry event fires later. So a
 * clash against a block the mod registers by other means (Registrate, a plain DeferredRegister) is
 * not detectable here and still surfaces the ordinary way, as a duplicate-registration crash. That
 * is the case {@code extend_original} exists to serve on purpose: a mod using it is meant to have
 * REMOVED its Java registration of that block first.
 */
public final class PackRegistryNames {

    /** modId -> every registry name already handed out for it. Guarded by itself; FML constructs
     *  mods in parallel, so two mods can be allocating names at the same moment. */
    private static final Map<String, Set<String>> CLAIMED = new HashMap<>();

    private PackRegistryNames() {
    }

    /**
     * Claims and returns the registry name for one pack block.
     *
     * @param modId          the mod the block registers under.
     * @param packId         the pack's id from its meta json.
     * @param blockId        the block json's filename, without the extension.
     * @param extendOriginal whether this block's pack set {@code "extend_original": true}.
     */
    public static String allocate(String modId, String packId, String blockId, boolean extendOriginal) {
        synchronized (CLAIMED) {
            Set<String> claimed = CLAIMED.computeIfAbsent(modId, key -> new HashSet<>());

            String prefixed = packId + "_" + blockId;

            if (extendOriginal && claimed.add(blockId)) {
                PowerNeoPackLoader.LOGGER.info(
                        "Pack '{}' extends the original set: '{}' registers as {}:{}", packId, blockId, modId, blockId);
                return blockId;
            }

            if (claimed.add(prefixed)) {
                if (extendOriginal) {
                    PowerNeoPackLoader.LOGGER.warn(
                            "Pack '{}' wanted to register '{}' as {}:{}, but that name is already taken by another "
                                    + "pack block - using {}:{} instead.", packId, blockId, modId, blockId, modId, prefixed);
                }
                return prefixed;
            }

            // Both preferred names are gone. Count up until something is free - the loop is the
            // "recursive" part: each candidate is checked by exactly the same rule as the last, so
            // a name that is itself a previous fallback simply gets counted past.
            for (int counter = 1; counter < Integer.MAX_VALUE; counter++) {
                String candidate = prefixed + "_" + counter;
                if (claimed.add(candidate)) {
                    PowerNeoPackLoader.LOGGER.warn(
                            "Pack '{}' block '{}' collides with an already-registered pack block - registering it as "
                                    + "{}:{}. Rename it in one of the packs if this wasn't intended.",
                            packId, blockId, modId, candidate);
                    return candidate;
                }
            }

            // Unreachable in practice - it would take billions of same-named blocks in one mod.
            throw new IllegalStateException("Ran out of registry names for " + modId + ":" + prefixed);
        }
    }

    /** Whether {@code registryName} has already been handed out for {@code modId}. */
    public static boolean isClaimed(String modId, String registryName) {
        synchronized (CLAIMED) {
            return CLAIMED.getOrDefault(modId, Set.of()).contains(registryName);
        }
    }

    /** Forgets every name claimed for {@code modId}. Only for tests and re-scans - calling it while
     *  a mod's packs are registered would let the next pack reuse a live name. */
    public static void clear(String modId) {
        synchronized (CLAIMED) {
            CLAIMED.remove(modId);
        }
    }
}
