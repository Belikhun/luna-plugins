package dev.belikhun.luna.legacy.shop;

import java.util.List;

/**
 * Everything the shop needs to know about an item on this platform.
 *
 * This is the shop's entire coupling to the game. Everything else about a shop
 * item - its id, its category, its prices, its trade limits - is text and numbers,
 * so with the calls below behind one interface the model, the pricing rules and
 * the item store all become shared code, and a platform supplies a page of glue.
 *
 * **What `encode` produces is per-platform and stays that way.** An item is NBT,
 * and 1.12.2's NBT is not 1.21's: `items.yml` written by a 1.12.2 backend does not
 * load on a modern one and must not be copied between them. The shop is per-backend
 * data, unlike the cluster-wide config the selector reads.
 *
 * @param <I> the platform's stack type
 */
public interface ShopItems<I> {
	/** The stack as a string `items.yml` can carry, or "" when it cannot be read. */
	String encode(I stack);

	/** A fresh stack from {@link #encode}'s output, or {@link #empty()} if unreadable. */
	I decode(String encoded);

	/**
	 * A stable identity for the stack, used to give the same item the same id twice.
	 *
	 * Two stacks that a player would call the same item must fingerprint the same,
	 * and the count must not enter it: one diamond and sixty-four diamonds are the
	 * same shop entry at different quantities.
	 */
	byte[] fingerprint(I stack);

	/** The platform's "nothing here" stack. */
	I empty();

	boolean isEmpty(I stack);

	/**
	 * Whether two stacks are the same thing to a shop, ignoring how many.
	 *
	 * Item plus data, not identity: a named diamond sword and a plain one are
	 * different entries, and sixty-four diamonds are the same entry as one.
	 */
	boolean sameItemAndData(I first, I second);

	/** The same stack at a different size. */
	I withCount(I stack, int count);

	/** How many of this fit in one stack; at least 1. */
	int maxStackSize(I stack);

	/** The name a player sees, custom name included; "" when there is none. */
	String displayName(I stack);

	/** The registry id, e.g. `minecraft:diamond`; "" when it cannot be read. */
	String itemId(I stack);

	/** The stack's lore lines as plain text, never null. */
	List<String> lore(I stack);
}
