package com.fennek.powerneopackloader.APIBridge;

/**
 * Which creative tabs a pack loader should create for the blocks it registers.
 * <p>
 * Pack blocks need SOME tab or they are unreachable in survival-adjacent play and invisible in the
 * creative menu - they can't be added to a vanilla tab, since they don't exist until a pack is
 * read. Hence the default of {@link #BOTH}: something always shows up without the modder
 * configuring anything. A mod that places pack blocks in its own existing tabs (via
 * {@code BuildCreativeModeTabContentsEvent} and {@code PowerPackLoaderRegistry}) wants
 * {@link #NONE}.
 */
public enum CreativeTabMode {

    /** No tabs at all - the mod arranges its own. */
    NONE,

    /** One tab per pack id, each holding only that pack's blocks. Titled
     *  {@code itemGroup.<packId>}, which a pack can translate in its own lang files. */
    PER_PACK,

    /** A single tab per loader, holding every block from every pack that loader found. */
    COMBINED,

    /** Both of the above - the default. Per-pack tabs stay readable as packs are added, and the
     *  combined tab is where to look when you don't remember which pack something came from. */
    BOTH;

    public boolean perPack() {
        return this == PER_PACK || this == BOTH;
    }

    public boolean combined() {
        return this == COMBINED || this == BOTH;
    }
}
