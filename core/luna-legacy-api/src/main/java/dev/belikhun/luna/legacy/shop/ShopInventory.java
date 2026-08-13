package dev.belikhun.luna.legacy.shop;

/**
 * The four things buying and selling do to a player's inventory.
 *
 * Everything else in {@link ShopService} - the prices, the daily limits, the
 * order the checks run in, the transaction log - is arithmetic over text and
 * numbers. These four calls are the whole of its coupling to the game, so with
 * them behind an interface the rules are the same code on every platform and a
 * backend supplies a page of glue.
 *
 * **Only the storage half of an inventory counts.** Armor and the offhand are not
 * places a shop puts things, and counting them would let a player sell what they
 * are wearing; every implementation must respect that, because the rules above
 * cannot check it.
 *
 * @param <P> the platform's player type
 * @param <I> the platform's item-stack type
 */
public interface ShopInventory<P, I> {
	/** How many of `sample` the player is holding, across the storage slots. */
	int countSimilar(P player, I sample);

	/**
	 * How many more of `sample` would fit.
	 *
	 * Counts room in partially filled stacks as well as empty slots, so a player
	 * with 63 of something and one free slot can be told the truth about both.
	 */
	int maxAcceptable(P player, I sample);

	/** Take exactly `amount` of `sample` away. The caller has already counted. */
	void removeSimilar(P player, I sample, int amount);

	/**
	 * Hand over `amount` of `sample`, splitting into stacks as the game requires.
	 *
	 * Anything that will not fit is the implementation's problem to place or drop;
	 * the caller has already asked {@link #maxAcceptable}, so this only overflows
	 * if the inventory changed underneath it.
	 */
	void give(P player, I sample, int amount);
}
