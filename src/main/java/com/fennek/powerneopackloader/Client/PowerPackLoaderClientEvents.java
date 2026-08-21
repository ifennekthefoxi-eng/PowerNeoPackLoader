package com.fennek.powerneopackloader.Client;

import com.fennek.powerneopackloader.PowerNeoPackLoader;
import com.fennek.powerneopackloader.Registration.PowerNeoPackLoaderRegister;
import net.minecraft.client.renderer.blockentity.ChestRenderer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

import java.util.Map;

/**
 * THE ACTUAL CHEST-VISIBILITY FIX.
 * <p>
 * Registering a {@link BlockEntityType} (what {@code PowerNeoPackLoaderRegister} does) and
 * registering a renderer FOR a {@link BlockEntityType} are two completely separate steps in
 * vanilla - one goes through the block entity type registry, the other goes through
 * {@code EntityRenderersEvent.RegisterRenderers}, fired once on the client only. Nothing before
 * this class ever touched the second one, which is exactly why {@code ChestLoadingExample} placed
 * and opened correctly (block + block entity + menu all work) but rendered as nothing: an
 * invisible chest isn't a missing-JSON problem, because chests were never drawn from a JSON model
 * to begin with. Chests are one of the handful of vanilla blocks whose actual 3D shape (the
 * separate lid + base, the opening animation) is baked into code and drawn by a
 * {@link ChestRenderer} keyed to the exact {@code BlockEntityType} instance - vanilla only ever
 * wires that renderer up for {@code BlockEntityType.CHEST} and {@code .TRAPPED_CHEST}. Every pack
 * chest gets its OWN distinct {@code BlockEntityType} (see {@code ChestLoadingExample}'s class doc
 * for why it has to), so none of them inherit vanilla's wiring - each one needs the SAME renderer
 * class registered against ITS OWN type here.
 * <p>
 * This is also why furnaces don't need anything here at all: {@code FurnaceLoadingExample} renders
 * through a normal baked block model (blockstate JSON + textures, lit/unlit variants), the same as
 * any ordinary block - furnaces have no {@code BlockEntityRenderer} in vanilla in the first place.
 * "Does this vanilla block have its own BlockEntityRenderer class?" is the question to ask before
 * assuming a pack block needs an entry in this file.
 */
@EventBusSubscriber(modid = PowerNeoPackLoader.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class PowerPackLoaderClientEvents {

    @SuppressWarnings({"unchecked", "rawtypes"})
    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        for (Map.Entry<ResourceLocation, Class<?>> entry : PowerNeoPackLoaderRegister.REGISTERED_ENTITY_CLASSES.entrySet()) {
            ResourceLocation registryId = entry.getKey();
            Class<?> entityClass = entry.getValue();

            // By RegisterRenderers time (client setup) every BlockEntityType from the earlier
            // RegisterEvent pass is already in the registry - this lookup is just translating the
            // id we stashed back into the live instance the renderer API wants.
            BlockEntityType<?> ownType = BuiltInRegistries.BLOCK_ENTITY_TYPE.get(registryId);
            if (ownType == null) {
                PowerNeoPackLoader.LOGGER.warn(
                        "No BlockEntityType found for {} while registering renderers - skipping.", registryId);
                continue;
            }

            if (ChestBlockEntity.class.isAssignableFrom(entityClass)) {
                // ChestRenderer works off the ChestBlockEntity instance's own data (open progress,
                // whether it's part of a double chest, etc.) - it doesn't care which
                // BlockEntityType it's attached to, so vanilla's own renderer class is reusable
                // as-is. No new renderer class needed, just this registration.
                event.registerBlockEntityRenderer((BlockEntityType) ownType, ChestRenderer::new);
                PowerNeoPackLoader.LOGGER.info("Registered ChestRenderer for pack block entity type {}", registryId);
            }

            // Add more `else if (SomeVanillaEntityClass.isAssignableFrom(entityClass)) { ... }`
            // branches here only for base classes that vanilla itself draws via a
            // BlockEntityRenderer (chest, bed, banner, sign, shulker box, decorated pot, etc).
            // Anything rendered from a normal baked block model - furnaces included - needs no
            // entry here at all.
        }
    }
}
