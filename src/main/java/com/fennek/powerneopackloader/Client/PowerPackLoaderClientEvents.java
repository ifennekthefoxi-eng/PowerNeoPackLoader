package com.fennek.powerneopackloader.Client;

import com.fennek.powerneopackloader.CoreComponentes.GeckoLibCompat;
import com.fennek.powerneopackloader.CoreComponentes.RendererPicker;
import com.fennek.powerneopackloader.PowerNeoPackLoader;
import com.fennek.powerneopackloader.Registration.PowerNeoPackLoaderRegister;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.ChestRenderer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

import java.lang.reflect.Constructor;
import java.util.Map;

/**
 * Gives every pack block entity - from any mod - the renderer it needs, once, here.
 * <p>
 * Registering a {@link BlockEntityType} (what {@code PowerNeoPackLoaderRegister} does) and
 * registering a renderer FOR a {@link BlockEntityType} are two completely separate steps in
 * vanilla: one goes through the block entity type registry, the other through
 * {@code EntityRenderersEvent.RegisterRenderers}, fired on the client only. A pack block that
 * misses the second one places, ticks, saves and opens perfectly while rendering as nothing at all
 * - it is not a missing-model problem and no amount of blockstate json fixes it. And because every
 * pack block gets its own freshly-created {@code BlockEntityType} (it has to - vanilla validates a
 * placed block against its type's valid-blocks list), no pack block ever inherits a renderer
 * registration made for some other type, not even when it reuses a vanilla entity class outright.
 * <p>
 * Three sources of renderer, in priority order:
 * <ol>
 *     <li>{@link RendererPicker#GLOBAL} - whatever the modder declared with {@code @PackRenderer}
 *     or {@code @PackBlock(renderer = ...)}. An explicit declaration always wins;</li>
 *     <li>vanilla {@link ChestRenderer}, for anything extending {@link ChestBlockEntity}. Chests
 *     are one of the handful of vanilla blocks whose 3D shape (separate lid and base, the opening
 *     animation) is baked into code rather than a model, and vanilla only ever wires that renderer
 *     up for its own two chest types;</li>
 *     <li>GeckoLib's {@code GeoBlockRenderer}, for GeckoLib entities - delegated to
 *     {@link GeckoLibClientEvents} so this class never resolves a GeckoLib type on an install
 *     without GeckoLib.</li>
 * </ol>
 * Blocks drawn from an ordinary baked model - furnaces, stairs, most pack blocks - need none of
 * this; "does this block's look come from a BlockEntityRenderer?" is the question to ask.
 * <p>
 * Subscribed to this library's own mod bus, not each consuming mod's: {@code RegisterRenderers}
 * fires for every mod, and {@code REGISTERED_ENTITY_CLASSES} already holds every pack block entity
 * in the game, so one listener covers all of them. Consuming mods add nothing.
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

            Class<?> declaredRenderer = RendererPicker.GLOBAL.getRendererByEntityClass(entityClass);
            if (declaredRenderer != null) {
                registerDeclared(event, ownType, declaredRenderer, registryId);
            } else if (ChestBlockEntity.class.isAssignableFrom(entityClass)) {
                // ChestRenderer works off the ChestBlockEntity instance's own data (open progress,
                // whether it's part of a double chest, etc.) - it doesn't care which
                // BlockEntityType it's attached to, so vanilla's own renderer class is reusable
                // as-is. No new renderer class needed, just this registration.
                event.registerBlockEntityRenderer((BlockEntityType) ownType, ChestRenderer::new);
                PowerNeoPackLoader.LOGGER.info("Registered ChestRenderer for pack block entity type {}", registryId);
            } else if (GeckoLibCompat.LOADED) {
                GeckoLibClientEvents.tryRegister(event, ownType, entityClass, registryId);
            }
        }
    }

    /**
     * Registers a modder-declared renderer class against {@code ownType}, constructing it
     * reflectively through the {@code (BlockEntityRendererProvider.Context)} constructor every
     * vanilla renderer already has.
     * <p>
     * The constructor is looked up NOW, while registering, rather than inside the provider lambda:
     * a renderer class with the wrong shape is then reported once at startup with the class name in
     * the message, instead of throwing from deep inside the render loop the first time one of these
     * blocks comes into view.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void registerDeclared(EntityRenderersEvent.RegisterRenderers event, BlockEntityType<?> ownType,
                                         Class<?> rendererClass, ResourceLocation registryId) {
        Constructor<?> ctor;
        try {
            ctor = rendererClass.getDeclaredConstructor(BlockEntityRendererProvider.Context.class);
            ctor.setAccessible(true);
        } catch (NoSuchMethodException e) {
            PowerNeoPackLoader.LOGGER.error(
                    "{} has no (BlockEntityRendererProvider.Context) constructor - {} will render as nothing.",
                    rendererClass.getName(), registryId);
            return;
        }

        event.registerBlockEntityRenderer((BlockEntityType) ownType, context -> {
            try {
                return (BlockEntityRenderer) ctor.newInstance(context);
            } catch (Exception e) {
                throw new RuntimeException("Failed to instantiate renderer " + rendererClass.getName()
                        + " for " + registryId, e);
            }
        });
        PowerNeoPackLoader.LOGGER.info("Registered {} for pack block entity type {}",
                rendererClass.getSimpleName(), registryId);
    }
}
