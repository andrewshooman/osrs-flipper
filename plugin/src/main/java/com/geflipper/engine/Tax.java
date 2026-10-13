package com.geflipper.engine;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Grand Exchange tax (js/config.js + js/util.js). */
public final class Tax
{
	/** 2% of the sell price, per item, rounded down (since 29 May 2025). */
	public static final double TAX_RATE = 0.02;
	public static final long TAX_CAP_PER_ITEM = 5_000_000;
	/** GE buy limits reset 4 hours after the first purchase of an item. */
	public static final long BUY_LIMIT_WINDOW_MS = 4 * 60 * 60 * 1000;

	/**
	 * Exempt from GE tax (OSRS Wiki "Grand Exchange" → Exempt from tax): bonds, energy
	 * potions, low-level ammo/runes/food, teleport tablets, games necklace(8), ring of
	 * dueling(8) and basic tools. Keep in sync with js/config.js.
	 */
	public static final Set<Integer> TAX_EXEMPT = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
		233, 315, 329, 347, 351, 355, 361, 365, 379, 558, 806, 807, 808, 882, 884, 886, 952, 1733, 1735, 1755,
		1785, 1891, 2140, 2142, 2309, 2327, 2347, 2552, 3008, 3010, 3012, 3014, 3853, 5325, 5329, 5331, 5341,
		5343, 8007, 8008, 8009, 8010, 8011, 8013, 8794, 13190, 28790, 28824)));

	private Tax()
	{
	}

	/** Tax paid on ONE item sold at {@code price}. */
	public static long perItem(long price, int itemId)
	{
		return (long) perItem((double) price, itemId);
	}

	/** As {@link #perItem(long, int)} for a fractional price (NaN pays no tax). */
	public static double perItem(double price, int itemId)
	{
		if (!(price > 0) || TAX_EXEMPT.contains(itemId))
		{
			return 0;
		}
		return Math.min(Math.floor(price * TAX_RATE), TAX_CAP_PER_ITEM);
	}

	/** What you actually receive per item after tax. */
	public static long netSellPrice(long price, int itemId)
	{
		return price - perItem(price, itemId);
	}

	public static double netSellPrice(double price, int itemId)
	{
		return price - perItem(price, itemId);
	}

	/** Lowest sell price that recovers {@code cost} per item after tax. */
	public static long breakevenSellPrice(double cost, int itemId)
	{
		if (!(cost > 0))
		{
			return 0;
		}
		// Lower bound on the answer: either the uncapped (2%) or capped regime.
		long p = (long) Math.max(1, Math.floor(Math.min((cost - 1) / (1 - TAX_RATE), cost + TAX_CAP_PER_ITEM)) - 1);
		while (netSellPrice(p, itemId) < cost)
		{
			p++;
		}
		return p;
	}
}
