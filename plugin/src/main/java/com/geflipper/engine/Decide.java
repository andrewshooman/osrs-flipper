package com.geflipper.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The validated strategy (js/decide.js, ported from research/strategy_model.py), checkin
 * preset. Inputs per item: features at the latest hourly bar and model predictions
 * (fill, ret, hours, fwd4_q10/50/90). Outputs are plain orders a person can place.
 */
public final class Decide
{
	/**
	 * research/strategy_model.py PRESETS["checkin"]: the production preset with a 10%
	 * per-item cap, which won for a player who checks in ~3x a day.
	 */
	public static final class P
	{
		public static final double KELLY_FRAC = 0.15;
		public static final double MAX_ITEM_FRAC = 0.10;
		public static final double MIN_EXP_RET = 0.005;
		/** Patient offers: leave them up for ~6h, then cancel the rest. */
		public static final double BUY_WAIT_HOURS = 6;
		/** Share of real volume we expect to get. */
		public static final double CAPTURE_SHARE = 0.10;
		/** Pump-and-dump guard. */
		public static final double GUARD_MAX_MARGIN = 0.15;
		public static final double GUARD_MAX_RET_24H = 0.25;
		public static final double GUARD_MAX_Z_168H = 3;
		public static final double GUARD_MIN_ACTIVE = 0.5;
		/** Sell anything held 2 days (research ModelStrategy.max_hold_h). */
		public static final double MAX_HOLD_HOURS = 48;

		private P()
		{
		}
	}

	private static final double LOG1P2 = StrictMath.log1p(2);

	private Decide()
	{
	}

	/** Candidate screen + pump-and-dump guard (same rules as the backtest). */
	public static boolean eligible(Map<String, Double> f)
	{
		if (!(get(f, "net_margin_rel") > 0) || !(get(f, "log_vol_buy_6h") > LOG1P2) || !(get(f, "log_vol_sell_6h") > LOG1P2))
		{
			return false;
		}
		if (get(f, "net_margin_rel") > P.GUARD_MAX_MARGIN || get(f, "ret_24h") > P.GUARD_MAX_RET_24H
			|| get(f, "z_168h") > P.GUARD_MAX_Z_168H)
		{
			return false;
		}
		return get(f, "active_24h") >= P.GUARD_MIN_ACTIVE;
	}

	/** Ranked orders that fit {@code deployable} gp and {@code slotsFree} GE slots. */
	public static BuyPlan buyPlan(List<BuyCandidate> items, double deployable, int slotsFree, double mktRet24h)
	{
		return buyPlan(items, deployable, slotsFree, mktRet24h, 0);
	}

	/** As above, leaving out orders expected to make less than {@code minExpectedGp} (after shrinking to the gp left). */
	public static BuyPlan buyPlan(List<BuyCandidate> items, double deployable, int slotsFree, double mktRet24h,
		double minExpectedGp)
	{
		double deploy = deployable;
		if (mktRet24h < -0.03)
		{
			deploy *= 0.5; // whole market falling: half size
		}
		List<BuyIdea> ideas = new ArrayList<>();
		for (BuyCandidate it : items)
		{
			Map<String, Double> pred = it.pred;
			Bar bar = it.bar;
			if (!eligible(it.features) || bar == null || !(bar.al > 0))
			{
				continue;
			}
			double fill = Fmt.clamp(get(pred, "fill"), 0, 1);
			double expRet = fill * get(pred, "ret");
			if (!(expRet > P.MIN_EXP_RET))
			{
				continue;
			}
			double hours = Math.max(get(pred, "hours"), 0.25);
			double spread = Math.max(get(pred, "fwd4_q90") - get(pred, "fwd4_q10"), 1e-3) / 2.56;
			long buy = (long) Js.round(bar.al) + 1;
			double rate = Math.min(bar.lv, bar.hv) * P.CAPTURE_SHARE; // per hour
			double kelly = Fmt.clamp((P.KELLY_FRAC * expRet) / (spread * spread), 0, P.MAX_ITEM_FRAC);
			double budget = Math.min(kelly * deploy, deploy * P.MAX_ITEM_FRAC);
			// NaN survives the qty < 1 test, as in the JS
			double qty = Math.floor(Math.min(Math.min(budget / buy, it.limitLeft), rate * 4));
			if (qty < 1)
			{
				continue;
			}
			ideas.add(new BuyIdea(it.id, it.name, buy, qty, qty * buy, expRet, qty * buy * expRet, hours,
				Js.round(bar.ah) - 1, fill, get(pred, "fwd4_q50"), Arrays.asList(
				Fmt.pct(expRet, 2) + " expected after tax (fills " + Fmt.pct(fill, 0) + " of the time)",
				"~" + Fmt.duration(hours) + " to buy and sell; up to 10% of your free gp, capped by the buy limit and what the market can fill")));
		}
		final double d = deploy;
		final int s = slotsFree;
		JsSort.sort(ideas, (a, b) -> b.expected / b.hours / usage(b, d, s) - a.expected / a.hours / usage(a, d, s));
		List<BuyIdea> plan = new ArrayList<>();
		double left = deploy;
		int slots = slotsFree;
		for (BuyIdea r : ideas)
		{
			if (slots <= 0)
			{
				break;
			}
			if (r.cost > left)
			{
				double q = Math.floor(left / r.buy);
				if (q < 1)
				{
					continue;
				}
				r.qty = q;
				r.cost = q * r.buy;
				r.expected = q * r.buy * r.expRet;
			}
			if (r.expected < minExpectedGp)
			{
				continue;
			}
			plan.add(r);
			left -= r.cost;
			slots--;
		}
		return new BuyPlan(plan, ideas, deploy - left);
	}

	private static double usage(BuyIdea r, double deploy, int slots)
	{
		return Math.max(r.cost / Math.max(deploy, 1), 1.0 / Math.max(slots, 1));
	}

	/**
	 * What to do with a holding of {@code qty} at {@code avgCost}; bar: latest hourly bar
	 * (may be null); pred: fwd4 quantiles. Mirrors ModelStrategy.manage().
	 */
	public static HoldingAction holdingAction(double qty, double avgCost, double heldHours, Bar bar, Map<String, Double> pred,
		int itemId)
	{
		if (bar == null || !(bar.ah > 0))
		{
			return new HoldingAction("wait", null, "No recent price — check again later", "muted");
		}
		long ask = (long) Js.round(bar.ah) - 1;
		long netNow = Tax.netSellPrice(ask, itemId);
		long be = Tax.breakevenSellPrice(avgCost, itemId);
		double q10 = get(pred, "fwd4_q10");
		double q50 = get(pred, "fwd4_q50");
		double q90 = get(pred, "fwd4_q90");
		if (heldHours >= P.MAX_HOLD_HOURS)
		{
			double now = Js.round(bar.al);
			return new HoldingAction("cut", now, "Held over " + Js.toString(Js.round(P.MAX_HOLD_HOURS / 24))
				+ " days: sell now (~" + Fmt.full(now) + ") and free the gp", "warn");
		}
		if (netNow < avgCost)
		{
			double optimistic = ask * StrictMath.exp(q90);
			if (Tax.netSellPrice(optimistic, itemId) < avgCost && q50 < 0)
			{
				double now = Js.round(bar.al);
				return new HoldingAction("cut", now, "Cut: even the good case won't reach breakeven. Sell now (~"
					+ Fmt.full(now) + ")", "bad");
			}
			long price = Math.max(ask, be);
			return new HoldingAction("hold", (double) price, "Hold — list at " + Fmt.full(price) + " (breakeven "
				+ Fmt.full(be) + ")", "muted");
		}
		if (q50 < -0.002 || q10 < -0.03)
		{
			return new HoldingAction("sell", (double) ask, "Sell now at " + Fmt.full(ask) + " — forecast is falling", "good");
		}
		return new HoldingAction("list", (double) ask, "List at " + Fmt.full(ask) + " (profit "
			+ Fmt.gp((netNow - avgCost) * qty, true) + ")", "good");
	}

	private static double get(Map<String, Double> m, String k)
	{
		Double v = m == null ? null : m.get(k);
		return v == null ? Double.NaN : v;
	}
}
