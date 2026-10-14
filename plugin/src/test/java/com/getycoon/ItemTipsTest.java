package com.getycoon;

import com.getycoon.engine.Offer;
import com.getycoon.tracking.OfferTracker;
import java.lang.reflect.Proxy;
import net.runelite.api.MenuEntry;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ItemTipsTest
{
	@Test
	public void holdingShowsCostBreakevenAdviceAndListing()
	{
		Offer listing = new Offer("o1", 1, "sell", 1000, 1300, 0);
		listing.filled = 250;
		String t = ItemTips.holding(1000, 1234, 1260, 1299L, 40_500.0, "List at 1,299 (profit +40.5K)", "good", 3.5, listing);
		String[] lines = t.split("</br>");
		assertEquals(ItemTips.TITLE, lines[0]);
		assertEquals("You hold 1,000, paid 1,234 each", lines[1]);
		assertEquals("Breakeven 1,260, now 1,299 (<col=4cc05a>+40.5K</col>)", lines[2]);
		assertEquals("<col=4cc05a>List at 1,299 (profit +40.5K)</col>", lines[3]);
		assertEquals("Held 3.5h, listed 250/1,000 at 1,300", lines[4]);
	}

	@Test
	public void holdingWithoutPriceOrListing()
	{
		String t = ItemTips.holding(5, 100, 103, null, null, "No recent price — check again later", "muted", 0.2, null);
		assertTrue(t.contains("</br>Breakeven 103</br>"));
		assertTrue(t.contains("<col=c0c0c0>No recent price: check again later</col>"));
		assertTrue(t.endsWith("</br>Held 12m"));
		assertFalse(t.contains("—"));
	}

	@Test
	public void lossShowsRed()
	{
		String t = ItemTips.holding(10, 500, 511, 480L, -310.0, "Hold — list at 511 (breakeven 511)", "muted", 2, null);
		assertTrue(t.contains("(<col=e05a4c>-310</col>)"));
		assertTrue(t.contains("Hold: list at 511"));
	}

	@Test
	public void plannedBuy()
	{
		String t = ItemTips.planned(8596, 21, 23.4, 20_768, 3.2);
		assertEquals(ItemTips.TITLE + "</br>Planned buy: 8,596 at 21</br>Sell around 23, expected <col=4cc05a>+20.8K</col> in about 3.2h", t);
	}

	@Test
	public void noResultNoTips()
	{
		assertTrue(ItemTips.build(null).isEmpty());
	}

	private static Widget widget(int id, int itemId)
	{
		return (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(), new Class<?>[]{Widget.class}, (p, m, a) ->
		{
			switch (m.getName())
			{
				case "getId":
					return id;
				case "getItemId":
					return itemId;
				default:
					throw new UnsupportedOperationException(m.getName());
			}
		});
	}

	private static MenuEntry entry(Widget w, int itemId)
	{
		return (MenuEntry) Proxy.newProxyInstance(MenuEntry.class.getClassLoader(), new Class<?>[]{MenuEntry.class}, (p, m, a) ->
		{
			switch (m.getName())
			{
				case "getWidget":
					return w;
				case "getItemId":
					return itemId;
				default:
					throw new UnsupportedOperationException(m.getName());
			}
		});
	}

	@Test
	public void itemUnderReadsInventoryBankAndGe()
	{
		assertEquals(4151, ItemTooltipOverlay.itemUnder(entry(widget(InterfaceID.Inventory.ITEMS, 4151), -1), null));
		assertEquals(560, ItemTooltipOverlay.itemUnder(entry(widget(InterfaceID.Bankmain.ITEMS, -1), 560), null));
		assertEquals(-1, ItemTooltipOverlay.itemUnder(entry(null, 4151), null));
		// Some other interface: no tooltip.
		assertEquals(-1, ItemTooltipOverlay.itemUnder(entry(widget(InterfaceID.Chatbox.MES_TEXT, 4151), 4151), null));

		OfferTracker.SlotState[] slots = new OfferTracker.SlotState[OfferTracker.SLOTS];
		slots[2] = new OfferTracker.SlotState();
		slots[2].itemId = 11832;
		assertEquals(11832, ItemTooltipOverlay.itemUnder(entry(widget(InterfaceID.GeOffers.INDEX_0 + 2, -1), -1), slots));
		assertEquals(-1, ItemTooltipOverlay.itemUnder(entry(widget(InterfaceID.GeOffers.INDEX_0 + 3, -1), -1), slots));
	}
}
