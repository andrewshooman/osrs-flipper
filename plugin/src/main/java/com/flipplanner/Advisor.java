package com.flipplanner;

import com.flipplanner.engine.BuyIdea;
import com.flipplanner.engine.Decide;
import com.flipplanner.engine.Ledger;
import com.flipplanner.engine.LedgerData;
import com.flipplanner.engine.Model;
import com.flipplanner.engine.Offer;
import com.flipplanner.engine.Recommender;
import com.flipplanner.engine.WikiPriceSource;
import com.flipplanner.ui.PanelModel;
import com.google.gson.Gson;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;

/**
 * Runs the model on the worker thread and turns its result into what the panel
 * and the GE shortcuts show.
 */
@Slf4j
class Advisor implements OfferShortcuts.Advice
{
	private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

	private final WikiPriceSource prices;
	private final FlipPlannerConfig config;
	private final Consumer<PanelModel> show;

	private volatile Map<Integer, long[]> buys = Collections.emptyMap();
	private volatile Map<Integer, long[]> sells = Collections.emptyMap();
	private volatile List<Integer> searchItems = Collections.emptyList();
	private volatile Map<Integer, String> names = Collections.emptyMap();
	private volatile String error;
	private volatile String tradeFile = "";
	private long lastBar = -1;
	private Recommender recommender;
	private volatile Recommender.Result lastResult;
	private Runnable onResult = () -> { };
	private volatile PanelModel last = new PanelModel();

	Advisor(OkHttpClient http, Gson gson, FlipPlannerConfig config, Consumer<PanelModel> show)
	{
		this.prices = new WikiPriceSource(http, gson);
		this.config = config;
		this.show = show;
	}

	synchronized void setError(String e)
	{
		error = e;
		last = last.copy();
		last.error = e;
		push(last);
	}

	synchronized void setTradeFile(String f)
	{
		tradeFile = f;
		last = last.copy();
		last.tradeFile = f;
		push(last);
	}

	/** Called on the worker thread after each successful run. */
	void setOnResult(Runnable r)
	{
		onResult = r;
	}

	Recommender.Result lastResult()
	{
		return lastResult;
	}

	Map<Integer, String> names()
	{
		return names;
	}

	WikiPriceSource prices()
	{
		return prices;
	}

	/** Item ids from the "Never suggest" setting: ids as they are, names looked up in the item list. */
	private java.util.Set<Integer> blocked()
	{
		java.util.Set<Integer> ids = new java.util.HashSet<>();
		String raw = config.blockedItems();
		if (raw == null || raw.trim().isEmpty())
		{
			return ids;
		}
		Map<Integer, com.flipplanner.engine.ItemMeta> items = null;
		for (String part : raw.split(","))
		{
			String t = part.trim();
			if (t.isEmpty())
			{
				continue;
			}
			if (t.matches("\\d+"))
			{
				ids.add(Integer.parseInt(t));
				continue;
			}
			try
			{
				if (items == null)
				{
					items = prices.mapping();
				}
				for (com.flipplanner.engine.ItemMeta it : items.values())
				{
					if (it.name != null && it.name.equalsIgnoreCase(t))
					{
						ids.add(it.id);
					}
				}
			}
			catch (java.io.IOException e)
			{
				log.debug("Flip Planner: item list unavailable for the block list", e);
			}
		}
		return ids;
	}

	private void push(PanelModel m)
	{
		SwingUtilities.invokeLater(() -> show.accept(m));
	}

	private static long currentBar(long now)
	{
		return now / 1000 / 3600 * 3600 - 3600;
	}

	/** Worker thread. force = always; otherwise only once a newer hourly bar is out. */
	void run(LedgerData ledger, boolean force)
	{
		long now = System.currentTimeMillis();
		if (!force && currentBar(now) <= lastBar)
		{
			return;
		}
		PanelModel busy = copyStatus(last);
		busy.loading = true;
		busy.status = "Running the model…";
		push(busy);

		Recommender.Settings s = new Recommender.Settings();
		s.members = config.members();
		s.bankroll = config.bankroll();
		s.cashReservePct = config.reservePercent() / 100.0;
		s.slots = config.slots();
		s.minExpectedGp = config.minOrderProfit();
		s.excludedIds = blocked();

		PanelModel m = new PanelModel();
		m.tradeFile = tradeFile;
		try
		{
			if (recommender == null)
			{
				recommender = new Recommender(Model.loadDefault());
			}
			Recommender.Result r = recommender.run(prices, ledger, s, now, p ->
			{
				PanelModel q = copyStatus(last);
				q.loading = true;
				q.status = p;
				push(q);
			});
			fill(m, r, ledger, now);
			lastBar = r.barTs;
			lastResult = r;
			m.error = null;
			error = null;
		}
		catch (Exception e)
		{
			log.warn("Flip Planner: model run failed", e);
			m = copyStatus(last);
			m.error = "Couldn't get prices: " + e.getMessage() + ". Try Refresh in a minute.";
		}
		m.loading = false;
		if (m.error == null && error != null)
		{
			m.error = error;
		}
		last = m;
		push(m);
		if (m.error == null)
		{
			onResult.run();
		}
	}

	/** The panel keeps showing the last results while a run is in progress. */
	private static PanelModel copyStatus(PanelModel from)
	{
		return from.copy();
	}

	private void fill(PanelModel m, Recommender.Result r, LedgerData ledger, long now)
	{
		Map<Integer, long[]> b = new HashMap<>();
		Map<Integer, long[]> sl = new HashMap<>();
		Map<Integer, String> nm = new HashMap<>();
		List<Integer> search = new ArrayList<>();

		m.freeGp = r.capital.deployable;
		for (BuyIdea i : config.sellOnly() ? java.util.Collections.<BuyIdea>emptyList() : r.plan)
		{
			PanelModel.Buy row = new PanelModel.Buy();
			row.itemId = i.id;
			row.name = i.name;
			row.price = i.buy;
			row.qty = (long) i.qty;
			row.sellTarget = Math.round(i.sellTarget);
			row.expected = i.expected;
			row.hours = i.hours;
			row.warning = i.drift;
			m.buys.add(row);
			m.expectedProfit += i.expected;
			b.put(i.id, new long[]{i.buy, (long) i.qty});
			nm.put(i.id, i.name);
			search.add(i.id);
		}
		if (m.buys.isEmpty())
		{
			m.noBuys = config.sellOnly() ? "Sell-only mode is on: no new buys."
				: r.buySlots > 0
				? String.format(Locale.US, "Nothing worth buying right now: no order is expected to make %,d gp or more. Empty slots are fine; check back later.", config.minOrderProfit())
				: "No free slots for new buys: list what you hold first.";
		}

		for (Recommender.HoldView h : r.holds)
		{
			PanelModel.Hold row = new PanelModel.Hold();
			row.itemId = h.itemId;
			row.name = h.name != null ? h.name : "Item " + h.itemId;
			row.qty = h.qty;
			row.advice = h.action.label;
			row.tone = h.action.tone;
			m.holds.add(row);
			if (h.action.price != null && h.action.price > 0)
			{
				sl.put(h.itemId, new long[]{Math.round(h.action.price), h.qty});
			}
			nm.put(h.itemId, row.name);
		}

		long staleMs = (long) (Decide.P.BUY_WAIT_HOURS * 3_600_000);
		for (Offer o : Ledger.openOffers(ledger))
		{
			if (o.isBuy() && now - o.placedAt > staleMs)
			{
				m.tidy.add(String.format(Locale.US, "Cancel your %s buy: it's been up %dh and the price has likely moved.",
					nm.getOrDefault(o.itemId, "item " + o.itemId), (now - o.placedAt) / 3_600_000));
			}
		}
		for (Offer o : r.makeRoom)
		{
			m.tidy.add(String.format(Locale.US, "Cancel your %s buy (%d%% filled) to free a slot for selling.",
				nm.getOrDefault(o.itemId, "item " + o.itemId), o.qty > 0 ? Math.round(100.0 * o.filled / o.qty) : 0));
		}

		Ledger.Stats st = Ledger.stats(ledger, ZoneId.systemDefault(), now);
		m.stats = st;
		Map<Integer, String> all = new HashMap<>();
		try
		{
			for (com.flipplanner.engine.ItemMeta it : prices.mapping().values())
			{
				all.put(it.id, it.name);
			}
		}
		catch (java.io.IOException e)
		{
			all.putAll(nm);
		}
		m.names = all;
		m.profitToday = st.today;
		m.profitTotal = st.profit;
		m.flips = st.flips;
		m.winRate = st.winRate;
		m.status = "Updated " + LocalTime.now().format(HM) + " from the "
			+ java.time.Instant.ofEpochSecond(r.barTs).atZone(ZoneId.systemDefault()).toLocalTime().format(HM) + " prices"
			+ (r.capital.slotsFree < config.slots() ? String.format(Locale.US, " · %d of %d slots free", r.capital.slotsFree, config.slots()) : "");

		buys = b;
		sells = sl;
		names = nm;
		searchItems = search;
	}

	@Override
	public long[] buy(int itemId)
	{
		return buys.get(itemId);
	}

	@Override
	public long[] sell(int itemId)
	{
		return sells.get(itemId);
	}

	@Override
	public List<Integer> searchItems()
	{
		return searchItems;
	}
}
