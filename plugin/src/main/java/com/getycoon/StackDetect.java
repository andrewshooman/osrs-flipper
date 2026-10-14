package com.getycoon;

import com.getycoon.engine.Fmt;
import com.getycoon.engine.Ledger;
import com.getycoon.engine.Tax;
import com.getycoon.tracking.OfferTracker;
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
	 * Coins waiting in finished GE slots until collected: the refund of a finished or
	 * cancelled buy, and a finished sell's proceeds after tax. Neither is in the inventory,
	 * the bank or the ledger's open offers yet.
	 */
	static long uncollected(OfferTracker.SlotState[] slots)
	{
		long gp = 0;
		if (slots == null)
		{
			return 0;
		}
		for (OfferTracker.SlotState s : slots)
		{
			if (s == null || !s.closed)
			{
				continue;
			}
			if (s.buy)
			{
				gp += Math.max(0, (long) s.total * s.price - s.spent);
			}
			else if (s.filled > 0)
			{
				gp += Math.round(s.filled * Tax.netSellPrice((double) s.spent / s.filled, s.itemId));
			}
		}
		return gp;
	}

	/**
	 * Coins collected from finished GE slots straight to the bank. The game doesn't send
	 * the bank's contents while it's closed, so without this the coins would drop out of
	 * the stack until the bank is next opened. Whatever left the slots and didn't reach the
	 * inventory within two ticks went to the bank. Client thread.
	 */
	static final class Collects
	{
		private long pending;
		private long invBefore;
		private int due = -1;
		private long since;

		/**
		 * gp left finished slots this tick.
		 *
		 * @param invAtLastTick inventory coins at the previous game tick, before this tick's changes
		 */
		void collected(long gp, long invAtLastTick, int tick, long nowMs)
		{
			if (gp <= 0 || invAtLastTick < 0)
			{
				return;
			}
			if (due < 0)
			{
				invBefore = invAtLastTick;
				due = tick + 2; // the inventory update can come a tick or two later
				since = nowMs;
			}
			pending += gp;
		}

		/** The gp that went to the bank, once the collection is two ticks old; otherwise 0. */
		long settle(long invNow, int tick)
		{
			if (due < 0 || tick < due)
			{
				return 0;
			}
			long toBank = invNow < 0 ? 0 : Math.max(0, pending - Math.max(0, invNow - invBefore));
			pending = 0;
			due = -1;
			return toBank;
		}

		/** When the collection being settled started (ms), so a bank seen since then wins. */
		long since()
		{
			return since;
		}
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
			return use ? "Open your bank once so GE Tycoon can count your coins. Until then it plans with the Stack setting ("
				+ Fmt.gp(setting) + ")." : null;
		}
		double total = cash + c.holdingCost + c.inBuyOffers;
		String parts = "coins " + Fmt.gp(cash) + ", in offers " + Fmt.gp(c.inBuyOffers) + ", in items " + Fmt.gp(c.holdingCost);
		return use ? "Stack " + Fmt.gp(total) + " (detected: " + parts + ")."
			: "Detected stack " + Fmt.gp(total) + " (" + parts + "). Turn on Use detected stack to plan with it instead of "
			+ Fmt.gp(setting) + ".";
	}
}
