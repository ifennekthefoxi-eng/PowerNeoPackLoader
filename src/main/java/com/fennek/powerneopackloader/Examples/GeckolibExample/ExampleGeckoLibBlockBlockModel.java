package com.fennek.powerneopackloader.Examples.GeckolibExample;

import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class ExampleGeckoLibBlockBlockModel extends GeoModel<ExampleGeckoLibBlockEntity> {

    // NOT MOD_ID ("powerneopackloader"). PowerPackLoaderPacksLoader exposes every pack's assets
    // under the PACK's own id as the resource namespace (see NamespaceRewritingPackResources -
    // content authored directly under assets/<packId>/... is served as-is under that packId, it is
    // never remapped to the mod's namespace), and PowerPackLoaderModelGenerator's own auto-generated
    // blockstates confirm this: i4_engine's generated blockstate points at
    // "functional_default:block/i4_engine", the pack id, not "powerneopackloader:...". The geo/
    // texture/animation files below live at assets/functional_default/... in the same pack, under
    // that same "functional_default" namespace - using MOD_ID here made GeckoLib look in a
    // namespace none of these files are actually exposed under, hence "Unable to find model".
    //
    // This is hardcoded because this class is tied to one specific pack. If more packs ship their
    // own GeckoLib blocks later, this needs to resolve the owning pack's id per-instance instead
    // (the same problem PowerPackLoaderRegistrationContext's ThreadLocal already solves for
    // BlockEntityType - the block class doesn't know which pack registered it otherwise).
    private static final String PACK_NAMESPACE = "example_pack";

    @Override
    public ResourceLocation getModelResource(ExampleGeckoLibBlockEntity animatable) {
        // GeckoLib will automatically find this in your external folder thanks to your virtual pack!
        return ResourceLocation.fromNamespaceAndPath(PACK_NAMESPACE, "geo/geckolib_example_block.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(ExampleGeckoLibBlockEntity animatable) {
        return ResourceLocation.fromNamespaceAndPath(PACK_NAMESPACE, "textures/block/geckolib_example_block.png");
    }

    @Override
    public ResourceLocation getAnimationResource(ExampleGeckoLibBlockEntity animatable) {
        return ResourceLocation.fromNamespaceAndPath(PACK_NAMESPACE, "animations/geckolib_example_block.animation.json");
    }
}