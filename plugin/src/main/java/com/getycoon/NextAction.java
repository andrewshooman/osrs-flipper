package com.getycoon;

import com.getycoon.tracking.OfferTracker;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The one thing to do next at the GE, in order: collect finished offers,
 * cancel buys that should go, list what you hold, place a new buy, or wait.
 * Pure: built from plain inputs so it can be tested without the client.
 */
public final class NextAction
{
	public enum Type
	{
		COLLECT, CANCEL, SELL, BUY, WAIT
	}

	public final Type type;
	public final int itemId;
	public final String name;
	/** GE slot (0-7) the action is about, or -1. */
	public final int slot;
	public final long price;
	public final long qty;
	public final String title;
	public final String detail;

	NextAction(Type type, int itemId, String name, int slot, long price, long qty, String title, String detail)
	{
		this.type = type;
		this.itemId = itemId;
		this.name = name;
		this.slot = slot;
		this.price = price;
		this.qty = qty;
		this.title = title;
		this.detail = detail;
	}

	/** A buy order from the plan. */
	public static final class Buy
	{
		final int itemId;
		final String name;
		final long price;
		final long qty;
		final double expected;

		public Buy(int itemId, String name, long price, long qty, double expected)
		{
			this.itemId = itemId;
			this.name = name;
			this.price = price;
			this.qty = qty;
			this.expected = expected;
		}
	}

	/** A holding and what the model says to do with it. */
	public static final class Hold
	{
		final int itemId;
		final String name;
		final long qty;
		/** list | sell | cut | hold | wait */
		final String action;
		final long price;
		final boolean listed;

		public Hold(int itemId, String name, long qty, String action, long price, boolean listed)
		{
			this.itemId = itemId;
			this.name = name;
			this.qty = qty;
			this.action = action;
			this.price = price;
			this.listed = listed;
		}
	}

	/** Everything the decision needs. */
	public static final class Input
	{
		public OfferTracker.SlotState[] slots = new OfferTracker.SlotState[OfferTracker.SLOTS];
		/** Placed time (ms) of the offer in each slot, 0 when unknown. */
		public long[] placedAt = new long[OfferTracker.SLOTS];
		public final List<Buy> buys = new ArrayList<>();
		public final List<Hold> holds = new ArrayList<>();
		/** Offer ids the plan says to cancel to free a slot for selling. */
		public final List<String> makeRoom = new ArrayList<>();
		public Set<Integer> skipped;
		public boolean sellOnly;
		/** GE slots the plugin may fill (the rest stay free for the player). */
		public int maxSlots = 7;
		public double buyWaitHours = 6;
		public long now;
		/** When the next hourly plan is due (ms), for the wait message. */
		public long nextPlanAt;
		public String nameOf(int id)
		{
			for (Buy b : buys)
			{
				if (b.itemId == id)
				{
					return b.name;
				}
			}
			for (Hold h : holds)
			{
				if (h.itemId == id)
				{
					return h.name;
				}
			}
			return "item " + id;
		}
	}

	private static String n(long v)
	{
		return String.format(Locale.US, "%,d", v);
	}

	private static boolean finished(OfferTracker.SlotState s)
	{
		return s != null && s.state != null && !"BUYING".equals(s.state) && !"SELLING".equals(s.state);
	}

	public static NextAction pick(Input in)
	{
		OfferTracker.SlotState[] slots = in.slots;
		int used = 0;
		for (OfferTracker.SlotState s : slots)
		{
			if (s != null)
			{
				used++;
			}
		}

		// 1. Finished offers sit in their slots until collected.
		for (int i = 0; i < slots.length; i++)
		{
			if (finished(slots[i]))
			{
				OfferTracker.SlotState s = slots[i];
				String name = in.nameOf(s.itemId);
				boolean cancelled = s.state != null && s.state.startsWith("CANCELLED");
				String what = !cancelled ? (s.buy ? "Bought " : "Sold ") + n(s.filled) + " " + name
					: "Cancelled " + (s.buy ? "buy" : "sell") + " of " + name
					+ (s.filled > 0 ? " (" + n(s.filled) + (s.buy ? " bought)" : " sold)") : "");
				return new NextAction(Type.COLLECT, s.itemId, name, i, 0, s.filled, "Collect from the GE", what + ". Click Collect.");
			}
		}

		// 2. Buys to cancel: stale (the edge has moved) or blocking a slot needed to sell.
		long staleMs = (long) (in.buyWaitHours * 3_600_000L);
		for (int i = 0; i < slots.length; i++)
		{
			OfferTracker.SlotState s = slots[i];
			if (s == null || !s.buy || s.closed)
			{
				continue;
			}
			boolean room = in.makeRoom.contains(s.offerId);
			boolean stale = in.placedAt[i] > 0 && in.now - in.placedAt[i] > staleMs;
			if (room || stale)
			{
				String why = room ? "to free a slot for selling" : String.format(Locale.US, "it's been up %dh and the price has moved", (in.now - in.placedAt[i]) / 3_600_000L);
				return new NextAction(Type.CANCEL, s.itemId, in.nameOf(s.itemId), i, s.price, s.total - s.filled,
					"Cancel your " + in.nameOf(s.itemId) + " buy",
					"Slot " + (i + 1) + ": " + why + ". Anything already bought stays yours.");
			}
		}

		// 3. Sell what you hold (sell first: it frees gp and slots).
		for (Hold h : in.holds)
		{
			boolean act = "list".equals(h.action) || "sell".equals(h.action) || "cut".equals(h.action);
			if (!act || h.listed || h.price <= 0 || h.qty <= 0)
			{
				continue;
			}
			if (used >= OfferTracker.SLOTS)
			{
				break;
			}
			String verb = "cut".equals(h.action) ? "Cut your loss: sell " : "Sell ";
			return new NextAction(Type.SELL, h.itemId, h.name, -1, h.price, h.qty,
				verb + n(h.qty) + " " + h.name,
				"at " + n(h.price) + " gp each");
		}

		// 4. A new buy, in plan order, if a slot the plugin may use is free.
		if (!in.sellOnly && used < in.maxSlots)
		{
			for (Buy b : in.buys)
			{
				if (in.skipped != null && in.skipped.contains(b.itemId))
				{
					continue;
				}
				boolean busy = false;
				for (OfferTracker.SlotState s : slots)
				{
					if (s != null && s.itemId == b.itemId)
					{
						busy = true;
						break;
					}
				}
				if (busy)
				{
					continue;
				}
				return new NextAction(Type.BUY, b.itemId, b.name, -1, b.price, b.qty,
					"Buy " + n(b.qty) + " " + b.name,
					"at " + n(b.price) + " gp each · expected +" + n(Math.round(b.expected)));
			}
		}

		// 5. Nothing worth doing right now.
		String when = in.nextPlanAt > in.now
			? String.format(Locale.US, " New plan in about %d min.", Math.max(1, (in.nextPlanAt - in.now) / 60_000L))
			: "";
		String why = in.sellOnly ? "Sell-only mode is on." : used >= in.maxSlots ? "Your slots are in use." : "No order clears your minimum profit right now.";
		return new NextAction(Type.WAIT, -1, null, -1, 0, 0, "Nothing to do right now", why + when);
	}
}
