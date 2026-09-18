package com.fennek.powerneopackloader.APIBridge.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code BlockItem} subclass as the item to register for {@link #value()}'s block class,
 * in place of the plain vanilla {@code BlockItem} every pack block otherwise gets. The standalone
 * form of {@code @PackBlock(item = ...)}.
 * <p>
 * The usual reason to need one at all is rendering: a plain {@code BlockItem} can only ever show a
 * flat 2D icon, so anything that should render as an animated 3D model in the inventory/hand (a
 * GeckoLib {@code GeoItem}, for instance) has to be its own item class.
 *
 * <h2>Constructors</h2>
 * Tried in order: {@code (Block, Item.Properties, PackBlockContext)}, then
 * {@code (Block, Item.Properties)} - the latter being {@code BlockItem}'s own signature, so a
 * subclass that just forwards to {@code super} needs nothing special. Non-public is fine.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface PackBlockItem {

    /** The {@code @PackBlock}-annotated block class this item belongs to. */
    Class<?> value();
}
