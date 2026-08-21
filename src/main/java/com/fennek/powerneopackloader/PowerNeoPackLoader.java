package com.fennek.powerneopackloader;

import com.fennek.powerneopackloader.APIBridge.PowerPackLoaderApi;
import com.fennek.powerneopackloader.CoreComponentes.ClassPicker;
import com.fennek.powerneopackloader.CoreComponentes.EntityPicker;
import com.fennek.powerneopackloader.Examples.ChestLoadingExample;
import com.fennek.powerneopackloader.Examples.FurnaceLoadingExample;
import com.fennek.powerneopackloader.Examples.LoadableFurnaceBlockEntity;
import com.fennek.powerneopackloader.Registration.PowerNeoPackLoaderRegister;
import com.fennek.powerneopackloader.loadingPacks.PowerPackLoaderPacksLoader;
import com.fennek.powerneopackloader.CoreComponentes.LoadableModelHorizontalDirectionalBlock;
import com.fennek.powerneopackloader.Registration.PowerNeoPackLoaderCreativeTabRegisterer;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(PowerNeoPackLoader.MOD_ID)
public class PowerNeoPackLoader {

    public static final String MOD_ID = "powerneopackloader";
    public static final Logger LOGGER = LogManager.getLogger();
    public static ModContainer container;
    public static ClassPicker ClassSelector;
    public static EntityPicker EntitySelector;

    public static PowerPackLoaderPacksLoader functionalLoader;
    public static PowerPackLoaderPacksLoader decorativeLoader;
    public static PowerPackLoaderPacksLoader miscLoader;

    public PowerNeoPackLoader(IEventBus modBus, ModContainer container) {
        PowerNeoPackLoader.container = container;

        ClassSelector = new ClassPicker();
        EntitySelector = new EntityPicker();

        ClassSelector.AddClass("ExampleChestBlock", ChestLoadingExample.class);
        EntitySelector.AddEntity(ChestLoadingExample.class, ChestBlockEntity.class);

        ClassSelector.AddClass("ExampleFurnaceBlock", FurnaceLoadingExample.class);
        EntitySelector.AddEntity(FurnaceLoadingExample.class, LoadableFurnaceBlockEntity.class);

        // 1. Register loaders via API
        functionalLoader = PowerPackLoaderApi.registerLoader(MOD_ID, "functional_blocks", "functional.meta.json");
        decorativeLoader = PowerPackLoaderApi.registerLoader(MOD_ID, "decorative_blocks", "decorative.meta.json");
        miscLoader = PowerPackLoaderApi.registerLoader(MOD_ID, "misc_blocks", "misc.meta.json");

        // 2. Register default pack folder (extracts files to the directory immediately)
        PowerPackLoaderApi.copyDefaultPack(functionalLoader, PowerNeoPackLoader.class, MOD_ID, "functional_pack");
        ///NOTE THE LINE BELOW THIS LINE IS JUST COMENTED TILL FULL BLOCK LOADING IS ACHIEVED
        //PowerPackLoaderApi.copyDefaultPack(decorativeLoader, PowerNeoPackLoader.class, MOD_ID, "decorative_pack");
        ///NOTE THE LINE BELOW THIS LINE IS JUST COMENTED TILL FULL BLOCK LOADING IS ACHIEVED
        //PowerPackLoaderApi.copyDefaultPack(miscLoader, PowerNeoPackLoader.class, MOD_ID, "misc_pack");

        // 3. Scan the newly extracted folders to dynamically register blocks and items
        PowerNeoPackLoaderRegister.RegisterBlocksFromPackLoader(functionalLoader,ClassSelector,EntitySelector,MOD_ID,modBus);

        // 4. Register creative tabs for whatever just got registered above - must run AFTER
        // step 3, since it reads the packId -> items map that step 3 just populated.
        PowerNeoPackLoaderCreativeTabRegisterer creativeTabRegisterer = new PowerNeoPackLoaderCreativeTabRegisterer();
        creativeTabRegisterer.RegisterCreativeTabsForEachPackId(functionalLoader, MOD_ID, modBus);
        creativeTabRegisterer.RegisterCreativeTabForAllBlocksInLoader(functionalLoader, MOD_ID, modBus);

        // 5. Register DeferredRegisters to the mod bus
        LoadableModelHorizontalDirectionalBlock.CODECS.register(modBus);
        // These two were being built but never actually handed to the mod bus - harmless in dev
        // (block/item/entity registration doesn't depend on it), but the codec is still what lets
        // these block classes round-trip through the BLOCK_TYPE registry correctly, so it should
        // be registered like every other DeferredRegister here.
        ChestLoadingExample.CODECS.register(modBus);
        FurnaceLoadingExample.CODECS.register(modBus);



        LOGGER.info("Successfully initialized {} pack loaders for {}", PowerPackLoaderPacksLoader.ALL_LOADERS.size(), MOD_ID);
    }
}