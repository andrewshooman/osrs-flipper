package com.getycoon;

import com.getycoon.tracking.OfferTracker;
import java.util.Collections;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class NextActionTest
{
	private static final long H = 3_600_000L;

	private static OfferTracker.SlotState slot(int item, boolean buy, String state, int total, int filled, String id)
	{
		OfferTracker.SlotState s = new OfferTracker.SlotState();
		s.itemId = item;
		s.buy = buy;
		s.state = state;
		s.total = total;
		s.filled = filled;
		s.price = 100;
		s.offerId = id;
		return s;
	}

	private static NextAction.Input input()
	{
		NextAction.Input in = new NextAction.Input();
		in.now = 100 * H;
		in.nextPlanAt = in.now + 20 * 60_000L;
		in.buys.add(new NextAction.Buy(1, "Garlic", 21, 8596, 20_768));
		in.buys.add(new NextAction.Buy(2, "Broad bolts", 53, 7000, 17_685));
		return in;
	}

	@Test
	public void collectComesFirst()
	{
		NextAction.Input in = input();
		in.slots[3] = slot(5, true, "BOUGHT", 10, 10, "o1");
		in.holds.add(new NextAction.Hold(9, "Steel cannonball", 5000, "list", 259, false));
		NextAction a = NextAction.pick(in);
		assertEquals(NextAction.Type.COLLECT, a.type);
		assertEquals(3, a.slot);
	}

	@Test
	public void cancelledOffersSayCancelled()
	{
		NextAction.Input in = input();
		in.slots[2] = slot(5, true, "CANCELLED_BUY", 100, 0, "o1");
		NextAction a = NextAction.pick(in);
		assertEquals(NextAction.Type.COLLECT, a.type);
		assertTrue(a.detail, a.detail.startsWith("Cancelled buy of "));
		assertTrue(a.detail, !a.detail.contains("("));
		in.slots[2] = slot(5, false, "CANCELLED_SELL", 100, 40, "o1");
		assertTrue(NextAction.pick(in).detail.contains("(40 sold)"));
		in.slots[2] = slot(5, true, "BOUGHT", 100, 100, "o1");
		assertTrue(NextAction.pick(in).detail.startsWith("Bought 100 "));
	}

	@Test
	public void staleOrBlockingBuysAreCancelled()
	{
		NextAction.Input in = input();
		in.slots[0] = slot(5, true, "BUYING", 100, 20, "o1");
		in.placedAt[0] = in.now - 7 * H;
		NextAction a = NextAction.pick(in);
		assertEquals(NextAction.Type.CANCEL, a.type);
		assertEquals(0, a.slot);
		assertTrue(a.detail.contains("up 7h"));

		in.placedAt[0] = in.now - H;
		in.makeRoom.add("o1");
		assertEquals(NextAction.Type.CANCEL, NextAction.pick(in).type);
		assertTrue(NextAction.pick(in).detail.contains("free a slot"));
	}

	@Test
	public void sellBeforeBuy_holdAndListedAreSkipped()
	{
		NextAction.Input in = input();
		in.holds.add(new NextAction.Hold(8, "Rune bar", 100, "hold", 12_500, false));
		in.holds.add(new NextAction.Hold(7, "Coal", 900, "list", 150, true));
		in.holds.add(new NextAction.Hold(9, "Steel cannonball", 5000, "list", 259, false));
		NextAction a = NextAction.pick(in);
		assertEquals(NextAction.Type.SELL, a.type);
		assertEquals(9, a.itemId);
		assertEquals(259, a.price);
		assertEquals(5000, a.qty);
		assertEquals("Sell 5,000 Steel cannonball", a.title);
	}

	@Test
	public void buyInPlanOrder_skippingSkippedAndBusy()
	{
		NextAction.Input in = input();
		NextAction a = NextAction.pick(in);
		assertEquals(NextAction.Type.BUY, a.type);
		assertEquals(1, a.itemId);
		assertEquals("Buy 8,596 Garlic", a.title);

		in.skipped = Collections.singleton(1);
		assertEquals(2, NextAction.pick(in).itemId);

		in.skipped = null;
		in.slots[2] = slot(1, true, "BUYING", 8596, 0, "o9");
		in.placedAt[2] = in.now - H;
		assertEquals(2, NextAction.pick(in).itemId);
	}

	@Test
	public void waitWhenSellOnlyOrSlotsFullOrNothingClears()
	{
		NextAction.Input in = input();
		in.sellOnly = true;
		NextAction a = NextAction.pick(in);
		assertEquals(NextAction.Type.WAIT, a.type);
		assertTrue(a.detail.startsWith("Sell-only mode is on."));
		assertTrue(a.detail.contains("20 min"));

		in = input();
		in.maxSlots = 1;
		in.slots[0] = slot(5, false, "SELLING", 10, 0, "o1");
		assertTrue(NextAction.pick(in).detail.startsWith("Your slots are in use."));

		in = input();
		in.buys.clear();
		assertTrue(NextAction.pick(in).detail.startsWith("No order clears"));
	}
}
