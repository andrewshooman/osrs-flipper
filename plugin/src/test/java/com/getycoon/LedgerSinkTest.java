package com.getycoon;

import com.getycoon.engine.Ledger;
import com.getycoon.engine.LedgerData;
import com.getycoon.tracking.OfferTracker;
import net.runelite.api.GrandExchangeOfferState;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class LedgerSinkTest
{
	/** Cheap items fill at several prices: the profit must come from what was really paid, not a rounded price. */
	@Test
	public void cheapFillsKeepTheirExactPrice()
	{
		LedgerData ledger = new LedgerData();
		OfferTracker t = new OfferTracker(null, new GeTycoonPlugin.LedgerSink(ledger));
		// 1,000 air runes bought for 4,620 gp in total (an average of 4.62), then sold for 4,380.
		t.onOffer(0, 556, 6, 1_000, 0, 0, GrandExchangeOfferState.BUYING, 1_000);
		t.onOffer(0, 556, 6, 1_000, 1_000, 4_620, GrandExchangeOfferState.BOUGHT, 2_000);
		t.onOffer(1, 556, 4, 1_000, 0, 0, GrandExchangeOfferState.SELLING, 3_000);
		t.onOffer(1, 556, 4, 1_000, 1_000, 4_380, GrandExchangeOfferState.SOLD, 4_000);

		assertEquals(2, ledger.trades.size());
		assertEquals(4.62, ledger.trades.get(0).price, 1e-9);
		assertEquals(4.38, ledger.trades.get(1).price, 1e-9);
		Ledger.ClosedTrip c = Ledger.positions(ledger.trades).closed.get(0);
		assertEquals(-240, c.profit, 1e-6); // no tax under 50 gp
	}
}
