package com.getycoon;

import com.getycoon.tracking.OfferTracker;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.function.Supplier;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Draws on top of the Grand Exchange: an outline on whatever the next action
 * needs clicked (Collect, a slot's Buy button, the item in the search results,
 * the price/quantity buttons, Confirm), and on each slot how long its offer has
 * been up, coloured green / amber / red. Drawing only: it never clicks.
 */
public class GeOverlay extends Overlay
{
	/** Snapshot of what the overlay needs, refreshed by the plugin. */
	public static final class State
	{
		public NextAction action;
		public OfferTracker.SlotState[] slots;
		public long[] placedAt;
		public double buyWaitHours = 6;
		public boolean highlight = true;
		public boolean timers = true;
	}

	// GE offer setup screen: children of InterfaceID.GeOffers.SETUP.
	private static final int SETUP_QTY_ALL = 50;
	private static final int SETUP_QTY_CUSTOM = 51;
	private static final int SETUP_PRICE_CUSTOM = 54;
	private static final int VARP_SETUP_PRICE = 5753;
	private static final int MESLAYER_MODE_GE_SEARCH = 14;

	private static final Color BLUE = new Color(0x4C, 0x9A, 0xFF);
	private static final Color RED = new Color(0xE0, 0x5A, 0x4C);
	private static final Color GREEN = new Color(0x4C, 0xC0, 0x5A);
	private static final Color AMBER = new Color(0xFA, 0xB2, 0x19);

	private final Client client;
	private final ItemManager itemManager;
	private Supplier<State> state = State::new;

	@Inject
	GeOverlay(Client client, ItemManager itemManager)
	{
		this.client = client;
		this.itemManager = itemManager;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(PRIORITY_HIGH);
	}

	public void setState(Supplier<State> state)
	{
		this.state = state;
	}

	private static boolean visible(Widget w)
	{
		return w != null && !w.isHidden();
	}

	private void outline(Graphics2D g, Widget w, Color c)
	{
		if (!visible(w))
		{
			return;
		}
		Rectangle r = w.getBounds();
		if (r == null || r.width <= 0)
		{
			return;
		}
		g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 50));
		g.fill(r);
		g.setColor(c);
		g.setStroke(new BasicStroke(2));
		g.draw(r);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		State s = state.get();
		if (s == null || client.getWidget(InterfaceID.GeOffers.INDEX_0) == null)
		{
			return null;
		}
		int openSlot = client.getVarbitValue(VarbitID.GE_SELECTEDSLOT) - 1;
		boolean setupOpen = openSlot >= 0 && visible(client.getWidget(InterfaceID.GeOffers.SETUP_CONFIRM));
		if (!setupOpen && openSlot < 0 && s.timers)
		{
			drawSlotTimers(g, s);
		}
		if (s.highlight && s.action != null)
		{
			if (setupOpen)
			{
				highlightSetup(g, s.action);
			}
			else if (openSlot < 0)
			{
				highlightHome(g, s);
			}
		}
		return null;
	}

	private Widget slotWidget(int slot)
	{
		return client.getWidget(InterfaceID.GeOffers.INDEX_0 + slot);
	}

	private void highlightHome(Graphics2D g, State s)
	{
		NextAction a = s.action;
		switch (a.type)
		{
			case COLLECT:
			{
				Widget bar = client.getWidget(InterfaceID.GeOffers.COLLECTALL);
				Widget collect = bar == null ? null : bar.getChild(2);
				if (visible(collect))
				{
					outline(g, collect, BLUE);
				}
				else
				{
					outline(g, slotWidget(a.slot), BLUE);
				}
				break;
			}
			case CANCEL:
				outline(g, slotWidget(a.slot), RED);
				break;
			case SELL:
			{
				// Offer the item from the inventory shown next to the GE.
				Widget inv = client.getWidget(InterfaceID.GeOffersSide.ITEMS);
				if (inv != null && inv.getDynamicChildren() != null)
				{
					for (Widget item : inv.getDynamicChildren())
					{
						// Items collected from the GE are often noted.
						if (item.getItemId() > 0 && itemManager.canonicalize(item.getItemId()) == a.itemId)
						{
							outline(g, item, BLUE);
							break;
						}
					}
				}
				break;
			}
			case BUY:
			{
				for (int i = 0; i < OfferTracker.SLOTS; i++)
				{
					if (s.slots != null && s.slots[i] != null)
					{
						continue;
					}
					Widget slot = slotWidget(i);
					Widget buy = slot == null ? null : slot.getChild(0);
					if (visible(buy))
					{
						outline(g, buy, BLUE);
						break;
					}
				}
				break;
			}
			default:
				break;
		}
	}

	private void highlightSetup(Graphics2D g, NextAction a)
	{
		if (a.type != NextAction.Type.BUY && a.type != NextAction.Type.SELL)
		{
			return;
		}
		boolean selling = client.getVarbitValue(VarbitID.GE_NEWOFFER_TYPE) == 1;
		if (selling != (a.type == NextAction.Type.SELL))
		{
			return;
		}
		int item = client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH);
		boolean searching = client.getVarcIntValue(VarClientID.MESLAYERMODE) == MESLAYER_MODE_GE_SEARCH;
		if (item != a.itemId)
		{
			if (searching)
			{
				highlightSearchResult(g, a);
			}
			return;
		}
		Widget setup = client.getWidget(InterfaceID.GeOffers.SETUP);
		if (setup == null)
		{
			return;
		}
		long price = setupPrice();
		long qty = client.getVarbitValue(VarbitID.GE_NEWOFFER_QUANTITY);
		// The GE fills in the guide price, so 0 means the price couldn't be read: then
		// keep pointing at the price button, but don't hold back Confirm because of it.
		boolean priceKnown = price > 0;
		boolean priceOk = price == a.price;
		boolean qtyOk = qty == a.qty;
		if (!priceOk)
		{
			outline(g, setup.getChild(SETUP_PRICE_CUSTOM), BLUE);
		}
		if (!qtyOk)
		{
			outline(g, setup.getChild(SETUP_QTY_CUSTOM), BLUE);
		}
		if ((priceOk || !priceKnown) && qtyOk)
		{
			outline(g, client.getWidget(InterfaceID.GeOffers.SETUP_CONFIRM), BLUE);
		}
	}

	/** The price on the offer setup screen. Its varp sits among the GE's long varps, so read it either way. */
	private long setupPrice()
	{
		long p = client.getVarpLongValue(VARP_SETUP_PRICE);
		return p > 0 ? p : client.getVarpValue(VARP_SETUP_PRICE);
	}

	private void highlightSearchResult(Graphics2D g, NextAction a)
	{
		Widget results = client.getWidget(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS);
		if (results == null || results.getDynamicChildren() == null || a.name == null)
		{
			return;
		}
		String want = a.name.toLowerCase();
		for (Widget w : results.getDynamicChildren())
		{
			String n = w.getName();
			if (n != null && n.replaceAll("<[^>]*>", "").toLowerCase().equals(want))
			{
				outline(g, w, BLUE);
				return;
			}
		}
	}

	private void drawSlotTimers(Graphics2D g, State s)
	{
		if (s.slots == null || s.placedAt == null)
		{
			return;
		}
		long now = System.currentTimeMillis();
		NextAction a = s.action;
		Font font = FontManager.getRunescapeSmallFont();
		g.setFont(font);
		for (int i = 0; i < OfferTracker.SLOTS; i++)
		{
			OfferTracker.SlotState st = s.slots[i];
			Widget w = slotWidget(i);
			if (st == null || !visible(w) || s.placedAt[i] <= 0)
			{
				continue;
			}
			long mins = Math.max(0, (now - s.placedAt[i]) / 60_000L);
			String t = mins < 60 ? mins + "m" : (mins / 60) + "h " + (mins % 60) + "m";
			boolean cancel = a != null && a.type == NextAction.Type.CANCEL && a.slot == i;
			boolean done = st.state != null && !"BUYING".equals(st.state) && !"SELLING".equals(st.state);
			Color c = cancel ? RED : done ? BLUE : st.buy && mins > s.buyWaitHours * 60 / 2 ? AMBER : GREEN;
			Rectangle r = w.getBounds();
			g.setColor(c);
			g.setStroke(new BasicStroke(1));
			g.drawRect(r.x, r.y, r.width - 1, r.height - 1);
			int tw = g.getFontMetrics().stringWidth(t);
			g.setColor(Color.BLACK);
			g.drawString(t, r.x + r.width - tw - 4, r.y + r.height - 4);
			g.setColor(c);
			g.drawString(t, r.x + r.width - tw - 5, r.y + r.height - 5);
		}
	}
}
