package com.getycoon.tracking;

import net.runelite.api.GrandExchangeOfferState;

/**
 * Turns the client's GE offer updates into offers and fills.
 *
 * The client reports each slot's whole offer every time it changes, and
 * again for every slot at login. The tracker remembers what it last saw per
 * slot (persisted with the trade file), so only new fills are recorded and a
 * login never double-counts. Fills that happened while logged out (mobile, or
 * another client) are picked up at the next login.
 */
public class OfferTracker
{
	public static final int SLOTS = 8;

	/** Where offers and fills go (the ledger). */
	public interface Sink
	{
		/** A new offer appeared in a slot; returns its id. */
		String openOffer(int slot, int itemId, boolean buy, int qty, long price, long ts);

		/** {@code qty} more items traded on an offer at an average of {@code price} each (before tax). */
		void fill(String offerId, int itemId, boolean buy, int qty, double price, long ts);

		/** The offer finished: completed, cancelled, or gone from its slot. */
		void closeOffer(String offerId, boolean anyFilled, long ts);
	}

	/** What the tracker last saw in a slot. */
	public static class SlotState
	{
		public int itemId;
		public boolean buy;
		public long price;
		public int total;
		public int filled;
		public long spent;
		public String state;
		public String offerId;
		public boolean closed;
	}

	private SlotState[] slots;
	private final Sink sink;

	public OfferTracker(SlotState[] slots, Sink sink)
	{
		this.slots = slots != null && slots.length == SLOTS ? slots : new SlotState[SLOTS];
		this.sink = sink;
	}

	public SlotState[] getSlots()
	{
		return slots;
	}

	public SlotState slot(int i)
	{
		return slots[i];
	}

	static boolean isBuy(GrandExchangeOfferState s)
	{
		return s == GrandExchangeOfferState.BUYING || s == GrandExchangeOfferState.BOUGHT || s == GrandExchangeOfferState.CANCELLED_BUY;
	}

	static boolean isActive(GrandExchangeOfferState s)
	{
		return s == GrandExchangeOfferState.BUYING || s == GrandExchangeOfferState.SELLING;
	}

	/**
	 * One offer update from the client. Returns true when the ledger changed.
	 * Ignore EMPTY updates that arrive while not logged in (the client clears
	 * the slots when logging out or hopping) before calling this.
	 */
	public boolean onOffer(int slot, int itemId, long price, int total, int filled, long spent, GrandExchangeOfferState state, long now)
	{
		if (slot < 0 || slot >= SLOTS || state == null)
		{
			return false;
		}
		SlotState s = slots[slot];

		if (state == GrandExchangeOfferState.EMPTY)
		{
			if (s == null)
			{
				return false;
			}
			if (!s.closed)
			{
				sink.closeOffer(s.offerId, s.filled > 0, now);
			}
			slots[slot] = null;
			return true;
		}

		boolean buy = isBuy(state);
		boolean changed = false;
		boolean same = s != null && s.itemId == itemId && s.buy == buy && s.price == price && s.total == total
			&& filled >= s.filled && !(s.closed && isActive(state));
		if (!same)
		{
			if (s != null && !s.closed)
			{
				// The slot moved on while we weren't watching.
				sink.closeOffer(s.offerId, s.filled > 0, now);
			}
			s = new SlotState();
			s.itemId = itemId;
			s.buy = buy;
			s.price = price;
			s.total = total;
			s.offerId = sink.openOffer(slot, itemId, buy, total, price, now);
			slots[slot] = s;
			changed = true;
		}

		int dq = filled - s.filled;
		if (dq > 0)
		{
			long ds = spent - s.spent;
			double avg = ds > 0 ? (double) ds / dq : price;
			sink.fill(s.offerId, itemId, buy, dq, avg, now);
			changed = true;
		}
		s.filled = filled;
		s.spent = spent;
		s.state = state.name();

		if (!isActive(state) && !s.closed)
		{
			sink.closeOffer(s.offerId, filled > 0, now);
			s.closed = true;
			changed = true;
		}
		return changed;
	}
}
