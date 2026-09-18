package com.fennek.powerneopackloader.CoreComponentes;

import com.fennek.powerneopackloader.PowerNeoPackLoader;
import com.fennek.powerneopackloader.loadingPacks.PowerPackLoaderPacksLoader;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import net.neoforged.fml.loading.FMLPaths;

import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

public class ResourceLocator {

    private static final Gson GSON = new Gson();
    private final PowerPackLoaderPacksLoader loader;

    public ResourceLocator(PowerPackLoaderPacksLoader loader) {
        this.loader = loader;
    }

    public PowerPackLoaderPacksLoader getLoader() {
        return this.loader;
    }

    /**
     * Resolves the exact path route:
     * powerpackloader / (mod id) / (loader folder) / (custom modder file location)
     */
    public Path resolvePath(String customFileLocation) {
        return FMLPaths.GAMEDIR.get()
                .resolve("powerpackloader")
                .resolve(this.loader.getModId())           // Gets (mod id)
                .resolve(this.loader.getDirectoryName())   // Gets (loader folder)
                .resolve(customFileLocation);              // Gets (custom modder file location)
    }

    public String getDefaultPath(){
        return FMLPaths.GAMEDIR.get()
                .resolve("powerpackloader")
                .resolve(this.loader.getModId())           // Gets (mod id)
                .resolve(this.loader.getDirectoryName())
                .toString();
    }

    /**
     * Gets the raw Path to the file if it exists and is not a directory.
     *
     * @param customFileLocation The input of the player, e.g., "configs/test.json"
     */
    public Optional<Path> getFilePath(String customFileLocation) {
        Path targetPath = resolvePath(customFileLocation);

        if (Files.exists(targetPath) && !Files.isDirectory(targetPath)) {
            return Optional.of(targetPath);
        }

        PowerNeoPackLoader.LOGGER.warn("File not found at: {}", targetPath.toAbsolutePath());
        return Optional.empty();
    }

    /**
     * Opens an InputStream for any file format.
     */
    public Optional<InputStream> getFileInputStream(String customFileLocation) {
        Optional<Path> path = getFilePath(customFileLocation);

        if (path.isPresent()) {
            try {
                return Optional.of(Files.newInputStream(path.get()));
            } catch (Exception e) {
                PowerNeoPackLoader.LOGGER.error("Failed to open file stream for: {}", customFileLocation, e);
            }
        }
        return Optional.empty();
    }

    // =========================================================================
    // SPECIFIC FILE TYPE READERS
    // =========================================================================

    /**
     * Reads and parses a .json file into a Gson JsonObject.
     */
    public Optional<JsonObject> getJson(String customFileLocation) {
        return getJson(customFileLocation, JsonObject.class);
    }

    /**
     * Reads a .json file and parses it into a custom Class/Record.
     */
    public <T> Optional<T> getJson(String customFileLocation, Class<T> clazz) {
        Optional<Path> path = getFilePath(customFileLocation);
        if (path.isPresent()) {
            try (Reader reader = Files.newBufferedReader(path.get(), StandardCharsets.UTF_8)) {
                T result = GSON.fromJson(reader, clazz);
                return Optional.ofNullable(result);
            } catch (Exception e) {
                PowerNeoPackLoader.LOGGER.error("Failed to parse JSON file at: {}", customFileLocation, e);
            }
        }
        return Optional.empty();
    }

    /**
     * Reads text-based files (.txt, .svg, .xml, .md) into a String.
     */
    public Optional<String> getText(String customFileLocation) {
        Optional<Path> path = getFilePath(customFileLocation);
        if (path.isPresent()) {
            try {
                return Optional.of(Files.readString(path.get(), StandardCharsets.UTF_8));
            } catch (Exception e) {
                PowerNeoPackLoader.LOGGER.error("Failed to read text file at: {}", customFileLocation, e);
            }
        }
        return Optional.empty();
    }

    /**
     * Reads a text file line-by-line into a List of Strings.
     */
    public Optional<List<String>> getTextLines(String customFileLocation) {
        Optional<Path> path = getFilePath(customFileLocation);
        if (path.isPresent()) {
            try {
                return Optional.of(Files.readAllLines(path.get(), StandardCharsets.UTF_8));
            } catch (Exception e) {
                PowerNeoPackLoader.LOGGER.error("Failed to read text lines at: {}", customFileLocation, e);
            }
        }
        return Optional.empty();
    }

    /**
     * Loads image files (.png) as Minecraft's NativeImage.
     * Note: Remember to call .close() on the NativeImage when done to avoid memory leaks!
     */
    public Optional<NativeImage> getImage(String customFileLocation) {
        Optional<InputStream> stream = getFileInputStream(customFileLocation);
        if (stream.isPresent()) {
            try (InputStream is = stream.get()) {
                return Optional.of(NativeImage.read(is));
            } catch (Exception e) {
                PowerNeoPackLoader.LOGGER.error("Failed to load NativeImage from: {}", customFileLocation, e);
            }
        }
        return Optional.empty();
    }

    /**
     * Reads raw bytes from any binary file (.dat, .bin, raw images, etc.).
     */
    public Optional<byte[]> getBytes(String customFileLocation) {
        Optional<Path> path = getFilePath(customFileLocation);
        if (path.isPresent()) {
            try {
                return Optional.of(Files.readAllBytes(path.get()));
            } catch (Exception e) {
                PowerNeoPackLoader.LOGGER.error("Failed to read raw bytes from: {}", customFileLocation, e);
            }
        }
        return Optional.empty();
    }

    /**
     * Reads .properties configuration files into a Java Properties map.
     */
    public Optional<Properties> getProperties(String customFileLocation) {
        Optional<InputStream> stream = getFileInputStream(customFileLocation);
        if (stream.isPresent()) {
            try (InputStream is = stream.get()) {
                Properties properties = new Properties();
                properties.load(is);
                return Optional.of(properties);
            } catch (Exception e) {
                PowerNeoPackLoader.LOGGER.error("Failed to parse properties file from: {}", customFileLocation, e);
            }
        }
        return Optional.empty();
    }
}