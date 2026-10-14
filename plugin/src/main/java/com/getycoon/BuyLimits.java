package com.getycoon;

import com.getycoon.engine.Fmt;
import com.getycoon.engine.ItemMeta;
import com.getycoon.engine.Ledger;
import com.getycoon.engine.Trade;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GE buy limits from the trades GE Tycoon recorded: how much of each item's limit is
 * used and when it resets. The game's rule (Ledger.buyLimitUsage): a 4-hour window
 * opens on the first purchase, and everything bought before it ends counts.
 * Purchases made outside RuneLite aren't known, so this can under-count.
 */
final class BuyLimits
{
	static final long WINDOW_MS = 4 * 3_600_000L;

	/** itemId -> {used, resetsAt (ms), limit (0 = unknown)} for items with a window running. */
	private volatile Map<Integer, long[]> windows = Collections.emptyMap();

	/** Recomputes every running window. Any thread; the trades list must not change meanwhile. */
	void update(List<Trade> trades, Map<Integer, ItemMeta> meta, long now)
	{
		Set<Integer> recent = new HashSet<>();
		for (Trade t : trades)
		{
			if ("buy".equals(t.side) && t.ts > now - WINDOW_MS)
			{
				recent.add(t.itemId);
			}
		}
		Map<Integer, long[]> out = new HashMap<>();
		for (int id : recent)
		{
			Ledger.LimitUsage u = Ledger.buyLimitUsage(trades, id, now);
			if (u.resetsAt != null)
			{
				ItemMeta m = meta == null ? null : meta.get(id);
				out.put(id, new long[]{u.used, u.resetsAt, m == null ? 0 : m.limit});
			}
		}
		windows = out;
	}

	/** One line for a tooltip or the GE quantity box; null when no window is running for the item. */
	String line(int itemId, long now)
	{
		long[] w = windows.get(itemId);
		return w == null ? null : format(w[0], w[2], w[1], now);
	}

	static String format(long used, long limit, long resetsAt, long now)
	{
		if (now >= resetsAt)
		{
			return null;
		}
		String in = "resets in " + until(resetsAt - now);
		if (limit <= 0)
		{
			return "Bought " + Fmt.full(used) + " this window, " + in;
		}
		if (used >= limit)
		{
			return "Buy limit reached (" + Fmt.full(limit) + "), " + in;
		}
		return "Buy limit: " + Fmt.full(limit - used) + " of " + Fmt.full(limit) + " left, " + in;
	}

	/** 1h 20m · 35m · under 1m. */
	static String until(long ms)
	{
		long mins = (ms + 59_999) / 60_000;
		if (mins < 1)
		{
			return "under 1m";
		}
		return mins >= 60 ? (mins / 60) + "h " + (mins % 60) + "m" : mins + "m";
	}
}
