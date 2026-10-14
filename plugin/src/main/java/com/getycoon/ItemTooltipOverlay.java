package com.getycoon;

import com.getycoon.tracking.OfferTracker;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.MenuEntry;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;

/**
 * Hovering an item you're flipping (inventory, bank, the GE's inventory or a GE
 * slot) shows what you paid, the breakeven and the current advice. Draws
 * nothing itself: it only adds a tooltip.
 */
public class ItemTooltipOverlay extends Overlay
{
	private final Client client;
	private final ItemManager itemManager;
	private final TooltipManager tooltipManager;
	private Supplier<Map<Integer, String>> tips = Collections::emptyMap;
	private Supplier<OfferTracker.SlotState[]> slots = () -> null;
	private java.util.function.IntFunction<String> extra = id -> null;

	@Inject
	ItemTooltipOverlay(Client client, ItemManager itemManager, TooltipManager tooltipManager)
	{
		this.client = client;
		this.itemManager = itemManager;
		this.tooltipManager = tooltipManager;
		setPosition(OverlayPosition.DYNAMIC);
	}

	/** extra: one more line for any item (the buy-limit window), shown even for items without a tip. */
	void setSources(Supplier<Map<Integer, String>> tips, Supplier<OfferTracker.SlotState[]> slots,
		java.util.function.IntFunction<String> extra)
	{
		this.tips = tips;
		this.slots = slots;
		this.extra = extra;
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (client.isMenuOpen())
		{
			return null;
		}
		Map<Integer, String> t = tips.get();
		MenuEntry[] entries = client.getMenu().getMenuEntries();
		if (entries == null || entries.length == 0)
		{
			return null;
		}
		int id = itemUnder(entries[entries.length - 1], slots.get());
		if (id <= 0)
		{
			return null;
		}
		int item = itemManager.canonicalize(id);
		String text = combine(t == null ? null : t.get(item), extra.apply(item));
		if (text != null)
		{
			tooltipManager.add(new Tooltip(text));
		}
		return null;
	}

	/** A tip plus an extra line; the extra line alone gets the title. Null when both are null. */
	static String combine(String tip, String extra)
	{
		if (extra == null)
		{
			return tip;
		}
		return (tip != null ? tip : ItemTips.TITLE) + "</br>" + extra;
	}

	/**
	 * The item a menu entry is on, if it's in the inventory, bank or GE (including a
	 * GE slot, via the tracked offers); -1 otherwise. Not canonicalized.
	 */
	static int itemUnder(MenuEntry e, OfferTracker.SlotState[] slots)
	{
		Widget w = e == null ? null : e.getWidget();
		if (w == null)
		{
			return -1;
		}
		int group = WidgetUtil.componentToInterface(w.getId());
		if (group == InterfaceID.GE_OFFERS)
		{
			int slot = w.getId() - InterfaceID.GeOffers.INDEX_0;
			if (slots != null && slot >= 0 && slot < slots.length && slots[slot] != null)
			{
				return slots[slot].itemId;
			}
			return -1;
		}
		if (group != InterfaceID.INVENTORY && group != InterfaceID.BANKMAIN && group != InterfaceID.BANKSIDE
			&& group != InterfaceID.GE_OFFERS_SIDE)
		{
			return -1;
		}
		int id = w.getItemId();
		return id > 0 ? id : e.getItemId();
	}
}
