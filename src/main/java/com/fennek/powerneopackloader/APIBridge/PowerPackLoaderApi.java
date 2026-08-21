package com.fennek.powerneopackloader.APIBridge;

import com.fennek.powerneopackloader.loadingPacks.PowerPackLoaderPacksLoader;
import net.minecraft.server.packs.PackType;
import net.neoforged.fml.loading.FMLLoader;

public class PowerPackLoaderApi {

    /**
     * Registers and creates a new custom folder loader.
     * Generates folders under: /powerpackloader/(modId)/(loaderFolder)/
     */
    public static PowerPackLoaderPacksLoader registerLoader(String modId, String loaderFolder, String metaFileName) {
        PackType packType = FMLLoader.getDist().isClient() ? PackType.CLIENT_RESOURCES : PackType.SERVER_DATA;
        return new PowerPackLoaderPacksLoader(packType, loaderFolder, modId, metaFileName);
    }

    /**
     * Copies a default pack directory from assets/(modId)/default_packs/(folderName)
     * into the loader's destination directory if it doesn't already exist.
     *
     * @param loader The target CACWEngineLoader instance
     * @param modMainClass Main mod class for JAR resource stream access
     * @param modId Your mod ID
     * @param defaultPackFolderName Folder name inside assets/(modId)/default_packs/ (e.g., "cacw_default_engines")
     */
    public static void copyDefaultPack(PowerPackLoaderPacksLoader loader, Class<?> modMainClass, String modId, String defaultPackFolderName) {
        String jarSourcePath = String.format("/assets/%s/default_packs/%s", modId, defaultPackFolderName);
        loader.addDefaultResource(modMainClass, jarSourcePath, defaultPackFolderName);

        // Force the extraction immediately during mod init, before world/game loads
        loader.setupAndExtractDefaults();
    }
}