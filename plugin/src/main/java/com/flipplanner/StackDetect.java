package com.flipplanner;

import com.flipplanner.engine.Fmt;
import com.flipplanner.engine.Ledger;
import net.runelite.api.Item;
import net.runelite.api.gameval.ItemID;

/**
 * The flipping stack from the game: coins and platinum tokens in the inventory and
 * bank, plus gp waiting in buy offers and items held at cost (both from the ledger).
 */
final class StackDetect
{
	private StackDetect()
	{
	}

	/** Coins plus platinum tokens (1,000 gp each) in a container's items. */
	static long cash(Item[] items)
	{
		long gp = 0;
		if (items == null)
		{
			return 0;
		}
		for (Item i : items)
		{
			if (i == null)
			{
				continue;
			}
			if (i.getId() == ItemID.COINS)
			{
				gp += i.getQuantity();
			}
			else if (i.getId() == ItemID.PLATINUM)
			{
				gp += 1000L * i.getQuantity();
			}
		}
		return gp;
	}

	/**
	 * The engine's bankroll is the stack before realised profit (equity = bankroll +
	 * realised). For equity to equal the detected stack, pass this.
	 */
	static double bankroll(long cash, Ledger.Capital c)
	{
		return cash + c.holdingCost + c.inBuyOffers - c.realised;
	}

	/** The detected stack's parts, for the panel; null when there's nothing to say. */
	static String note(boolean use, Long cash, double setting, Ledger.Capital c)
	{
		if (cash == null)
		{
			return use ? "Open your bank once so Flip Planner can count your coins. Until then it plans with the Stack setting ("
				+ Fmt.gp(setting) + ")." : null;
		}
		double total = cash + c.holdingCost + c.inBuyOffers;
		String parts = "coins " + Fmt.gp(cash) + ", in offers " + Fmt.gp(c.inBuyOffers) + ", in items " + Fmt.gp(c.holdingCost);
		return use ? "Stack " + Fmt.gp(total) + " (detected: " + parts + ")."
			: "Detected stack " + Fmt.gp(total) + " (" + parts + "). Turn on Use detected stack to plan with it instead of "
			+ Fmt.gp(setting) + ".";
	}
}
