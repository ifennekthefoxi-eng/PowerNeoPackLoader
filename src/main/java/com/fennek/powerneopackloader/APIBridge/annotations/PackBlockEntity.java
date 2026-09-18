package com.fennek.powerneopackloader.APIBridge.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code BlockEntity} subclass as the entity to pair with {@link #value()}'s block class.
 * The standalone form of {@code @PackBlock(entity = ...)} - use whichever reads better; declaring
 * it on the block keeps the family in one place, declaring it here is what you need when the block
 * class isn't yours to annotate (a vanilla block, or one from another mod).
 * <p>
 * Every pack block gets its OWN dedicated {@code BlockEntityType} - one shared type across several
 * pack blocks cannot work, because {@code BlockEntity}'s constructor validates the block it's
 * placed on against the type's valid-blocks list and crashes the game on the first placement
 * otherwise. That's why an entity class reused from vanilla (e.g. {@code ChestBlockEntity}, whose
 * public 2-arg constructor hardcodes {@code BlockEntityType.CHEST}) is constructed through its
 * {@code (BlockEntityType<?>, BlockPos, BlockState)} constructor when it has one - see
 * {@code PowerNeoPackLoaderRegister#instantiateBlockEntity}.
 *
 * <h2>Constructors</h2>
 * Tried in order: {@code (BlockEntityType<?>, BlockPos, BlockState)}, then
 * {@code (BlockPos, BlockState)}. Non-public is fine.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface PackBlockEntity {

    /** The {@code @PackBlock}-annotated block class this entity belongs to. */
    Class<?> value();
}
