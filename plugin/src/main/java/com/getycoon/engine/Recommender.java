package com.getycoon.engine;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * "Do this now" (js/engine.js doThisNow + scoreItem): buy orders for free slots and an
 * action for every holding, from the validated model.
 */
public final class Recommender
{
	public static final int MAX_CONCURRENT_REQUESTS = 6;

	private final Model model;
	private final int maxConcurrent;

	public Recommender(Model model)
	{
		this(model, MAX_CONCURRENT_REQUESTS);
	}

	public Recommender(Model model, int maxConcurrent)
	{
		this.model = model;
		this.maxConcurrent = Math.max(1, maxConcurrent);
	}

	/** The settings a run reads (defaults as in js/config.js). */
	public static final class Settings
	{
		public boolean members = true;
		public double bankroll = 11_000_000;
		/** Share of equity kept untouched. */
		public double cashReservePct = 0.5;
		/** GE slots the tool plans for. */
		public int slots = 7;
		/** Items never suggested. */
		public Set<Integer> excludedIds = new HashSet<>();
		/** Shortlist size by tradeable margin (top 300 holds ~96% of what the strategy buys). */
		public int candidates = 300;
		/** Leave out buy orders expected to make less than this (gp). */
		public double minExpectedGp = 0;
	}

	/** Features, latest hourly bar and model predictions for one item. */
	public static final class Scored
	{
		public final Map<String, Double> features;
		public final Bar bar;
		public final Map<String, Double> pred;

		Scored(Map<String, Double> features, Bar bar, Map<String, Double> pred)
		{
			this.features = features;
			this.bar = bar;
			this.pred = pred;
		}
	}

	/** A holding with the model's advice. */
	public static final class HoldView
	{
		public final int itemId;
		/** Null when the item is missing from /mapping. */
		public final String name;
		public final long qty;
		public final double avgCost;
		public final double cost;
		public final long oldestTs;
		public final double heldHours;
		public final HoldingAction action;
		/** The open sell offer for this item, if any. */
		public final Offer listing;
		/** Hourly instant-buy price - 1; null without a price. */
		public final Long ask;
		/** Profit after tax if sold at {@link #ask}; null without one. */
		public final Double pnl;

		HoldView(Ledger.Holding h, String name, double heldHours, HoldingAction action, Offer listing, Long ask, Double pnl)
		{
			this.itemId = h.itemId;
			this.name = name;
			this.qty = h.qty;
			this.avgCost = h.avgCost;
			this.cost = h.cost;
			this.oldestTs = h.oldestTs;
			this.heldHours = heldHours;
			this.action = action;
			this.listing = listing;
			this.ask = ask;
			this.pnl = pnl;
		}
	}

	public static final class Result
	{
		/** Start of the latest completed hour (unix seconds). */
		public final long barTs;
		public final MarketContext ctx;
		public final Ledger.Capital capital;
		/** Buy orders to place now, best first. */
		public final List<BuyIdea> plan;
		/** Every idea, ranked (plan entries included). */
		public final List<BuyIdea> ideas;
		public final double deployUsed;
		public final List<HoldView> holds;
		/** Slots left for buying after keeping one for every holding that needs listing. */
		public final int buySlots;
		/** Buy offers to cancel so holdings can be listed (least filled first). */
		public final List<Offer> makeRoom;
		/** Unix milliseconds of the run. */
		public final long at;

		Result(long barTs, MarketContext ctx, Ledger.Capital capital, BuyPlan plan, List<HoldView> holds, int buySlots,
			List<Offer> makeRoom, long at)
		{
			this.barTs = barTs;
			this.ctx = ctx;
			this.capital = capital;
			this.plan = plan.plan;
			this.ideas = plan.ideas;
			this.deployUsed = plan.deployUsed;
			this.holds = holds;
			this.buySlots = buySlots;
			this.makeRoom = makeRoom;
			this.at = at;
		}
	}

	private static final class Candidate
	{
		final int id;
		final ItemMeta item;
		final double edge;

		Candidate(int id, ItemMeta item, double edge)
		{
			this.id = id;
			this.item = item;
			this.edge = edge;
		}
	}

	/** Start of the latest completed hour, in unix seconds. */
	public static long barTs(long nowMs)
	{
		return (long) (Math.floor(nowMs / 1000.0 / 3600) * 3600 - 3600);
	}

	/** Features + model predictions for one item at the bar starting {@code barTs}. */
	public Scored scoreItem(PriceSource src, int id, ItemMeta item, long barTs, MarketContext ctx) throws IOException
	{
		List<HourPoint> pts = src.timeseries1h(id);
		Series s = Features.denseSeries(pts == null ? Collections.<HourPoint>emptyList() : pts, barTs);
		Map<String, Double> f = Features.itemFeatures(s, item, ctx);
		return new Scored(f, s.lastBar(), model.predict(f));
	}

	/**
	 * Full run at {@code nowMs}. progress (may be null) gets status text. Throws when the
	 * mapping or a market snapshot can't be fetched; one item failing only drops that item.
	 */
	public Result run(PriceSource src, LedgerData ledger, Settings settings, long nowMs, Consumer<String> progress)
		throws IOException
	{
		Consumer<String> say = progress == null ? t -> { } : progress;
		Settings s = settings == null ? new Settings() : settings;
		long barTs = barTs(nowMs);
		ExecutorService pool = Executors.newFixedThreadPool(maxConcurrent, r ->
		{
			Thread t = new Thread(r, "ge-tycoon-recommender");
			t.setDaemon(true);
			return t;
		});
		try
		{
			say.accept("Fetching market snapshots…");
			Future<Map<Integer, ItemMeta>> fItems = pool.submit(src::mapping);
			Future<Map<Integer, HourPoint>> fNow = pool.submit(() -> src.snapshot1h(barTs));
			Future<Map<Integer, HourPoint>> fPrev1 = pool.submit(() -> src.snapshot1h(barTs - 3600));
			Future<Map<Integer, HourPoint>> fPrev24 = pool.submit(() -> src.snapshot1h(barTs - 86400));
			Future<Map<Integer, HourPoint>> fDay = pool.submit(() ->
			{
				try
				{
					return src.avg24h();
				}
				catch (Exception e)
				{
					return null;
				}
			});
			Map<Integer, ItemMeta> items = orEmpty(await(fItems));
			Map<Integer, HourPoint> now = new TreeMap<>(orEmpty(await(fNow)));
			Map<Integer, HourPoint> prev1 = orEmpty(await(fPrev1));
			Map<Integer, HourPoint> prev24 = orEmpty(await(fPrev24));
			Map<Integer, HourPoint> day = await(fDay);
			MarketContext ctx = MarketContext.compute(now, prev1, prev24, day);
			Ledger.Capital cap = Ledger.capital(ledger, s.cashReservePct, s.slots, s.bankroll);

			// Shortlist: both sides traded last hour, members setting, positive post-tax margin, decent volume.
			Set<Integer> excluded = s.excludedIds == null ? Collections.<Integer>emptySet() : s.excludedIds;
			List<Candidate> shortList = new ArrayList<>();
			for (Map.Entry<Integer, HourPoint> e : now.entrySet())
			{
				HourPoint d = e.getValue();
				ItemMeta item = items.get(e.getKey());
				if (item == null || !(s.members || !item.members) || excluded.contains(item.id)
					|| !(d.avgHighPrice > 0) || !(d.avgLowPrice > 0) || !(d.highPriceVolume > 2) || !(d.lowPriceVolume > 2)
					|| !(d.avgHighPrice - 1 - tax1(d.avgHighPrice - 1) - (d.avgLowPrice + 1) > 0))
				{
					continue;
				}
				double edge = Math.min(d.highPriceVolume, d.lowPriceVolume)
					* (d.avgHighPrice - 1 - tax1(d.avgHighPrice - 1) - d.avgLowPrice - 1);
				shortList.add(new Candidate(e.getKey(), item, edge));
			}
			JsSort.sort(shortList, (a, b) -> b.edge - a.edge); // tradeable gp of margin per hour
			if (shortList.size() > Math.max(0, s.candidates))
			{
				shortList = new ArrayList<>(shortList.subList(0, Math.max(0, s.candidates)));
			}

			Map<Integer, Ledger.Holding> holdings = Ledger.positions(ledger == null ? null : ledger.trades).holdings;
			Set<Integer> idSet = new LinkedHashSet<>();
			for (Candidate c : shortList)
			{
				idSet.add(c.id);
			}
			idSet.addAll(holdings.keySet());
			List<Integer> ids = new ArrayList<>(idSet);
			Object progressLock = new Object();
			int[] done = {0};
			List<Future<Scored>> futures = new ArrayList<>();
			for (int id : ids)
			{
				ItemMeta item = items.getOrDefault(id, ItemMeta.UNKNOWN);
				futures.add(pool.submit(() ->
				{
					Scored r;
					try
					{
						r = scoreItem(src, id, item, barTs, ctx);
					}
					catch (Exception e)
					{
						r = null;
					}
					synchronized (progressLock)
					{
						say.accept("Running the model… " + (++done[0]) + "/" + ids.size());
					}
					return r;
				}));
			}
			Map<Integer, Scored> scored = new LinkedHashMap<>();
			for (int i = 0; i < ids.size(); i++)
			{
				scored.put(ids.get(i), await(futures.get(i)));
			}

			List<Offer> open = Ledger.openOffers(ledger);
			List<HoldView> holds = new ArrayList<>();
			for (Ledger.Holding h : holdings.values())
			{
				Scored sc = scored.get(h.itemId);
				double heldHours = (nowMs - h.oldestTs) / 36e5;
				HoldingAction act = sc != null
					? Decide.holdingAction(h.qty, h.avgCost, heldHours, sc.bar, sc.pred, h.itemId)
					: new HoldingAction("wait", null, "No model data", "muted");
				Offer listing = null;
				for (Offer o : open)
				{
					if ("sell".equals(o.side) && o.itemId == h.itemId)
					{
						listing = o;
						break;
					}
				}
				Long ask = sc != null && sc.bar.ah > 0 ? (long) Js.round(sc.bar.ah) - 1 : null;
				Double pnl = ask != null && ask != 0 ? (Tax.netSellPrice(ask, h.itemId) - h.avgCost) * h.qty : null;
				ItemMeta meta = items.get(h.itemId);
				holds.add(new HoldView(h, meta == null ? null : meta.name, heldHours, act, listing, ask, pnl));
			}
			// Selling what you hold comes first: keep a slot for every holding that still
			// needs listing, and if there aren't enough, free them by cancelling the
			// least-filled buy offers (same rule as the bot's sell_priority).
			int needSlot = 0;
			for (HoldView h : holds)
			{
				if (h.listing == null && !"wait".equals(h.action.action))
				{
					needSlot++;
				}
			}
			List<Offer> buys = new ArrayList<>();
			for (Offer o : open)
			{
				if ("buy".equals(o.side))
				{
					buys.add(o);
				}
			}
			JsSort.sort(buys, (a, b) ->
			{
				double d = (double) a.filled / a.qty - (double) b.filled / b.qty;
				return d != 0 && !Double.isNaN(d) ? d : (double) (a.placedAt - b.placedAt);
			});
			List<Offer> makeRoom = new ArrayList<>(buys.subList(0, Math.min(buys.size(), Math.max(0, needSlot - cap.slotsFree))));
			int buySlots = Math.max(0, cap.slotsFree - needSlot);
			Set<Integer> busy = new HashSet<>();
			for (Offer o : open)
			{
				busy.add(o.itemId);
			}
			List<BuyCandidate> buyItems = new ArrayList<>();
			for (Candidate c : shortList)
			{
				Scored sc = scored.get(c.id);
				if (busy.contains(c.id) || holdings.containsKey(c.id) || sc == null)
				{
					continue;
				}
				buyItems.add(new BuyCandidate(c.id, c.item.name, Ledger.remainingLimit(ledger, c.id, c.item.limit, nowMs),
					sc.bar, sc.features, sc.pred));
			}
			BuyPlan plan = Decide.buyPlan(buyItems, cap.deployable, buySlots, ctx.mktRet24h, s.minExpectedGp);

			// Prices are from the last hourly close; flag orders whose live price has moved since.
			Map<Integer, long[]> lt;
			try
			{
				lt = orEmpty(src.latest());
			}
			catch (Exception e)
			{
				lt = Collections.emptyMap();
			}
			for (BuyIdea r : plan.plan)
			{
				long[] live = lt.get(r.id);
				if (live == null || live.length < 2 || !(live[1] > 0))
				{
					continue;
				}
				long low = live[1];
				r.liveLow = low;
				if (low > r.buy * 1.02)
				{
					r.drift = "Price rose since the hourly close (instant-sell now " + low + "). Skip, or bid up to "
						+ Js.toString(Math.min(low + 1, r.sellTarget - 1)) + " if the margin still clears tax.";
				}
				else if (low < r.buy * 0.98)
				{
					r.drift = "Price dropped since the hourly close (instant-sell now " + low + "). You can bid a little lower.";
				}
			}
			return new Result(barTs, ctx, cap, plan, holds, buySlots, makeRoom, nowMs);
		}
		finally
		{
			pool.shutdown();
		}
	}

	private static double tax1(double p)
	{
		return Math.min(Math.floor(p * 0.02), 5e6);
	}

	private static <K, V> Map<K, V> orEmpty(Map<K, V> m)
	{
		return m == null ? Collections.<K, V>emptyMap() : m;
	}

	private static <T> T await(Future<T> f) throws IOException
	{
		try
		{
			return f.get();
		}
		catch (InterruptedException e)
		{
			throw new InterruptedIOException("Recommendation run interrupted");
		}
		catch (ExecutionException e)
		{
			Throwable c = e.getCause();
			if (c instanceof IOException)
			{
				throw (IOException) c;
			}
			if (c instanceof RuntimeException)
			{
				throw (RuntimeException) c;
			}
			if (c instanceof Error)
			{
				throw (Error) c;
			}
			throw new IOException(c);
		}
	}
}
