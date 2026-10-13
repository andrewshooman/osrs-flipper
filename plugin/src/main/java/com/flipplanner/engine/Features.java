package com.flipplanner.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Live feature builder, a port of js/features.js (itself mirroring research/features.py):
 * the 42 model inputs at the LAST hourly bar of one item. Same windows, NaN rules and
 * sample std; StrictMath for the fdlibm functions V8 uses.
 */
public final class Features
{
	/** Must match research/features.py. */
	public static final double NATURE_RUNE_PRICE = 110;
	public static final int DEFAULT_BARS = 365;
	private static final long STEP = 3600;
	private static final double NaN = Double.NaN;

	private Features()
	{
	}

	/** Dense hourly arrays ending at barTs from /timeseries?timestep=1h points (gaps → NaN / 0). */
	public static Series denseSeries(List<HourPoint> points, long barTs)
	{
		return denseSeries(points, barTs, DEFAULT_BARS);
	}

	public static Series denseSeries(List<HourPoint> points, long barTs, int bars)
	{
		double[] ah = new double[bars];
		double[] al = new double[bars];
		double[] hv = new double[bars];
		double[] lv = new double[bars];
		java.util.Arrays.fill(ah, NaN);
		java.util.Arrays.fill(al, NaN);
		long start = barTs - (bars - 1) * STEP;
		if (points != null)
		{
			for (HourPoint p : points)
			{
				double i = Js.round((double) (p.timestamp - start) / STEP);
				if (i < 0 || i >= bars)
				{
					continue;
				}
				int k = (int) i;
				if (p.avgHighPrice > 0)
				{
					ah[k] = p.avgHighPrice;
				}
				if (p.avgLowPrice > 0)
				{
					al[k] = p.avgLowPrice;
				}
				hv[k] = p.highPriceVolume;
				lv[k] = p.lowPriceVolume;
			}
		}
		return new Series(ah, al, hv, lv, barTs);
	}

	/**
	 * Features at the last bar, in the same key order as the JS. Non-finite values are NaN.
	 * {@code item} and {@code ctx} may be null (no limit / alch value; zero market returns).
	 */
	public static Map<String, Double> itemFeatures(Series s, ItemMeta item, MarketContext ctx)
	{
		int n = s.ah.length;
		int last = n - 1;
		double[] ah = ffill(s.ah, 6);
		double[] al = ffill(s.al, 6);
		double[] mid = new double[n];
		double[] lmid = new double[n];
		double[] ret1 = new double[n];
		double[] bid = new double[n];
		double[] net = new double[n];
		double[] netRel = new double[n];
		for (int i = 0; i < n; i++)
		{
			mid[i] = (ah[i] + al[i]) / 2;
			lmid[i] = StrictMath.log(mid[i]);
			ret1[i] = i > 0 ? lmid[i] - lmid[i - 1] : NaN;
			bid[i] = al[i] + 1;
			double ask = ah[i] - 1;
			net[i] = ask - tax(ask) - bid[i];
			netRel[i] = net[i] / bid[i];
		}
		double lim = item == null ? 0 : item.limit;
		Map<String, Double> f = new LinkedHashMap<>();

		f.put("spread_rel", (ah[last] - al[last]) / mid[last]);
		f.put("net_margin_rel", netRel[last]);
		f.put("net_margin_gp_log", Js.finite(net[last]) ? Math.signum(net[last]) * StrictMath.log1p(Math.abs(net[last])) : NaN);
		f.put("log_price", StrictMath.log(mid[last]));
		f.put("log_limit", StrictMath.log1p(lim));
		f.put("log_limit_value", StrictMath.log1p(lim * mid[last]));

		for (int h : new int[]{1, 6, 24})
		{
			double sh = sum(s.hv, n - h, n);
			double sl = sum(s.lv, n - h, n);
			f.put("log_vol_buy_" + h + "h", StrictMath.log1p(sl / h));
			f.put("log_vol_sell_" + h + "h", StrictMath.log1p(sh / h));
			f.put("imbalance_" + h + "h", (sh - sl) / (sh + sl + 1));
		}
		double[] tot = new double[n];
		for (int i = 0; i < n; i++)
		{
			tot[i] = s.hv[i] + s.lv[i];
		}
		f.put("vol_surge", tot[last] / (mean(tot, n - 24, n, 1) + 1));
		f.put("log_limit_over_vol", StrictMath.log1p(lim / (sum(s.lv, n - 4, n) + 1)));

		for (int h : new int[]{1, 3, 6, 24, 72, 168})
		{
			f.put("ret_" + h + "h", last - h >= 0 ? lmid[last] - lmid[last - h] : NaN);
		}
		for (int h : new int[]{24, 168})
		{
			int minP = Math.max(3, h / 4);
			f.put("vol_" + h + "h", std(ret1, n - h, n, minP));
			f.put("z_" + h + "h", (lmid[last] - mean(lmid, n - h, n, minP)) / std(lmid, n - h, n, minP));
			double lo = Double.POSITIVE_INFINITY;
			double hi = Double.NEGATIVE_INFINITY;
			int cnt = 0;
			for (int i = Math.max(0, n - h); i < n; i++)
			{
				if (Js.finite(mid[i]))
				{
					lo = Math.min(lo, mid[i]);
					hi = Math.max(hi, mid[i]);
					cnt++;
				}
			}
			if (cnt < 3)
			{
				lo = NaN;
				hi = NaN;
			}
			f.put("range_pos_" + h + "h", (mid[last] - lo) / (hi - lo));
		}
		double[] lag = new double[n];
		for (int i = 0; i < n; i++)
		{
			lag[i] = i > 0 ? ret1[i - 1] : NaN;
		}
		f.put("autocorr_168h", corr(ret1, lag, n - 168, n, 42));

		double[] prof = new double[n];
		for (int i = 0; i < n; i++)
		{
			prof[i] = Js.finite(net[i]) ? (net[i] > 0 ? 1 : 0) : NaN;
		}
		for (int h : new int[]{6, 24})
		{
			f.put("margin_persist_" + h + "h", mean(prof, n - h, n, 2));
		}
		f.put("margin_median_rel_24h", median(netRel, n - 24, n, 3));
		f.put("margin_vs_median", f.get("net_margin_rel") - f.get("margin_median_rel_24h"));
		double[] active = new double[n];
		for (int i = 0; i < n; i++)
		{
			active[i] = Js.finite(s.ah[i]) && Js.finite(s.al[i]) ? 1 : 0;
		}
		f.put("active_24h", mean(active, n - 24, n, 1));

		f.put("mkt_ret_1h", ctx == null ? 0 : ctx.mktRet1h);
		f.put("mkt_ret_24h", ctx == null ? 0 : ctx.mktRet24h);
		long hour = Math.floorDiv(s.barTs, 3600) % 24;
		long dow = (Math.floorDiv(s.barTs, 86400) + 4) % 7;
		double how = (2 * Math.PI * (dow * 24 + hour)) / 168;
		f.put("how_sin", StrictMath.sin(how));
		f.put("how_cos", StrictMath.cos(how));
		f.put("hour_sin", StrictMath.sin((2 * Math.PI * hour) / 24));
		f.put("hour_cos", StrictMath.cos((2 * Math.PI * hour) / 24));
		double alch = (item == null ? 0 : item.highalch) - NATURE_RUNE_PRICE;
		f.put("alch_floor_rel", alch > 0 ? alch / bid[last] : 0);
		for (Map.Entry<String, Double> e : f.entrySet())
		{
			if (!Js.finite(e.getValue()))
			{
				e.setValue(NaN);
			}
		}
		return f;
	}

	/** Tax as research/features.py applies it: no exemptions. */
	private static double tax(double p)
	{
		return Math.min(Math.floor(p * 0.02), 5e6);
	}

	// ---- window helpers over a[from..to) (from clamped to 0), NaN-skipping like pandas ----

	private static double sum(double[] a, int from, int to)
	{
		double s = 0;
		for (int i = Math.max(0, from); i < to; i++)
		{
			s += Js.finite(a[i]) ? a[i] : 0;
		}
		return s;
	}

	private static double mean(double[] a, int from, int to, int minP)
	{
		double s = 0;
		int c = 0;
		for (int i = Math.max(0, from); i < to; i++)
		{
			if (Js.finite(a[i]))
			{
				s += a[i];
				c++;
			}
		}
		return c >= minP ? s / c : NaN;
	}

	private static double std(double[] a, int from, int to, int minP)
	{
		double[] v = valid(a, from, to);
		if (v.length < Math.max(minP, 2))
		{
			return NaN;
		}
		double s = 0;
		for (double x : v)
		{
			s += x;
		}
		double m = s / v.length;
		double ss = 0;
		for (double x : v)
		{
			ss += (x - m) * (x - m);
		}
		return Math.sqrt(ss / (v.length - 1));
	}

	private static double median(double[] a, int from, int to, int minP)
	{
		double[] v = valid(a, from, to);
		java.util.Arrays.sort(v);
		if (v.length < minP || v.length == 0)
		{
			return NaN;
		}
		int h = v.length >> 1;
		return v.length % 2 == 1 ? v[h] : (v[h - 1] + v[h]) / 2;
	}

	private static double corr(double[] x, double[] y, int from, int to, int minP)
	{
		int lo = Math.max(0, from);
		double[] px = new double[to - lo];
		double[] py = new double[to - lo];
		int c = 0;
		for (int i = lo; i < to; i++)
		{
			if (Js.finite(x[i]) && Js.finite(y[i]))
			{
				px[c] = x[i];
				py[c] = y[i];
				c++;
			}
		}
		if (c < minP || c < 2)
		{
			return NaN;
		}
		double sx = 0;
		double sy = 0;
		for (int i = 0; i < c; i++)
		{
			sx += px[i];
		}
		for (int i = 0; i < c; i++)
		{
			sy += py[i];
		}
		double mx = sx / c;
		double my = sy / c;
		double sxy = 0;
		double sxx = 0;
		double syy = 0;
		for (int i = 0; i < c; i++)
		{
			sxy += (px[i] - mx) * (py[i] - my);
			sxx += (px[i] - mx) * (px[i] - mx);
			syy += (py[i] - my) * (py[i] - my);
		}
		return sxx > 0 && syy > 0 ? sxy / Math.sqrt(sxx * syy) : NaN;
	}

	private static double[] valid(double[] a, int from, int to)
	{
		int lo = Math.max(0, from);
		double[] v = new double[to - lo];
		int c = 0;
		for (int i = lo; i < to; i++)
		{
			if (Js.finite(a[i]))
			{
				v[c++] = a[i];
			}
		}
		return java.util.Arrays.copyOf(v, c);
	}

	/** Forward-fill gaps of at most {@code limit} bars. */
	private static double[] ffill(double[] a, int limit)
	{
		double[] out = a.clone();
		double last = NaN;
		int gap = 0;
		for (int i = 0; i < out.length; i++)
		{
			if (Js.finite(out[i]))
			{
				last = out[i];
				gap = 0;
			}
			else if (Js.finite(last) && ++gap <= limit)
			{
				out[i] = last;
			}
		}
		return out;
	}
}
