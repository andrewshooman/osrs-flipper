package com.getycoon.tracking;

import java.util.ArrayList;
import java.util.List;
import static net.runelite.api.GrandExchangeOfferState.BOUGHT;
import static net.runelite.api.GrandExchangeOfferState.BUYING;
import static net.runelite.api.GrandExchangeOfferState.CANCELLED_BUY;
import static net.runelite.api.GrandExchangeOfferState.EMPTY;
import static net.runelite.api.GrandExchangeOfferState.SELLING;
import static net.runelite.api.GrandExchangeOfferState.SOLD;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

public class OfferTrackerTest
{
	private final List<String> log = new ArrayList<>();
	private int ids;
	private OfferTracker t;

	private final OfferTracker.Sink sink = new OfferTracker.Sink()
	{
		@Override
		public String openOffer(int slot, int itemId, boolean buy, int qty, long price, long ts)
		{
			String id = "o" + (++ids);
			log.add("open " + id + " " + (buy ? "buy" : "sell") + " " + itemId + " " + qty + "@" + price);
			return id;
		}

		@Override
		public void fill(String offerId, int itemId, boolean buy, int qty, double price, long ts)
		{
			log.add("fill " + offerId + " " + qty + "@" + price);
		}

		@Override
		public void closeOffer(String offerId, boolean anyFilled, long ts)
		{
			log.add("close " + offerId + (anyFilled ? " done" : " cancelled"));
		}
	};

	@Before
	public void setUp()
	{
		t = new OfferTracker(null, sink);
	}

	@Test
	public void placeFillCompleteCollect()
	{
		assertTrue(t.onOffer(0, 4151, 1000, 100, 0, 0, BUYING, 1));
		assertTrue(t.onOffer(0, 4151, 1000, 100, 40, 40_000, BUYING, 2));
		assertTrue(t.onOffer(0, 4151, 1000, 100, 100, 99_700, BOUGHT, 3));
		assertTrue(t.onOffer(0, -1, 0, 0, 0, 0, EMPTY, 4));
		assertEquals(List.of("open o1 buy 4151 100@1000", "fill o1 40@1000.0", "fill o1 60@995.0", "close o1 done"), log);
	}

	@Test
	public void loginBurstRepeatsAreIgnored()
	{
		t.onOffer(3, 2, 500, 10, 10, 5000, SOLD, 1);
		log.clear();
		assertFalse(t.onOffer(3, 2, 500, 10, 10, 5000, SOLD, 2));
		assertFalse(t.onOffer(3, 2, 500, 10, 10, 5000, SOLD, 3));
		assertEquals(List.of(), log);
	}

	@Test
	public void fillsWhileLoggedOutArePickedUpAtLogin()
	{
		t.onOffer(1, 2, 500, 100, 40, 20_000, BUYING, 1);
		// Restart: the slot state comes back from the trade file.
		OfferTracker again = new OfferTracker(t.getSlots(), sink);
		log.clear();
		assertTrue(again.onOffer(1, 2, 500, 100, 100, 50_000, BOUGHT, 9));
		assertEquals(List.of("fill o1 60@500.0", "close o1 done"), log);
	}

	@Test
	public void slotReusedWhileLoggedOut()
	{
		t.onOffer(1, 2, 500, 100, 40, 20_000, BUYING, 1);
		log.clear();
		t.onOffer(1, 7, 90, 5, 0, 0, SELLING, 2);
		assertEquals(List.of("close o1 done", "open o2 sell 7 5@90"), log);
	}

	@Test
	public void cancelledPartWayIsDone_cancelledEmptyIsCancelled()
	{
		t.onOffer(0, 2, 500, 100, 30, 15_000, BUYING, 1);
		t.onOffer(0, 2, 500, 100, 30, 15_000, CANCELLED_BUY, 2);
		t.onOffer(1, 3, 10, 50, 0, 0, BUYING, 3);
		t.onOffer(1, 3, 10, 50, 0, 0, CANCELLED_BUY, 4);
		assertEquals(List.of("open o1 buy 2 100@500", "fill o1 30@500.0", "close o1 done",
			"open o2 buy 3 50@10", "close o2 cancelled"), log);
	}

	@Test
	public void identicalOfferPlacedAgainIsNew()
	{
		t.onOffer(0, 2, 500, 10, 10, 5000, BOUGHT, 1);
		log.clear();
		// Collected and re-placed on mobile: the next thing we see is a fresh offer with the same terms.
		t.onOffer(0, 2, 500, 10, 0, 0, BUYING, 2);
		assertEquals(List.of("open o2 buy 2 10@500"), log);
	}

	@Test
	public void firstSightOfAPartFilledOfferRecordsItsFills()
	{
		t.onOffer(5, 2, 500, 10, 4, 1996, BUYING, 1);
		assertEquals(List.of("open o1 buy 2 10@500", "fill o1 4@499.0"), log);
	}

	@Test
	public void emptySlotWithNothingTrackedIsNoChange()
	{
		assertFalse(t.onOffer(2, -1, 0, 0, 0, 0, EMPTY, 1));
	}
}
