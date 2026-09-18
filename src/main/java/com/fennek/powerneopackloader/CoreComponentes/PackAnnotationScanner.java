package com.fennek.powerneopackloader.CoreComponentes;

import com.fennek.powerneopackloader.APIBridge.annotations.PackBlock;
import com.fennek.powerneopackloader.APIBridge.annotations.PackBlockEntity;
import com.fennek.powerneopackloader.APIBridge.annotations.PackBlockItem;
import com.fennek.powerneopackloader.APIBridge.annotations.PackRenderer;
import com.fennek.powerneopackloader.PowerNeoPackLoader;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.ModFileScanData;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Type;

import java.lang.annotation.ElementType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns {@code @PackBlock}/{@code @PackBlockEntity}/{@code @PackBlockItem}/{@code @PackRenderer}
 * into filled-in {@link ClassPicker}/{@link EntityPicker}/{@link ItemPicker}/{@link RendererPicker}
 * registries, so a modder never writes a single {@code AddClass(...)} line. This is the whole of
 * "the modder only has to create their classes".
 *
 * <h2>How the classes are found</h2>
 * Through NeoForge's own mod-file annotation scan ({@link ModFileScanData}) - the exact index that
 * powers {@code @Mod} and {@code @EventBusSubscriber}. FML builds it while locating mod files, long
 * before any mod constructor runs, by reading class bytes with ASM. That means:
 * <ul>
 *     <li>discovery is free - no classpath walk, no reflection library, no scanning at runtime;</li>
 *     <li>it works identically in a dev workspace, in a production jar, and inside a jar-in-jar;</li>
 *     <li><b>no annotated class is loaded just to be found.</b> A class is only loaded once we've
 *     decided we actually want it, which is what makes the optional-dependency handling below
 *     possible.</li>
 * </ul>
 *
 * <h2>Scoping to one mod</h2>
 * The scan is per mod FILE, and we resolve the file from the mod id the caller passed to
 * {@code PowerPackLoaderApi}. Two mods can therefore both ship a {@code @PackBlock} called
 * {@code "EngineBlock"} without either seeing the other's - each one's pack json means its own
 * class. A mod file containing several mods shares one scan, so sibling mods in the same jar do
 * see each other's annotations; that's the one case where ids need to stay distinct.
 *
 * <h2>Optional dependencies</h2>
 * Every class resolution here is individually guarded against {@link Throwable}, not just
 * {@link ClassNotFoundException}. Loading a class whose superclass or interface is missing - a
 * block extending a type from a mod the player didn't install - throws {@link NoClassDefFoundError},
 * an Error rather than an Exception. Catching only exceptions would let a single optional-dependency
 * block class take the whole game down at startup; catching Throwable per class means it is simply
 * skipped with a warning and every other pack block still loads.
 */
public final class PackAnnotationScanner {

    /** {@code void.class} is the "not set" sentinel for the Class-valued members of
     *  {@code @PackBlock}; ASM reports it as the primitive type {@code V}. */
    private static final Type VOID_TYPE = Type.getType(void.class);

    private PackAnnotationScanner() {
    }

    /**
     * Scans {@code modId}'s mod file and fills the four registries from whatever it finds.
     * <p>
     * Safe to call more than once for the same mod (a mod with several pack loaders does): every
     * {@code AddX} method is first-wins, so a repeat scan re-adds the same mappings as no-ops.
     * Renderer mappings are only collected on the client - see {@link RendererPicker}.
     *
     * @return how many {@code @PackBlock} classes were registered.
     */
    public static int scan(String modId, ClassPicker classPicker, EntityPicker entityPicker,
                           ItemPicker itemPicker, RendererPicker rendererPicker) {
        ModFileScanData scanData = findScanData(modId);
        if (scanData == null) {
            PowerNeoPackLoader.LOGGER.warn(
                    "No mod file scan data found for '{}' - annotated pack classes cannot be discovered. "
                            + "Register them manually via the ClassPicker/EntityPicker/ItemPicker if this is intentional.",
                    modId);
            return 0;
        }

        boolean client = FMLLoader.getDist().isClient();
        int blockCount = 0;

        // Pass 1: @PackBlock. Has to complete before the standalone annotations below, because a
        // @PackBlockEntity naming a block class is only meaningful once that block class is a
        // known pack block - and because @PackBlock's own entity()/item()/renderer() members are
        // first-wins over them.
        for (ModFileScanData.AnnotationData data : scanData.getAnnotatedBy(PackBlock.class, ElementType.TYPE).toList()) {
            Class<?> blockClass = resolve(data.clazz(), "@PackBlock");
            if (blockClass == null) {
                continue;
            }
            if (!Block.class.isAssignableFrom(blockClass)) {
                PowerNeoPackLoader.LOGGER.error(
                        "@PackBlock on {} is ignored - it does not extend net.minecraft.world.level.block.Block.",
                        blockClass.getName());
                continue;
            }

            Map<String, Object> members = data.annotationData();

            String id = stringMember(members, "id");
            if (id == null || id.isBlank()) {
                id = blockClass.getSimpleName();
            }

            List<String> missing = missingMods(members);
            if (!missing.isEmpty()) {
                // Skipped, not failed: see @PackBlock#requiredMods for why registering it anyway
                // is fatal rather than merely broken. Remembered under the id packs refer to it by,
                // so a pack naming it gets told WHY it's unavailable instead of "unknown class".
                recordSkipped(modId, id, missing);
                PowerNeoPackLoader.LOGGER.info("Skipping @PackBlock '{}' ({}) - requires missing mod(s) {}.",
                        id, blockClass.getSimpleName(), missing);
                continue;
            }

            classPicker.AddClass(id, blockClass);
            blockCount++;
            PowerNeoPackLoader.LOGGER.info("Discovered pack block class '{}' -> {}", id, blockClass.getName());

            // The all-in-one form. Each of these is optional and independently guarded, so a
            // missing renderer on a server (or a GeckoLib entity without GeckoLib) never stops
            // the block itself from registering.
            Class<?> entityClass = resolveMember(members, "entity", "@PackBlock(entity)");
            if (entityClass != null) {
                entityPicker.AddEntity(blockClass, entityClass);
            }

            Class<?> itemClass = resolveMember(members, "item", "@PackBlock(item)");
            if (itemClass != null) {
                itemPicker.AddItem(blockClass, itemClass);
            }

            if (client && entityClass != null) {
                Class<?> rendererClass = resolveMember(members, "renderer", "@PackBlock(renderer)");
                if (rendererClass != null) {
                    rendererPicker.AddRenderer(entityClass, rendererClass);
                }
            }
        }

        // Pass 2: the standalone annotations, for families declared from the other side (or split
        // across classes the modder doesn't own).
        for (ModFileScanData.AnnotationData data : scanData.getAnnotatedBy(PackBlockEntity.class, ElementType.TYPE).toList()) {
            Class<?> entityClass = resolve(data.clazz(), "@PackBlockEntity");
            Class<?> blockClass = resolveMember(data.annotationData(), "value", "@PackBlockEntity(value)");
            if (entityClass != null && blockClass != null) {
                entityPicker.AddEntity(blockClass, entityClass);
                PowerNeoPackLoader.LOGGER.info("Discovered pack block entity {} for block {}",
                        entityClass.getSimpleName(), blockClass.getSimpleName());
            }
        }

        for (ModFileScanData.AnnotationData data : scanData.getAnnotatedBy(PackBlockItem.class, ElementType.TYPE).toList()) {
            Class<?> itemClass = resolve(data.clazz(), "@PackBlockItem");
            Class<?> blockClass = resolveMember(data.annotationData(), "value", "@PackBlockItem(value)");
            if (itemClass != null && blockClass != null) {
                itemPicker.AddItem(blockClass, itemClass);
                PowerNeoPackLoader.LOGGER.info("Discovered pack block item {} for block {}",
                        itemClass.getSimpleName(), blockClass.getSimpleName());
            }
        }

        if (client) {
            for (ModFileScanData.AnnotationData data : scanData.getAnnotatedBy(PackRenderer.class, ElementType.TYPE).toList()) {
                Class<?> rendererClass = resolve(data.clazz(), "@PackRenderer");
                Class<?> entityClass = resolveMember(data.annotationData(), "value", "@PackRenderer(value)");
                if (rendererClass != null && entityClass != null) {
                    rendererPicker.AddRenderer(entityClass, rendererClass);
                    PowerNeoPackLoader.LOGGER.info("Discovered pack renderer {} for block entity {}",
                            rendererClass.getSimpleName(), entityClass.getSimpleName());
                }
            }
        }

        PowerNeoPackLoader.LOGGER.info("Annotation scan for '{}' registered {} pack block class(es).", modId, blockCount);
        return blockCount;
    }

    /**
     * The scan data of the mod FILE that {@code modId} lives in.
     * <p>
     * {@code ModList#getModFileById} is the direct route and covers every normally-loaded mod. The
     * fallback exists for the case where it doesn't resolve (a mod file whose info isn't indexed
     * under this id yet at the time we're called): we then look through every file's scan data for
     * one that declares this mod id, which reaches the same object the long way round.
     */
    private static @Nullable ModFileScanData findScanData(String modId) {
        IModFileInfo info = ModList.get().getModFileById(modId);
        if (info != null && info.getFile() != null) {
            return info.getFile().getScanResult();
        }

        for (ModFileScanData candidate : ModList.get().getAllScanData()) {
            for (IModFileInfo fileInfo : candidate.getIModInfoData()) {
                boolean owns = fileInfo.getMods().stream().anyMatch(mod -> modId.equals(mod.getModId()));
                if (owns) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /**
     * modId -> pack class id -> the mods it needed but didn't get.
     * <p>
     * Purely so the register step can tell a pack author "that block needs GeckoLib" instead of
     * "unknown block class", which is the same message a typo produces and sends them looking in
     * entirely the wrong place.
     */
    private static final Map<String, Map<String, List<String>>> SKIPPED = new java.util.concurrent.ConcurrentHashMap<>();

    private static void recordSkipped(String modId, String classId, List<String> missingMods) {
        SKIPPED.computeIfAbsent(modId, key -> new java.util.concurrent.ConcurrentHashMap<>())
                .put(classId, List.copyOf(missingMods));
    }

    /**
     * The mods that {@code classId} needed but didn't get, or empty if it wasn't skipped for that
     * reason - i.e. the name is genuinely unknown.
     */
    public static List<String> missingModsFor(String modId, String classId) {
        return SKIPPED.getOrDefault(modId, Map.of()).getOrDefault(classId, List.of());
    }

    /**
     * The {@code requiredMods} entries that are not loaded. Empty when the block can register.
     * <p>
     * ASM reports an array-valued annotation member as a {@link List}, and omits it entirely when
     * it was left at its default - so an absent member means "needs nothing", not "needs nothing
     * known".
     */
    private static List<String> missingMods(Map<String, Object> members) {
        if (!(members.get("requiredMods") instanceof List<?> required) || required.isEmpty()) {
            return List.of();
        }
        List<String> missing = new ArrayList<>();
        for (Object entry : required) {
            String modId = String.valueOf(entry);
            if (!ModList.get().isLoaded(modId)) {
                missing.add(modId);
            }
        }
        return missing;
    }

    /** A String-valued annotation member, or null when the modder left it at its default (ASM
     *  omits defaulted members from the map entirely - they are not present as nulls). */
    private static @Nullable String stringMember(Map<String, Object> members, String name) {
        Object value = members.get(name);
        return value instanceof String s ? s : null;
    }

    /** A Class-valued annotation member, resolved to a live {@link Class}; null when the member
     *  was left at its {@code void.class} default, absent, or impossible to load. */
    private static @Nullable Class<?> resolveMember(Map<String, Object> members, String name, String what) {
        // ASM hands back the class reference as a Type (just a descriptor string) rather than a
        // loaded Class - which is precisely why naming a client-only renderer in a @PackBlock on a
        // common-side block class is safe: nothing resolves until we choose to.
        if (!(members.get(name) instanceof Type type) || VOID_TYPE.equals(type)) {
            return null;
        }
        return resolve(type, what);
    }

    private static @Nullable Class<?> resolve(Type type, String what) {
        try {
            // initialize=false: we only need the Class object to read its shape and hand it to the
            // register step, which constructs it later. Running static initializers here would pull
            // in registry/client state at an arbitrarily early point in mod loading.
            return Class.forName(type.getClassName(), false, PackAnnotationScanner.class.getClassLoader());
        } catch (Throwable t) {
            // Throwable, not Exception - see this class's doc: a missing optional dependency
            // surfaces as NoClassDefFoundError, and must skip one class rather than kill startup.
            PowerNeoPackLoader.LOGGER.warn("Skipping {} on '{}': {} ({})", what, type.getClassName(),
                    t.getClass().getSimpleName(), t.getMessage());
            return null;
        }
    }
}
