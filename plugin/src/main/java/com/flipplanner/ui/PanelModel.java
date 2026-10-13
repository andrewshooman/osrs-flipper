package com.flipplanner.ui;

import com.flipplanner.engine.Ledger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Everything the side panel shows, filled in by the plugin. */
public class PanelModel
{
	public static class Buy
	{
		public int itemId;
		public String name;
		public long price;
		public long qty;
		public long sellTarget;
		public double expected;
		public double hours;
		public String warning;
	}

	public static class Hold
	{
		public int itemId;
		public String name;
		public long qty;
		public String advice;
		/** good | warn | bad | muted */
		public String tone;
	}

	public String status = "";
	public String error;
	public boolean loading;
	/** Shown instead of the buy list when there's nothing worth buying. */
	public String noBuys;
	public double freeGp;
	public double expectedProfit;
	public final List<Buy> buys = new ArrayList<>();
	public final List<Hold> holds = new ArrayList<>();
	public final List<String> tidy = new ArrayList<>();
	/** Live dip alerts, newest first. */
	public final List<String> dips = new ArrayList<>();
	public double profitToday;
	public double profitTotal;
	public int flips;
	/** Realised profit since logging in, and how long ago that was. */
	public double sessionProfit;
	public double sessionHours;
	public double winRate = Double.NaN;
	public String tradeFile = "";
	/** The detected stack and its parts, or a hint to open the bank; null to show nothing. */
	public String stackNote;
	/** For the Stats tab; null until the first run. */
	public Ledger.Stats stats;
	public Map<Integer, String> names = Collections.emptyMap();

	/** A copy to change without touching what the panel is showing. */
	public PanelModel copy()
	{
		PanelModel m = new PanelModel();
		m.status = status;
		m.error = error;
		m.loading = loading;
		m.noBuys = noBuys;
		m.freeGp = freeGp;
		m.expectedProfit = expectedProfit;
		m.buys.addAll(buys);
		m.holds.addAll(holds);
		m.tidy.addAll(tidy);
		m.dips.addAll(dips);
		m.profitToday = profitToday;
		m.profitTotal = profitTotal;
		m.flips = flips;
		m.sessionProfit = sessionProfit;
		m.sessionHours = sessionHours;
		m.winRate = winRate;
		m.tradeFile = tradeFile;
		m.stackNote = stackNote;
		m.stats = stats;
		m.names = names;
		return m;
	}
}
