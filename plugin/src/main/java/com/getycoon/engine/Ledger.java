package com.getycoon.engine;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure ledger maths (the core of js/ledger.js): FIFO positions, GE buy limits, capital and
 * profit stats. Nothing here touches storage or the network.
 */
public final class Ledger
{
	/** Buys before sells at the same time, otherwise oldest first. */
	private static final JsSort.Cmp<Trade> BY_TIME = (a, b) ->
	{
		double d = (double) (a.ts - b.ts);
		if (d != 0)
		{
			return d;
		}
		return (a.isBuy() ? -1 : 1) - (b.isBuy() ? -1 : 1);
	};

	private Ledger()
	{
	}

	/** An open lot: what is left of one buy. */
	public static final class Lot
	{
		public long qty;
		public final double price;
		public final long ts;

		Lot(long qty, double price, long ts)
		{
			this.qty = qty;
			this.price = price;
			this.ts = ts;
		}
	}

	/** Everything still held of one item. */
	public static final class Holding
	{
		public final int itemId;
		public final long qty;
		public final double cost;
		public final double avgCost;
		/** Time of the oldest unsold buy. */
		public final long oldestTs;
		public final List<Lot> lots;

		Holding(int itemId, long qty, double cost, double avgCost, long oldestTs, List<Lot> lots)
		{
			this.itemId = itemId;
			this.qty = qty;
			this.cost = cost;
			this.avgCost = avgCost;
			this.oldestTs = oldestTs;
			this.lots = lots;
		}
	}

	/** The matched part of one sell trade. */
	public static final class ClosedTrip
	{
		public final String tradeId;
		public final int itemId;
		public final long qty;
		public final double buyPrice;
		public final double sellPrice;
		public final double cost;
		public final double proceeds;
		public final double tax;
		public final double profit;
		public final double roi;
		public final long buyTs;
		public final long sellTs;
		public final double holdHours;

		ClosedTrip(String tradeId, int itemId, long qty, double buyPrice, double sellPrice, double cost, double proceeds,
			double tax, long buyTs, long sellTs)
		{
			this.tradeId = tradeId;
			this.itemId = itemId;
			this.qty = qty;
			this.buyPrice = buyPrice;
			this.sellPrice = sellPrice;
			this.cost = cost;
			this.proceeds = proceeds;
			this.tax = tax;
			this.profit = proceeds - cost;
			this.roi = (proceeds - cost) / cost;
			this.buyTs = buyTs;
			this.sellTs = sellTs;
			this.holdHours = (sellTs - buyTs) / 36e5;
		}
	}

	/** The part of a sell with no recorded buy behind it. */
	public static final class Unmatched
	{
		public final String tradeId;
		public final int itemId;
		public final long qty;
		public final double price;
		public final long ts;

		Unmatched(String tradeId, int itemId, long qty, double price, long ts)
		{
			this.tradeId = tradeId;
			this.itemId = itemId;
			this.qty = qty;
			this.price = price;
			this.ts = ts;
		}
	}

	public static final class Positions
	{
		/** By item, in order of each item's first buy. */
		public final Map<Integer, Holding> holdings;
		public final List<ClosedTrip> closed;
		public final List<Unmatched> unmatched;

		Positions(Map<Integer, Holding> holdings, List<ClosedTrip> closed, List<Unmatched> unmatched)
		{
			this.holdings = holdings;
			this.closed = closed;
			this.unmatched = unmatched;
		}
	}

	public static final class LimitUsage
	{
		public final long used;
		/** Null when no window is running. */
		public final Long windowStart;
		public final Long resetsAt;

		LimitUsage(long used, Long windowStart, Long resetsAt)
		{
			this.used = used;
			this.windowStart = windowStart;
			this.resetsAt = resetsAt;
		}
	}

	/** Money picture. Equity is at cost (realised profit only). */
	public static final class Capital
	{
		public final double equity;
		public final double realised;
		public final double holdingCost;
		public final double inBuyOffers;
		public final double cash;
		public final double reserve;
		public final double deployable;
		public final int slotsUsed;
		public final int slotsFree;

		Capital(double equity, double realised, double holdingCost, double inBuyOffers, double cash, double reserve,
			double deployable, int slotsUsed, int slotsFree)
		{
			this.equity = equity;
			this.realised = realised;
			this.holdingCost = holdingCost;
			this.inBuyOffers = inBuyOffers;
			this.cash = cash;
			this.reserve = reserve;
			this.deployable = deployable;
			this.slotsUsed = slotsUsed;
			this.slotsFree = slotsFree;
		}
	}

	public static final class ItemStats
	{
		public final int itemId;
		public double profit;
		public long qty;
		public double cost;
		public double tax;
		public int flips;
		public int wins;

		ItemStats(int itemId)
		{
			this.itemId = itemId;
		}
	}

	public static final class DayProfit
	{
		/** Local calendar day, yyyy-mm-dd. */
		public final String day;
		public final double profit;

		DayProfit(String day, double profit)
		{
			this.day = day;
			this.profit = profit;
		}
	}

	public static final class Stats
	{
		public final List<ClosedTrip> closed;
		public final Map<Integer, Holding> holdings;
		public final List<Unmatched> unmatched;
		public final double profit;
		public final double tax;
		/** NaN with no closed trades. */
		public final double roi;
		public final int flips;
		public final double winRate;
		public final double avgHoldHours;
		/** Profit of trades closed today (local day). */
		public final double today;
		/** Best item first. */
		public final List<ItemStats> byItem;
		/** Oldest day first. */
		public final List<DayProfit> byDay;

		Stats(Positions p, double profit, double tax, double roi, int flips, double winRate, double avgHoldHours, double today,
			List<ItemStats> byItem, List<DayProfit> byDay)
		{
			this.closed = p.closed;
			this.holdings = p.holdings;
			this.unmatched = p.unmatched;
			this.profit = profit;
			this.tax = tax;
			this.roi = roi;
			this.flips = flips;
			this.winRate = winRate;
			this.avgHoldHours = avgHoldHours;
			this.today = today;
			this.byItem = byItem;
			this.byDay = byDay;
		}
	}

	/**
	 * Replays trades oldest-first, matching each sell against the oldest unsold buys of
	 * the same item (FIFO). Returns open lots and closed round trips.
	 */
	public static Positions positions(List<Trade> trades)
	{
		List<Trade> sorted = new ArrayList<>(trades == null ? Collections.<Trade>emptyList() : trades);
		JsSort.sort(sorted, BY_TIME);
		Map<Integer, ArrayDeque<Lot>> lots = new LinkedHashMap<>();
		List<ClosedTrip> closed = new ArrayList<>();
		List<Unmatched> unmatched = new ArrayList<>();
		for (Trade t : sorted)
		{
			if (t.isBuy())
			{
				lots.computeIfAbsent(t.itemId, k -> new ArrayDeque<>()).add(new Lot(t.qty, t.price, t.ts));
				continue;
			}
			ArrayDeque<Lot> q = lots.get(t.itemId);
			if (q == null)
			{
				q = new ArrayDeque<>();
			}
			long left = t.qty;
			double cost = 0;
			long matched = 0;
			Long firstBuyTs = null;
			while (left > 0 && !q.isEmpty())
			{
				Lot lot = q.peekFirst();
				long take = Math.min(left, lot.qty);
				cost += (double) take * lot.price;
				matched += take;
				if (firstBuyTs == null)
				{
					firstBuyTs = lot.ts;
				}
				lot.qty -= take;
				left -= take;
				if (lot.qty == 0)
				{
					q.pollFirst();
				}
			}
			if (matched > 0)
			{
				double tax = t.isNoTax() ? 0 : Tax.perItem((double) t.price, t.itemId) * matched; // alching pays no GE tax
				double proceeds = (double) t.price * matched - tax;
				closed.add(new ClosedTrip(t.id, t.itemId, matched, cost / matched, t.price, cost, proceeds, tax, firstBuyTs, t.ts));
			}
			if (left > 0)
			{
				unmatched.add(new Unmatched(t.id, t.itemId, left, t.price, t.ts));
			}
		}
		Map<Integer, Holding> holdings = new LinkedHashMap<>();
		for (Map.Entry<Integer, ArrayDeque<Lot>> e : lots.entrySet())
		{
			ArrayDeque<Lot> q = e.getValue();
			double qty = 0;
			for (Lot l : q)
			{
				qty += l.qty;
			}
			if (qty <= 0)
			{
				continue;
			}
			double cost = 0;
			for (Lot l : q)
			{
				cost += (double) l.qty * l.price;
			}
			holdings.put(e.getKey(), new Holding(e.getKey(), (long) qty, cost, cost / qty, q.peekFirst().ts, new ArrayList<>(q)));
		}
		return new Positions(holdings, closed, unmatched);
	}

	/**
	 * GE buy limits: a 4-hour window opens on the first purchase of an item; everything
	 * bought before it expires counts toward the limit.
	 */
	public static LimitUsage buyLimitUsage(List<Trade> trades, int itemId, long now)
	{
		List<Trade> buys = new ArrayList<>();
		for (Trade t : trades)
		{
			if (t.itemId == itemId && t.isBuy())
			{
				buys.add(t);
			}
		}
		JsSort.sort(buys, BY_TIME);
		Long start = null;
		long used = 0;
		for (Trade b : buys)
		{
			if (start == null || b.ts >= start + Tax.BUY_LIMIT_WINDOW_MS)
			{
				start = b.ts;
				used = 0;
			}
			used += b.qty;
		}
		if (start == null || now >= start + Tax.BUY_LIMIT_WINDOW_MS)
		{
			return new LimitUsage(0, null, null);
		}
		return new LimitUsage(used, start, start + Tax.BUY_LIMIT_WINDOW_MS);
	}

	/**
	 * Quantity still buyable this window, counting what open buy offers will take.
	 * +Infinity when the limit is unknown (0).
	 */
	public static double remainingLimit(LedgerData ledger, int itemId, int limit, long now)
	{
		if (limit == 0)
		{
			return Double.POSITIVE_INFINITY; // unknown limit: let the other caps decide
		}
		double used = buyLimitUsage(trades(ledger), itemId, now).used;
		double pending = 0;
		for (Offer o : openOffers(ledger))
		{
			if (o.itemId == itemId && o.isBuy())
			{
				pending += o.qty - o.filled;
			}
		}
		return Math.max(0, limit - used - pending);
	}

	public static List<Offer> openOffers(LedgerData ledger)
	{
		List<Offer> out = new ArrayList<>();
		if (ledger != null && ledger.offers != null)
		{
			for (Offer o : ledger.offers)
			{
				if (o.isOpen())
				{
					out.add(o);
				}
			}
		}
		return out;
	}

	/**
	 * Money picture: {@code bankroll} plus realised profit, less what holdings and open buy
	 * offers tie up, keeping {@code cashReservePct} of equity untouched.
	 */
	public static Capital capital(LedgerData ledger, double cashReservePct, int slots, double bankroll)
	{
		Positions p = positions(trades(ledger));
		double realised = 0;
		for (ClosedTrip c : p.closed)
		{
			realised += c.profit;
		}
		double holdingCost = 0;
		for (Holding h : p.holdings.values())
		{
			holdingCost += h.cost;
		}
		List<Offer> open = openOffers(ledger);
		double inBuyOffers = 0;
		for (Offer o : open)
		{
			if (o.isBuy())
			{
				inBuyOffers += (double) (o.qty - o.filled) * o.price;
			}
		}
		double equity = bankroll + realised;
		double cash = equity - holdingCost - inBuyOffers;
		double reserve = equity * cashReservePct;
		double deployable = Math.max(0, Math.min(cash, equity - reserve - holdingCost - inBuyOffers));
		int slotsUsed = open.size();
		return new Capital(equity, realised, holdingCost, inBuyOffers, cash, reserve, deployable, slotsUsed,
			Math.max(0, slots - slotsUsed));
	}

	/** Local calendar day of {@code ts}: yyyy-mm-dd. */
	public static String dayKey(long ts, ZoneId zone)
	{
		LocalDate d = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate();
		return d.getYear() + "-" + pad2(d.getMonthValue()) + "-" + pad2(d.getDayOfMonth());
	}

	private static String pad2(int x)
	{
		return x < 10 ? "0" + x : Integer.toString(x);
	}

	/** Profit report; days are calendar days in {@code zone}, "today" is the day of {@code nowMs}. */
	public static Stats stats(LedgerData ledger, ZoneId zone, long nowMs)
	{
		Positions p = positions(trades(ledger));
		Map<Integer, ItemStats> byItem = new LinkedHashMap<>();
		Map<String, Double> byDay = new LinkedHashMap<>();
		for (ClosedTrip c : p.closed)
		{
			ItemStats it = byItem.computeIfAbsent(c.itemId, ItemStats::new);
			it.profit += c.profit;
			it.qty += c.qty;
			it.cost += c.cost;
			it.tax += c.tax;
			it.flips++;
			if (c.profit > 0)
			{
				it.wins++;
			}
			String k = dayKey(c.sellTs, zone);
			byDay.put(k, or0(byDay.get(k)) + c.profit);
		}
		double profit = 0;
		double cost = 0;
		double tax = 0;
		double hold = 0;
		int wins = 0;
		for (ClosedTrip c : p.closed)
		{
			profit += c.profit;
		}
		for (ClosedTrip c : p.closed)
		{
			cost += c.cost;
		}
		for (ClosedTrip c : p.closed)
		{
			tax += c.tax;
			hold += c.holdHours;
			if (c.profit > 0)
			{
				wins++;
			}
		}
		int n = p.closed.size();
		List<ItemStats> items = new ArrayList<>(byItem.values());
		JsSort.sort(items, (a, b) -> b.profit - a.profit);
		List<DayProfit> days = new ArrayList<>();
		for (Map.Entry<String, Double> e : byDay.entrySet())
		{
			days.add(new DayProfit(e.getKey(), e.getValue()));
		}
		JsSort.sort(days, (a, b) -> a.day.compareTo(b.day) < 0 ? -1 : 1);
		return new Stats(p, profit, tax, cost != 0 ? profit / cost : Double.NaN, n, n > 0 ? (double) wins / n : Double.NaN,
			n > 0 ? hold / n : Double.NaN, or0(byDay.get(dayKey(nowMs, zone))), items, days);
	}

	/** JS {@code x || 0}. */
	private static double or0(Double x)
	{
		return x == null || x == 0 || Double.isNaN(x) ? 0 : x;
	}

	private static List<Trade> trades(LedgerData ledger)
	{
		return ledger == null || ledger.trades == null ? Collections.<Trade>emptyList() : ledger.trades;
	}
}
