package com.flipplanner;

import com.flipplanner.engine.Fmt;
import com.flipplanner.engine.HourPoint;
import com.flipplanner.engine.ItemMeta;
import com.flipplanner.engine.Tax;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dip alerts: a liquid item whose instant-sell price in the newest 5-minute bucket is
 * well under its 24-hour average, on heavy volume. In the 5-minute replay
 * (research/dips.py, HANDOFF.md) buying the dip and selling at 1% under the 24-hour
 * average instant-buy price, dumping after 6 hours, made +56k/day out of sample with
 * 2 slots and 2M per trade, against -28k/day for the same trades at random moments.
 * It only pays when acted on within about 20 minutes. Advice only: nothing is placed.
 */
final class DipAlerts
{
	/** Instant-sells must turn over at least this much a day (the research's liquidity cut). */
	static final double MIN_GP_DAY = 50_000_000;
	/** At least twice the usual 5-minute instant-sell volume. */
	static final double VOL_X = 2.0;
	/** Sell 1% under the 24h average instant-buy price. */
	static final double MARGIN = 0.01;
	/** Most gp per alert trade. */
	static final long CAP = 2_000_000;
	static final long COOLDOWN_MS = 6 * 3_600_000L;
	static final double HOLD_HOURS = 6;
	private static final int BUCKETS_PER_DAY = 288;

	static final class Alert
	{
		final int itemId;
		final String name;
		/** Price to bid: the dip's average instant-sell price. */
		final long price;
		/** Price to sell at. */
		final long target;
		final long qty;
		/** How far under the 24h average, e.g. 0.11. */
		final double drop;
		/** Profit after tax if it all sells at the target. */
		final double expected;
		/** Start of the 5-minute bucket (unix seconds). */
		final long bucket;

		Alert(int itemId, String name, long price, long target, long qty, double drop, double expected, long bucket)
		{
			this.itemId = itemId;
			this.name = name;
			this.price = price;
			this.target = target;
			this.qty = qty;
			this.drop = drop;
			this.expected = expected;
			this.bucket = bucket;
		}

		/** One line for a notification or the chat. */
		String text()
		{
			return name + " dipped " + Math.round(drop * 100) + "% to " + Fmt.full(price) + ". Buy up to " + Fmt.full(qty)
				+ " at " + Fmt.full(price) + ", sell at " + Fmt.full(target) + " (" + Fmt.gp(expected, true)
				+ "); sell anyway after " + Math.round(HOLD_HOURS) + "h.";
		}
	}

	private DipAlerts()
	{
	}

	/**
	 * Alerts for the newest 5-minute bucket, best first.
	 *
	 * @param fiveMin   newest 5-minute bucket per item (/5m)
	 * @param day       24-hour averages per item (/24h)
	 * @param meta      item names, members flags and buy limits (/mapping)
	 * @param skip      items not to alert (blocked, or alerted within the cooldown)
	 * @param members   members items allowed
	 * @param drop      minimum fall under the 24h average instant-sell price, e.g. 0.08
	 * @param freeGp    gp available for a trade
	 */
	static List<Alert> find(Map<Integer, HourPoint> fiveMin, Map<Integer, HourPoint> day, Map<Integer, ItemMeta> meta,
		Set<Integer> skip, boolean members, double drop, double freeGp)
	{
		List<Alert> out = new ArrayList<>();
		if (fiveMin == null || day == null || meta == null)
		{
			return out;
		}
		for (Map.Entry<Integer, HourPoint> e : fiveMin.entrySet())
		{
			int id = e.getKey();
			HourPoint now = e.getValue();
			HourPoint ref = day.get(id);
			ItemMeta m = meta.get(id);
			if (ref == null || m == null || skip.contains(id) || (m.members && !members))
			{
				continue;
			}
			double low = now.avgLowPrice;
			double refLow = ref.avgLowPrice;
			double refHigh = ref.avgHighPrice;
			if (!(low > 0) || !(refLow > 0) || !(refHigh > 0))
			{
				continue;
			}
			if (refLow * ref.lowPriceVolume < MIN_GP_DAY)
			{
				continue;
			}
			double usualVol = (double) ref.lowPriceVolume / BUCKETS_PER_DAY;
			if (low > refLow * (1 - drop) || now.lowPriceVolume < VOL_X * usualVol)
			{
				continue;
			}
			long price = Math.round(low);
			long target = (long) Math.floor(refHigh * (1 - MARGIN));
			double net = Tax.netSellPrice((double) target, id);
			if (net <= price * 1.002)
			{
				continue;
			}
			long limit = m.limit > 0 ? m.limit : Long.MAX_VALUE;
			long qty = Math.min(limit, (long) Math.floor(Math.min(CAP, Math.max(0, freeGp)) / price));
			if (qty < 1)
			{
				continue;
			}
			out.add(new Alert(id, m.name, price, target, qty, 1 - low / refLow, qty * (net - price), now.timestamp));
		}
		out.sort(Comparator.comparingDouble((Alert a) -> -a.expected));
		return out;
	}
}
