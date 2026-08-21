package com.fennek.powerneopackloader.Registration;

import com.fennek.powerneopackloader.PowerNeoPackLoader;
import com.fennek.powerneopackloader.loadingPacks.PowerPackLoaderPacksLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates a fallback English translation for a pack's creative tab title
 * (translation key {@code itemGroup.<packId>}) - but ONLY when the pack doesn't already ship its
 * own {@code assets/<packId>/lang/} folder. A pack author supplying their own lang files (exactly
 * like vanilla: {@code en_us.json}, {@code es_es.json}, etc.) always wins outright and nothing is
 * generated for that pack at all, so there's never a collision between the two.
 * <p>
 * Output lands under {@link PowerPackLoaderPacksLoader#getGeneratedResourcesFolder()} - a SIBLING
 * of the pack's own folder, never a file dropped directly into it, so a pack folder never ends up
 * mixed with content this loader invented itself. That generated folder is wiped in full on every
 * scan by {@link PowerPackLoaderModelGenerator#clear(PowerPackLoaderPacksLoader)} before any of
 * this runs, so a fallback lang file left behind by a pack that got a real one added later (or
 * removed) never lingers.
 */
public final class PowerPackLoaderLangGenerator {

    private PowerPackLoaderLangGenerator() {
    }

    /**
     * @param loader     the loader this pack belongs to - only used to locate the generated
     *                   resources folder to write into.
     * @param packFolder the specific pack's own root folder (e.g. ".../functional_pack").
     * @param packId     the pack's own id from its meta json - also its asset namespace, and the
     *                   suffix of the translation key its creative tab title uses.
     */
    public static void generate(PowerPackLoaderPacksLoader loader, Path packFolder, String packId) {
        Path packLangFolder = packFolder.resolve("assets").resolve(packId).resolve("lang");
        if (Files.isDirectory(packLangFolder)) {
            // The pack already brings its own translations - trust it completely and generate
            // nothing, so we never fight the pack author's own en_us.json/es_es.json/etc.
            return;
        }

        Path target = loader.getGeneratedResourcesFolder()
                .resolve("assets").resolve(packId).resolve("lang").resolve("en_us.json");

        String displayName = humanize(packId);
        String json = "{\n  \"itemGroup." + packId + "\": \"" + escapeJson(displayName) + "\"\n}\n";

        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            PowerNeoPackLoader.LOGGER.error("Failed to write generated lang fallback '{}': {}", target, e.getMessage());
        }
    }

    /** "artics_blocks" -&gt; "Artics Blocks" */
    private static String humanize(String packId) {
        String[] words = packId.replace('-', '_').split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (result.length() > 0) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) result.append(word.substring(1).toLowerCase());
        }
        return result.length() == 0 ? packId : result.toString();
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
