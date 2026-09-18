package com.fennek.powerneopackloader.CoreComponentes;

import net.neoforged.fml.ModList;

/**
 * Whether GeckoLib is present, checked once via NeoForge's own {@link ModList} - never via a
 * direct GeckoLib class reference, so this class itself is always safe to load and touch.
 * <p>
 * Every direct reference to a GeckoLib type in this mod (ExampleGeckoLibBlock's supporting
 * classes, GeckoLibClientEvents, the isGeckoLibItem check in PowerNeoPackLoaderRegister) MUST sit
 * behind {@code if (GeckoLibCompat.LOADED)} / a {@code LOADED &&} short-circuit. The JVM only
 * resolves a class the first time a code path that touches it actually runs, so guarding like
 * this - not the {@code geckolib} entry in neoforge.mods.toml alone - is what keeps a install
 * without GeckoLib from ever hitting a NoClassDefFoundError instead of just skipping the example.
 */
public final class GeckoLibCompat {

    public static final boolean LOADED = ModList.get().isLoaded("geckolib");

    private GeckoLibCompat() {
    }
}
