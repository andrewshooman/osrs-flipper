package com.flipplanner;

import com.flipplanner.engine.BuyIdea;
import com.flipplanner.engine.Fmt;
import com.flipplanner.engine.Offer;
import com.flipplanner.engine.Recommender;
import com.flipplanner.engine.Tax;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Tooltip text for items the player is flipping: what they paid, the breakeven
 * after tax, and the current advice. Shown when hovering the item in the
 * inventory, bank or GE. Lines are separated by {@code </br>}; colours use
 * RuneLite's {@code <col=rrggbb>} tags.
 */
final class ItemTips
{
	static final String TITLE = "<col=ff9040>Flip Planner</col>";
	private static final String GOOD = "4cc05a";
	private static final String WARN = "fab219";
	private static final String BAD = "e05a4c";
	private static final String MUTED = "c0c0c0";

	private ItemTips()
	{
	}

	/** Tips for every holding and every planned buy in {@code r}, by item id. */
	static Map<Integer, String> build(Recommender.Result r)
	{
		if (r == null)
		{
			return Collections.emptyMap();
		}
		Map<Integer, String> tips = new HashMap<>();
		for (BuyIdea b : r.plan)
		{
			tips.put(b.id, planned((long) b.qty, b.buy, b.sellTarget, b.expected, b.hours));
		}
		for (Recommender.HoldView h : r.holds)
		{
			// A holding wins over a planned buy of the same item: it's what the player has now.
			tips.put(h.itemId, holding(h.qty, h.avgCost, Tax.breakevenSellPrice(h.avgCost, h.itemId), h.ask, h.pnl,
				h.action.label, h.action.tone, h.heldHours, h.listing));
		}
		return tips;
	}

	static String holding(long qty, double avgCost, long breakeven, Long ask, Double pnl, String advice, String tone,
		double heldHours, Offer listing)
	{
		StringBuilder s = new StringBuilder(TITLE);
		line(s, "You hold " + Fmt.full(qty) + ", paid " + Fmt.full(avgCost) + " each");
		String now = ask == null ? "" : ", now " + Fmt.full(ask)
			+ (pnl == null ? "" : " (" + color(pnl >= 0 ? GOOD : BAD, Fmt.gp(pnl, true)) + ")");
		line(s, "Breakeven " + Fmt.full(breakeven) + now);
		line(s, color(toneColor(tone), plain(advice)));
		String held = "Held " + Fmt.duration(heldHours);
		if (listing != null)
		{
			held += ", listed " + Fmt.full(listing.filled) + "/" + Fmt.full(listing.qty) + " at " + Fmt.full(listing.price);
		}
		line(s, held);
		return s.toString();
	}

	static String planned(long qty, long buy, double sellTarget, double expected, double hours)
	{
		StringBuilder s = new StringBuilder(TITLE);
		line(s, "Planned buy: " + Fmt.full(qty) + " at " + Fmt.full(buy));
		line(s, "Sell around " + Fmt.full(sellTarget) + ", expected " + color(expected >= 0 ? GOOD : BAD, Fmt.gp(expected, true))
			+ " in about " + Fmt.duration(hours));
		return s.toString();
	}

	private static void line(StringBuilder s, String text)
	{
		s.append("</br>").append(text);
	}

	private static String color(String hex, String text)
	{
		return "<col=" + hex + ">" + text + "</col>";
	}

	private static String toneColor(String tone)
	{
		if ("good".equals(tone))
		{
			return GOOD;
		}
		if ("warn".equals(tone))
		{
			return WARN;
		}
		if ("bad".equals(tone))
		{
			return BAD;
		}
		return MUTED;
	}

	/** The game font has no em dash; advice labels use one. */
	static String plain(String text)
	{
		return text == null ? "" : text.replace(" — ", ": ").replace("—", "-");
	}
}
