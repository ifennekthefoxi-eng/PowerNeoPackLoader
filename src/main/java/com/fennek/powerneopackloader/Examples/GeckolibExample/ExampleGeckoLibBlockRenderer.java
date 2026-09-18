package com.fennek.powerneopackloader.Examples.GeckolibExample;

import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

public class ExampleGeckoLibBlockRenderer extends GeoBlockRenderer<ExampleGeckoLibBlockEntity> {

    public ExampleGeckoLibBlockRenderer(BlockEntityRendererProvider.Context context) {
        super(new ExampleGeckoLibBlockBlockModel());
    }
}
