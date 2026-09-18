package com.fennek.powerneopackloader.loadingPacks;

import com.fennek.powerneopackloader.PowerNeoPackLoader;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.world.item.Item;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.locating.IModFile;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class PowerPackLoaderPacksLoader implements RepositorySource {

    private static final Marker MARKER = MarkerManager.getMarker("CACWEnginePackFinder");
    private static final Gson GSON = new GsonBuilder().create();

    /**
     * Every loader any mod has created, so {@code PowerPackLoaderModBusEvents} can add them all to
     * the game's pack repositories and {@code PowerPackLoaderCommand} can list them, without either
     * needing to know which mods exist.
     * <p>
     * Copy-on-write because FML constructs mods in PARALLEL: several mods' constructors add to this
     * list at the same time, while the pack-finder event iterates it. A plain ArrayList would both
     * risk losing an entry and throw ConcurrentModificationException mid-iteration. Writes happen
     * once per loader at startup and reads are frequent, which is exactly this list's trade-off.
     */
    public static final List<PowerPackLoaderPacksLoader> ALL_LOADERS = new CopyOnWriteArrayList<>();

    // Instance-bound record for folder-based default packs
    public record DefaultResourceEntry(Class<?> modClass, String srcPath, String extraDirName) {}
    private final List<DefaultResourceEntry> defaultResources = new ArrayList<>();

    /**
     * Default packs whose source directory has already been resolved to a {@link Path}, via
     * NeoForge's own mod-file resource lookup.
     * <p>
     * Preferred over {@link DefaultResourceEntry}'s {@code Class#getResource} route, which cannot
     * see a DIRECTORY resource when a mod is loaded from exploded directories rather than a jar -
     * i.e. in every dev workspace. See {@code PackLoaderBuilder}.
     */
    public record DefaultPathEntry(Path source, String extraDirName) {}
    private final List<DefaultPathEntry> defaultPathResources = new ArrayList<>();

    public PackType packType;
    private final String directoryName;
    private final String modId;
    private final String metaFileName;
    private final Path rootFolder;

    // Cache lists & maps
    private final List<EnginePack> discoveredPacks = new ArrayList<>();
    private final Map<ResourceLocation, ResourceLocation> commandEngineIndex = new HashMap<>();
    private final Map<ResourceLocation, EnginePack> commandPackIndex = new HashMap<>();

    // packId -> every BlockItem registered for that pack by PowerNeoPackLoaderRegister, so
    // creative-tab registration (see PowerNeoPackLoaderCreativeTabRegisterer) can look up "give
    // me everything that came from pack X" without re-scanning the filesystem or reflecting into
    // the DeferredRegister that PowerNeoPackLoaderRegister used internally.
    private final Map<String, List<DeferredItem<? extends Item>>> packIdToItems = new LinkedHashMap<>();

    public PowerPackLoaderPacksLoader(PackType packType, String directoryName, String modId, String metaFileName) {
        this.packType = packType;
        this.directoryName = directoryName;
        this.modId = modId;
        this.metaFileName = metaFileName;

        // Path: powerpackloader/(modid)/(directoryName)
        this.rootFolder = FMLPaths.GAMEDIR.get()
                .resolve("powerpackloader")
                .resolve(modId)
                .resolve(directoryName);

        ALL_LOADERS.add(this);
    }

    public void addDefaultResource(Class<?> modClass, String srcPath, String extraDirName) {
        this.defaultResources.add(new DefaultResourceEntry(modClass, srcPath, extraDirName));
    }

    /** Registers a default pack whose source directory is already a {@link Path} - see
     *  {@link DefaultPathEntry}. */
    public void addDefaultResource(Path source, String extraDirName) {
        this.defaultPathResources.add(new DefaultPathEntry(source, extraDirName));
    }

    /**
     * Generates the root directory and copies default packs from the JAR early.
     * Called during mod initialization to ensure packs exist before the game scans.
     */
    public void setupAndExtractDefaults() {
        File folder = rootFolder.toFile();

        if (!folder.isDirectory()) {
            try {
                Files.createDirectories(rootFolder);
            } catch (Exception e) {
                PowerNeoPackLoader.LOGGER.warn(MARKER, "Failed to init directory structure at {}", rootFolder, e);
                return;
            }
        }

        // Path-resolved default packs (the normal route - see DefaultPathEntry).
        for (DefaultPathEntry entry : this.defaultPathResources) {
            Path targetDir = rootFolder.resolve(entry.extraDirName());
            if (!Files.isDirectory(targetDir)) {
                PowerNeoPackLoader.LOGGER.info(MARKER, "Copying default pack folder '{}' into {}",
                        entry.extraDirName(), targetDir);
                GetJarResources.copyModDirectory(entry.source(), rootFolder, entry.extraDirName());
            }
        }

        // Copy default pack folders from JAR assets into run directory if missing
        for (DefaultResourceEntry entry : this.defaultResources) {
            Path targetDir = rootFolder.resolve(entry.extraDirName());
            if (!Files.isDirectory(targetDir)) {
                PowerNeoPackLoader.LOGGER.info(MARKER, "Copying default pack folder '{}' from JAR into {}",
                        entry.extraDirName(), targetDir);
                GetJarResources.copyModDirectory(entry.modClass(), entry.srcPath(), rootFolder, entry.extraDirName());
            }
        }
    }

    public String getDirectoryName() {
        return this.directoryName;
    }

    public String getModId() {
        return this.modId;
    }

    public Path getRootFolder() {
        return this.rootFolder;
    }

    /**
     * Where generated resources (blockstate/item-model json synthesized for data-driven blocks
     * - see {@code PowerPackLoaderModelGenerator}) get written. Lives under its own
     * {@code generated} subfolder of the mod's powerpackloader directory - i.e.
     * {@code powerpackloader/<modId>/generated/<directoryName>_generated} - rather than as a
     * sibling of rootFolder directly, so it doesn't clutter the listing of real pack folders
     * (functional_blocks, decorative_blocks, ...) and the pack-folder scan in
     * {@code PowerNeoPackLoaderRegister} never mistakes it for a user pack. Merged back into the
     * client resource stack, unmapped, by {@link #discoverExtensions()} below - unlike every
     * discovered pack, this one is written directly under this mod's own real namespace already,
     * matching how the blocks it describes are actually registered, so it needs no
     * {@link NamespaceRewritingPackResources}.
     */
    public Path getGeneratedResourcesFolder() {
        return this.rootFolder.getParent().resolve("generated").resolve(this.directoryName + "_generated");
    }

    public String getMetaFile(){return this.metaFileName;}

    @Override
    public void loadPacks(Consumer<Pack> pOnLoad) {
        // Fallback for anyone using this loader directly as a RepositorySource without going
        // through PowerPackLoaderModBusEvents (see that class for why this field alone isn't
        // reliable): uses whatever PackType this loader was originally constructed with.
        Pack extensionsPack = discoverExtensions(this.packType);
        if (extensionsPack != null) {
            pOnLoad.accept(extensionsPack);
        }
    }

    /**
     * Builds the merged {@link Pack} for a specific side.
     * <p>
     * Takes {@code type} as a parameter rather than reading a stored field: this loader is a
     * single shared object added to the pack repository for BOTH {@code CLIENT_RESOURCES} and
     * {@code SERVER_DATA} (an integrated server fires {@link net.neoforged.neoforge.event.AddPackFindersEvent}
     * for each), so a mutable "current" packType would get overwritten by whichever side fired
     * last and silently corrupt the other - see {@code PowerPackLoaderModBusEvents} for the
     * registration side of this fix.
     */
    public Pack discoverExtensions(PackType type) {
        // Fallback check just in case a user deletes the folder while the game is running
        File folder = rootFolder.toFile();
        if (!folder.isDirectory()) {
            try {
                Files.createDirectories(rootFolder);
            } catch (Exception ignored) {}
        }

        PowerNeoPackLoader.LOGGER.info(MARKER, "Scanning for engine packs in {}", rootFolder);
        List<EnginePack> enginePacks = scanExtensions(rootFolder);

        discoveredPacks.clear();
        discoveredPacks.addAll(enginePacks);
        rebuildCommandEngineIndex(enginePacks);

        PowerNeoPackLoader.LOGGER.info(MARKER, "Found {} pack(s) in {}", enginePacks.size(), rootFolder);

        List<PackResources> extensionPacks = new ArrayList<>();

        for (EnginePack enginePack : enginePacks) {
            PackLocationInfo pInfo = new PackLocationInfo(enginePack.name(), Component.literal(enginePack.name()), PackSource.BUILT_IN, Optional.empty());
            PackResources raw = Files.isDirectory(enginePack.path())
                    ? new PathPackResources.PathResourcesSupplier(enginePack.path()).openPrimary(pInfo)
                    : new FilePackResources.FileResourcesSupplier(enginePack.path()).openPrimary(pInfo);

            PackResources packResources = new NamespaceRewritingPackResources(
                    pInfo, raw, this.modId, enginePack.id());

            extensionPacks.add(packResources);
        }

        Path generatedFolder = getGeneratedResourcesFolder();
        if (Files.isDirectory(generatedFolder)) {
            PackLocationInfo genInfo = new PackLocationInfo(this.directoryName + "_generated",
                    Component.literal(this.directoryName + " Generated"), PackSource.BUILT_IN, Optional.empty());
            extensionPacks.add(new PathPackResources.PathResourcesSupplier(generatedFolder).openPrimary(genInfo));
        }

        if (extensionPacks.isEmpty()) {
            return null;
        }

        PackLocationInfo info = new PackLocationInfo(this.directoryName + "_resources", Component.literal(this.directoryName + " Resources"), PackSource.BUILT_IN, Optional.empty());
        PackMetadataSection meta = new PackMetadataSection(Component.translatable(this.directoryName + ".resources.modresources"), SharedConstants.getCurrentVersion().getPackVersion(packType), Optional.empty());

        DelegatingPackResources pack = new DelegatingPackResources(info, meta, extensionPacks) {
            public IoSupplier<InputStream> getRootResource(String... paths) {
                if (paths.length == 1 && paths[0].equals("pack.png")) {
                    Path logoPath = getModIcon(modId);
                    if (logoPath != null) {
                        return IoSupplier.create(logoPath);
                    }
                }
                return null;
            }
        };

        PackSelectionConfig config = new PackSelectionConfig(true, Pack.Position.BOTTOM, false);
        return Pack.readMetaAndCreate(info, pack, packType, config);
    }

    public static @Nullable Path getModIcon(String modId) {
        Optional<? extends ModContainer> m = ModList.get().getModContainerById(modId);
        if (m.isPresent()) {
            IModInfo mod = m.get().getModInfo();
            IModFile file = mod.getOwningFile().getFile();
            if (file != null) {
                Path logoPath = file.findResource("icon.png");
                if (Files.exists(logoPath)) {
                    return logoPath;
                }
            }
        }
        return null;
    }

    private EnginePack fromDirPath(Path path) throws IOException {
        Path packInfoFilePath = path.resolve(this.metaFileName);
        if (!Files.exists(packInfoFilePath)) return null;

        try (InputStream stream = Files.newInputStream(packInfoFilePath)) {
            PackMeta info = GSON.fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), PackMeta.class);

            if (info == null || info.name == null || info.id == null) {
                PowerNeoPackLoader.LOGGER.warn(MARKER, "Failed to read info json or name/id missing: {}", packInfoFilePath.getFileName());
                return null;
            }
            return new EnginePack(path, info.name, info.id);
        } catch (IOException | JsonSyntaxException | JsonIOException exception) {
            PowerNeoPackLoader.LOGGER.warn(MARKER, "Failed to read info json: {}", packInfoFilePath.getFileName());
            PowerNeoPackLoader.LOGGER.warn(MARKER, exception.getMessage());
        }
        return null;
    }

    private EnginePack fromZipPath(Path path) {
        try (ZipFile zipFile = new ZipFile(path.toFile())) {
            ZipEntry extDescriptorEntry = zipFile.getEntry(this.metaFileName);
            if (extDescriptorEntry == null) {
                return null;
            }

            try (InputStream stream = zipFile.getInputStream(extDescriptorEntry)) {
                PackMeta info = GSON.fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), PackMeta.class);

                if (info == null || info.name == null || info.id == null) {
                    PowerNeoPackLoader.LOGGER.warn(MARKER, "Failed to read info json or name/id missing: {}", path.getFileName());
                    return null;
                }
                return new EnginePack(path, info.name, info.id);
            } catch (IOException | JsonSyntaxException | JsonIOException e) {
                PowerNeoPackLoader.LOGGER.error(MARKER, "Failed to load extension from ZIP {}. Error: {}", path.getFileName(), e);
                return null;
            }
        } catch (IOException e) {
            PowerNeoPackLoader.LOGGER.error(MARKER, "Failed to load extension from ZIP {}. Error: {}", path.getFileName(), e);
            return null;
        }
    }

    private List<EnginePack> scanExtensions(Path extensionsPath) {
        List<EnginePack> enginePacks = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(extensionsPath)) {
            for (Path entry : stream) {
                EnginePack enginePack = null;
                if (Files.isDirectory(entry)) {
                    enginePack = fromDirPath(entry);
                } else if (entry.toString().endsWith(".zip")) {
                    enginePack = fromZipPath(entry);
                }

                if (enginePack != null) {
                    PowerNeoPackLoader.LOGGER.info(MARKER, "- {}, Main namespace: {}", enginePack.path().getFileName(), enginePack.name());
                    enginePacks.add(enginePack);
                }
            }
        } catch (IOException e) {
            PowerNeoPackLoader.LOGGER.error(MARKER, "Failed to scan extensions from {}. Error: {}", extensionsPath, e);
        }

        return enginePacks;
    }

    public record EnginePack(Path path, String name, String id) {}

    public static class PackMeta {
        public String name;
        public String id;
        /** See PackRegistryNames - blocks of a pack with this set register without the pack
         *  prefix, as part of the mod's own content. */
        public boolean extend_original;
    }

    public List<EnginePack> getDiscoveredPacks() {
        return discoveredPacks;
    }

    /**
     * Records that {@code item} was registered for {@code packId}. Called by
     * {@code PowerNeoPackLoaderRegister} right after it registers each pack block's BlockItem.
     */
    public void registerPackItem(String packId, DeferredItem<? extends Item> item) {
        packIdToItems.computeIfAbsent(packId, k -> new ArrayList<>()).add(item);
    }

    /**
     * Wipes the packId -> items registry. Called before (re)scanning so a pack block that got
     * renamed/removed doesn't leave a stale item pointing at nothing, mirroring
     * {@code PowerPackLoaderModelGenerator#clear}.
     */
    public void clearPackItems() {
        packIdToItems.clear();
    }

    /**
     * Every item registered so far, grouped by the pack id it came from. Preserves discovery
     * order (both of pack ids and of items within a pack) since {@link #packIdToItems} is a
     * {@link LinkedHashMap}.
     */
    public Map<String, List<DeferredItem<? extends Item>>> getPackIdToItems() {
        return Collections.unmodifiableMap(packIdToItems);
    }

    public @Nullable ResourceLocation getCommandEngineId(ResourceLocation commandId) {
        return commandEngineIndex.get(commandId);
    }

    public Set<ResourceLocation> getCommandEngineIds() {
        return Collections.unmodifiableSet(commandEngineIndex.keySet());
    }

    public @Nullable EnginePack getCommandEnginePack(ResourceLocation commandId) {
        return commandPackIndex.get(commandId);
    }

    private void rebuildCommandEngineIndex(List<EnginePack> packs) {
        commandEngineIndex.clear();
        commandPackIndex.clear();
        for (EnginePack pack : packs) {
            try {
                scanPackEngines(pack, (engineName, engineId) -> {
                    ResourceLocation commandId = ResourceLocation.fromNamespaceAndPath(
                            this.modId, pack.id() + "_" + engineName.replace('/', '_'));
                    ResourceLocation existing = commandEngineIndex.putIfAbsent(commandId, engineId);
                    if (existing == null) {
                        commandPackIndex.put(commandId, pack);
                    } else if (!existing.equals(engineId)) {
                        PowerNeoPackLoader.LOGGER.error(MARKER,
                                "Command id '{}' is ambiguous between '{}' and '{}'.", commandId, existing, engineId);
                    }
                });
            } catch (Exception e) {
                PowerNeoPackLoader.LOGGER.warn(MARKER, "Could not index engines for pack '{}': {}", pack.id(), e.getMessage());
            }
        }
    }

    private static void scanPackEngines(EnginePack pack, java.util.function.BiConsumer<String, ResourceLocation> consumer) throws IOException {
        if (Files.isDirectory(pack.path())) {
            try (Stream<Path> entries = Files.walk(pack.path())) {
                entries.filter(Files::isRegularFile).forEach(path ->
                        indexEnginePath(pack.path().relativize(path).toString().replace('\\', '/'), consumer));
            }
        } else {
            try (ZipFile zip = new ZipFile(pack.path().toFile())) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (!entry.isDirectory()) indexEnginePath(entry.getName(), consumer);
                }
            }
        }
    }

    private static void indexEnginePath(String path, java.util.function.BiConsumer<String, ResourceLocation> consumer) {
        if (!path.startsWith("data/") || !path.endsWith(".json")) return;
        String[] parts = path.split("/", 4);
        if (parts.length != 4 || !parts[2].equals("engines")) return;
        String engineName = parts[3].substring(0, parts[3].length() - ".json".length());
        if (engineName.isEmpty()) return;
        try {
            consumer.accept(engineName, ResourceLocation.fromNamespaceAndPath(parts[1], engineName));
        } catch (Exception ignored) {
        }
    }
}