package com.flipplanner;

import com.flipplanner.engine.Ledger;
import com.flipplanner.engine.LedgerData;
import com.flipplanner.engine.Offer;
import com.flipplanner.engine.Trade;
import com.flipplanner.tracking.OfferTracker;
import net.runelite.api.Item;
import net.runelite.api.gameval.ItemID;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class StackDetectTest
{
	@Test
	public void countsCoinsAndPlatinum()
	{
		Item[] items = {new Item(ItemID.COINS, 1_250_000), null, new Item(4151, 1), new Item(ItemID.PLATINUM, 3_000),
			new Item(ItemID.COINS, 5)};
		assertEquals(4_250_005L, StackDetect.cash(items));
		assertEquals(0L, StackDetect.cash(null));
	}

	/** One closed flip (+ profit), one item still held, and one open buy offer. */
	private static LedgerData ledger()
	{
		LedgerData l = new LedgerData();
		l.trades.add(new Trade("t1", 2, "buy", 100, 1_000, 1_000));
		l.trades.add(new Trade("t2", 2, "sell", 100, 1_200, 2_000));
		l.trades.add(new Trade("t3", 3, "buy", 50, 2_000, 3_000));
		Offer o = new Offer("o1", 4, "buy", 1_000, 300, 4_000);
		o.filled = 400;
		l.offers.add(o);
		l.trades.add(new Trade("t4", 4, "buy", 400, 300, 4_500));
		return l;
	}

	@Test
	public void bankrollMakesEquityEqualTheDetectedStack()
	{
		LedgerData l = ledger();
		Ledger.Capital parts = Ledger.capital(l, 0, 8, 0);
		assertEquals(50 * 2_000 + 400 * 300, parts.holdingCost, 1e-6);
		assertEquals(600 * 300, parts.inBuyOffers, 1e-6);  // unfilled part of the open buy
		assertTrue(parts.realised > 0);

		long cash = 5_000_000;
		double bankroll = StackDetect.bankroll(cash, parts);
		Ledger.Capital c = Ledger.capital(l, 0.5, 8, bankroll);
		assertEquals(cash + parts.holdingCost + parts.inBuyOffers, c.equity, 1e-6);
		assertEquals(cash, c.cash, 1e-6);
	}

	@Test
	public void notes()
	{
		Ledger.Capital parts = Ledger.capital(ledger(), 0, 8, 0);
		assertNull(StackDetect.note(false, null, 11_000_000, parts));
		assertTrue(StackDetect.note(true, null, 11_000_000, parts).startsWith("Open your bank once"));
		assertTrue(StackDetect.note(true, null, 11_000_000, parts).contains("(11.0M)"));
		assertEquals("Stack 5.40M (detected: coins 5.00M, in offers 180K, in items 220K).",
			StackDetect.note(true, 5_000_000L, 11_000_000, parts));
		assertTrue(StackDetect.note(false, 5_000_000L, 11_000_000, parts).startsWith("Detected stack 5.40M"));
	}

	private static OfferTracker.SlotState slot(boolean buy, int total, int filled, long price, long spent, boolean closed)
	{
		OfferTracker.SlotState s = new OfferTracker.SlotState();
		s.itemId = 4151;
		s.buy = buy;
		s.total = total;
		s.filled = filled;
		s.price = price;
		s.spent = spent;
		s.closed = closed;
		return s;
	}

	@Test
	public void coinsWaitingInFinishedSlots()
	{
		OfferTracker.SlotState[] slots = new OfferTracker.SlotState[OfferTracker.SLOTS];
		slots[0] = slot(true, 100, 40, 1_000, 39_000, true);    // cancelled buy: 61,000 back
		slots[1] = slot(false, 10, 10, 2_000_000, 20_000_000, true); // finished sell: 2% tax
		slots[2] = slot(true, 100, 50, 1_000, 50_000, false);   // still buying: nothing to collect
		slots[3] = slot(false, 5, 0, 900, 0, true);             // cancelled sell, nothing sold: items only
		assertEquals(61_000 + 19_600_000, StackDetect.uncollected(slots));
		assertEquals(0, StackDetect.uncollected(null));
	}
}
