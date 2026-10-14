package com.getycoon;

import com.getycoon.engine.ItemMeta;
import com.getycoon.engine.Trade;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class BuyLimitsTest
{
	private static final long M = 60_000L;
	private static final long H = 60 * M;
	private static final long NOW = 1_791_900_000_000L;

	private static Trade buy(int item, long qty, long ts)
	{
		return new Trade("t" + ts + item, item, "buy", qty, 100, ts);
	}

	@Test
	public void formats()
	{
		assertEquals("Buy limit: 3,000 of 10,000 left, resets in 1h 20m", BuyLimits.format(7_000, 10_000, NOW + 80 * M, NOW));
		assertEquals("Buy limit reached (10,000), resets in 35m", BuyLimits.format(10_000, 10_000, NOW + 35 * M, NOW));
		assertEquals("Bought 250 this window, resets in 2h 0m", BuyLimits.format(250, 0, NOW + 2 * H, NOW));
		assertNull(BuyLimits.format(1, 10, NOW, NOW));
		assertEquals("under 1m", BuyLimits.until(0));
		assertEquals("1m", BuyLimits.until(30_000));
		assertEquals("4h 0m", BuyLimits.until(4 * H));
	}

	@Test
	public void windowOpensOnFirstBuyAndExpires()
	{
		List<Trade> trades = new ArrayList<>();
		trades.add(buy(1, 500, NOW - 5 * H));   // opens a window that ends at NOW - 1h
		trades.add(buy(1, 2_000, NOW - 3 * H)); // counts toward that old window
		trades.add(buy(1, 1_000, NOW - 1 * H)); // the old window has ended: opens the current one, until NOW + 3h
		trades.add(buy(2, 10, NOW - 30 * M));   // limit unknown (not in the item list)
		trades.add(new Trade("s", 3, "sell", 5, 100, NOW - M)); // sells don't count
		Map<Integer, ItemMeta> meta = Collections.singletonMap(1, new ItemMeta(1, "Thing", true, 5_000, 0, 0));
		BuyLimits b = new BuyLimits();
		b.update(trades, meta, NOW);
		assertEquals("Buy limit: 4,000 of 5,000 left, resets in 3h 0m", b.line(1, NOW));
		assertEquals("Bought 10 this window, resets in 3h 30m", b.line(2, NOW));
		assertNull(b.line(3, NOW));
		// Once the window ends the line goes away without another update.
		assertNull(b.line(1, NOW + 3 * H));
	}

	@Test
	public void tooltipCombinesTipAndLimit()
	{
		assertNull(ItemTooltipOverlay.combine(null, null));
		assertEquals("tip", ItemTooltipOverlay.combine("tip", null));
		assertEquals("tip</br>limit", ItemTooltipOverlay.combine("tip", "limit"));
		assertEquals(ItemTips.TITLE + "</br>limit", ItemTooltipOverlay.combine(null, "limit"));
	}
}
