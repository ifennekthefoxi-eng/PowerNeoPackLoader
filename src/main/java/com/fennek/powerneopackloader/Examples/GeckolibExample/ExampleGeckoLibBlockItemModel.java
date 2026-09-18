package com.fennek.powerneopackloader.Examples.GeckolibExample;

import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * Item-side twin of {@link ExampleGeckoLibBlockBlockModel} - same geo/texture/animation files
 * (there's only one visual for this block, whether it's placed or held), just typed against
 * {@link ExampleGeckoLibBlockItem} instead of {@link ExampleGeckoLibBlockEntity} since GeckoLib's
 * {@code GeoModel<T>} is generic over the specific animatable it's rendering. See
 * {@code ExampleChestBlockBlockModel}'s own class doc for why {@code PACK_NAMESPACE} is the
 * pack's id ("functional_default") and not this mod's id - same reasoning applies here unchanged.
 */
public class ExampleGeckoLibBlockItemModel extends GeoModel<ExampleGeckoLibBlockItem> {

    private static final String PACK_NAMESPACE = "example_pack";

    @Override
    public ResourceLocation getModelResource(ExampleGeckoLibBlockItem animatable) {
        return ResourceLocation.fromNamespaceAndPath(PACK_NAMESPACE, "geo/geckolib_example_block.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(ExampleGeckoLibBlockItem animatable) {
        return ResourceLocation.fromNamespaceAndPath(PACK_NAMESPACE, "textures/block/geckolib_example_block.png");
    }

    @Override
    public ResourceLocation getAnimationResource(ExampleGeckoLibBlockItem animatable) {
        return ResourceLocation.fromNamespaceAndPath(PACK_NAMESPACE, "animations/geckolib_example_block.animation.json");
    }
}
