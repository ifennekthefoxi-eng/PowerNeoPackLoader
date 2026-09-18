package com.fennek.powerneopackloader.loadingPacks;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.fennek.powerneopackloader.PowerNeoPackLoader;
import com.fennek.powerneopackloader.Registration.PowerPackLoaderRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@EventBusSubscriber(modid = PowerNeoPackLoader.MOD_ID)
public class PowerPackLoaderCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(
                Commands.literal("pnpl")
                        .requires(source -> source.hasPermission(2))

                        // /pnpl blocks [mod_id] - what actually got registered. The first thing to
                        // check when a pack block doesn't show up in game: it separates "the pack
                        // was never read" from "the block json was rejected" from "it registered
                        // fine and the problem is its model or renderer".
                        .then(Commands.literal("blocks")
                                .executes(context -> listBlocks(context, null))
                                .then(Commands.argument("mod_id", StringArgumentType.string())
                                        .suggests(PowerPackLoaderCommand::suggestModIds)
                                        .executes(context -> listBlocks(context, StringArgumentType.getString(context, "mod_id")))
                                )
                        )

                        // /pnpl <loader_name>
                        .then(Commands.argument("loader", StringArgumentType.string())
                                .suggests(PowerPackLoaderCommand::suggestLoaderNames)

                                // /pnpl <loader_name> packs
                                .then(Commands.literal("packs")
                                        .executes(context -> listPacks(context, StringArgumentType.getString(context, "loader")))
                                )

                                // /pnpl <loader_name> inspect <pack_name> [subfolder]
                                .then(Commands.literal("inspect")
                                        .then(Commands.argument("pack_name", StringArgumentType.string())
                                                .suggests(PowerPackLoaderCommand::suggestPackNames)
                                                .executes(context -> inspectPack(context,
                                                        StringArgumentType.getString(context, "loader"),
                                                        StringArgumentType.getString(context, "pack_name"),
                                                        ""))
                                                .then(Commands.argument("subfolder", StringArgumentType.greedyString())
                                                        .suggests(PowerPackLoaderCommand::suggestSubfolders)
                                                        .executes(context -> inspectPack(context,
                                                                StringArgumentType.getString(context, "loader"),
                                                                StringArgumentType.getString(context, "pack_name"),
                                                                StringArgumentType.getString(context, "subfolder")))
                                                )
                                        )
                                )
                        )
        );
    }

    // --- HELPER LOGIC ---

    private static PowerPackLoaderPacksLoader getLoader(String loaderName) {
        for (PowerPackLoaderPacksLoader loader : PowerPackLoaderPacksLoader.ALL_LOADERS) {
            if (loader.getDirectoryName().equalsIgnoreCase(loaderName)) {
                return loader;
            }
        }
        return null;
    }

    // --- TAB COMPLETION LOGIC ---

    private static CompletableFuture<Suggestions> suggestLoaderNames(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        List<String> loaders = PowerPackLoaderPacksLoader.ALL_LOADERS.stream()
                .map(PowerPackLoaderPacksLoader::getDirectoryName)
                .toList();

        return SharedSuggestionProvider.suggest(loaders, builder);
    }

    private static CompletableFuture<Suggestions> suggestPackNames(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        String loaderName;
        try {
            loaderName = StringArgumentType.getString(context, "loader");
        } catch (IllegalArgumentException e) {
            return Suggestions.empty();
        }

        PowerPackLoaderPacksLoader loader = getLoader(loaderName);
        if (loader == null) return Suggestions.empty();

        List<String> packNames = loader.getDiscoveredPacks().stream()
                .map(PowerPackLoaderPacksLoader.EnginePack::name)
                .map(name -> name.contains(" ") ? "\"" + name + "\"" : name)
                .toList();

        return SharedSuggestionProvider.suggest(packNames, builder);
    }

    private static CompletableFuture<Suggestions> suggestSubfolders(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        String loaderName;
        String packName;
        try {
            loaderName = StringArgumentType.getString(context, "loader");
            packName = StringArgumentType.getString(context, "pack_name");
        } catch (IllegalArgumentException e) {
            return Suggestions.empty();
        }

        PowerPackLoaderPacksLoader loader = getLoader(loaderName);
        if (loader == null) return Suggestions.empty();

        PowerPackLoaderPacksLoader.EnginePack targetPack = loader.getDiscoveredPacks().stream()
                .filter(pack -> pack.name().equalsIgnoreCase(packName))
                .findFirst()
                .orElse(null);

        if (targetPack == null) return Suggestions.empty();

        Set<String> folderPaths = new HashSet<>();
        Path packPath = targetPack.path();

        try {
            if (Files.isDirectory(packPath)) {
                try (Stream<Path> paths = Files.walk(packPath)) {
                    paths.filter(Files::isDirectory).forEach(p -> {
                        if (!p.equals(packPath)) {
                            String relPath = packPath.relativize(p).toString().replace("\\", "/") + "/";
                            folderPaths.add(relPath);
                        }
                    });
                }
            } else {
                try (ZipFile zip = new ZipFile(packPath.toFile())) {
                    for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                        ZipEntry entry = e.nextElement();
                        String name = entry.getName();
                        if (entry.isDirectory()) {
                            folderPaths.add(name);
                        } else {
                            int lastSlash = name.lastIndexOf('/');
                            if (lastSlash != -1) {
                                folderPaths.add(name.substring(0, lastSlash + 1));
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            // Ignore errors during tab completion
        }

        return SharedSuggestionProvider.suggest(folderPaths, builder);
    }

    private static CompletableFuture<Suggestions> suggestModIds(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        Set<String> modIds = new TreeSet<>();
        for (PowerPackLoaderPacksLoader loader : PowerPackLoaderPacksLoader.ALL_LOADERS) {
            modIds.add(loader.getModId());
        }
        return SharedSuggestionProvider.suggest(modIds, builder);
    }

    // --- COMMAND EXECUTION LOGIC ---

    /**
     * Lists every block this library registered from a pack, grouped by pack, with its real
     * registry id and whether it got a block entity - i.e. exactly the facts needed to tell which
     * stage of the pipeline a missing block fell out of.
     *
     * @param modId the mod to list, or null for every mod using the library.
     */
    private static int listBlocks(CommandContext<CommandSourceStack> context, String modId) {
        CommandSourceStack source = context.getSource();

        Set<String> modIds = new TreeSet<>();
        if (modId != null) {
            modIds.add(modId);
        } else {
            for (PowerPackLoaderPacksLoader loader : PowerPackLoaderPacksLoader.ALL_LOADERS) {
                modIds.add(loader.getModId());
            }
        }

        int total = 0;
        for (String id : modIds) {
            List<String> packIds = PowerPackLoaderRegistry.packIds(id);
            if (packIds.isEmpty()) {
                source.sendSuccess(() -> Component.literal("§7No pack blocks registered for §e" + id), false);
                continue;
            }
            source.sendSuccess(() -> Component.literal("§a--- Pack blocks for [" + id + "] ---"), false);
            for (String packId : packIds) {
                var entries = PowerPackLoaderRegistry.byPack(id, packId);
                source.sendSuccess(() -> Component.literal("§6" + packId + " §7(" + entries.size() + ")"), false);
                for (var entry : entries) {
                    String suffix = entry.entityType() != null ? " §8[block entity]" : "";
                    source.sendSuccess(() -> Component.literal("§7  - §f" + entry.id() + suffix), false);
                }
                total += entries.size();
            }
        }

        int shown = total;
        source.sendSuccess(() -> Component.literal("§a" + shown + " pack block(s) registered."), false);
        return 1;
    }

    private static int listPacks(CommandContext<CommandSourceStack> context, String loaderName) {
        CommandSourceStack source = context.getSource();
        PowerPackLoaderPacksLoader loader = getLoader(loaderName);

        if (loader == null) {
            source.sendFailure(Component.literal("§cUnknown pack loader '" + loaderName + "'!"));
            return 0;
        }

        var packs = loader.getDiscoveredPacks();

        if (packs.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§cNo custom packs found for loader '" + loaderName + "'!"), false);
            return 1;
        }

        source.sendSuccess(() -> Component.literal("§a--- Loaded Packs for [" + loaderName + "] (" + packs.size() + ") ---"), false);
        for (var pack : packs) {
            source.sendSuccess(() -> Component.literal("§7- §e" + pack.name() + " §f(" + pack.path().getFileName() + ")"), false);
        }
        return 1;
    }

    private static int inspectPack(CommandContext<CommandSourceStack> context, String loaderName, String packName, String subfolder) {
        CommandSourceStack source = context.getSource();
        PowerPackLoaderPacksLoader loader = getLoader(loaderName);

        if (loader == null) {
            source.sendFailure(Component.literal("§cUnknown pack loader '" + loaderName + "'!"));
            return 0;
        }

        var packs = loader.getDiscoveredPacks();

        PowerPackLoaderPacksLoader.EnginePack targetPack = packs.stream()
                .filter(pack -> pack.name().equalsIgnoreCase(packName))
                .findFirst()
                .orElse(null);

        if (targetPack == null) {
            source.sendFailure(Component.literal("§cCould not find a loaded pack named '" + packName + "' in loader '" + loaderName + "'."));
            return 1;
        }

        String normalizedSubfolder = subfolder.replace("\\", "/");
        if (normalizedSubfolder.startsWith("/")) normalizedSubfolder = normalizedSubfolder.substring(1);
        if (!normalizedSubfolder.isEmpty() && !normalizedSubfolder.endsWith("/")) normalizedSubfolder += "/";

        Path packPath = targetPack.path();
        Set<String> foundEntries = new TreeSet<>();

        try {
            if (Files.isDirectory(packPath)) {
                Path targetPath = packPath.resolve(normalizedSubfolder);
                if (!Files.exists(targetPath) || !Files.isDirectory(targetPath)) {
                    source.sendFailure(Component.literal("§cSubfolder '" + normalizedSubfolder + "' does not exist in this pack."));
                    return 1;
                }

                try (DirectoryStream<Path> stream = Files.newDirectoryStream(targetPath)) {
                    for (Path p : stream) {
                        if (Files.isDirectory(p)) {
                            foundEntries.add("§e📁 " + p.getFileName().toString() + "/");
                        } else {
                            foundEntries.add("§f📄 " + p.getFileName().toString());
                        }
                    }
                }
            } else {
                try (ZipFile zip = new ZipFile(packPath.toFile())) {
                    boolean foundAny = false;
                    for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                        ZipEntry entry = e.nextElement();
                        String name = entry.getName();

                        if (name.startsWith(normalizedSubfolder)) {
                            foundAny = true;
                            String remainder = name.substring(normalizedSubfolder.length());
                            if (remainder.isEmpty()) continue;

                            int slashIndex = remainder.indexOf('/');
                            if (slashIndex == -1) {
                                foundEntries.add("§f📄 " + remainder);
                            } else {
                                foundEntries.add("§e📁 " + remainder.substring(0, slashIndex) + "/");
                            }
                        }
                    }
                    if (!foundAny && !normalizedSubfolder.isEmpty()) {
                        source.sendFailure(Component.literal("§cSubfolder '" + normalizedSubfolder + "' does not exist in this zip pack."));
                        return 1;
                    }
                }
            }
        } catch (IOException e) {
            source.sendFailure(Component.literal("§cFailed to read pack contents: " + e.getMessage()));
            return 1;
        }

        String displayPath = normalizedSubfolder.isEmpty() ? "(root)" : normalizedSubfolder;
        source.sendSuccess(() -> Component.literal("§a--- Contents of [" + loaderName + "] " + targetPack.name() + " @ " + displayPath + " ---"), false);

        if (foundEntries.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§7(Empty folder)"), false);
        } else {
            for (String entry : foundEntries) {
                source.sendSuccess(() -> Component.literal("  " + entry), false);
            }
        }

        return 1;
    }
}