package com.geflipper.engine;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

/** Whole-market median returns, fed to the model as mkt_ret_1h / mkt_ret_24h. */
public final class MarketContext
{
	public static final MarketContext ZERO = new MarketContext(0, 0);

	public final double mktRet1h;
	public final double mktRet24h;

	public MarketContext(double mktRet1h, double mktRet24h)
	{
		this.mktRet1h = mktRet1h;
		this.mktRet24h = mktRet24h;
	}

	/**
	 * Median h-hour log return across liquid items (24h low volume over 120), from
	 * whole-market /1h snapshots. {@code day24} (the /24h averages) may be null: 0 returns.
	 */
	public static MarketContext compute(Map<Integer, HourPoint> now, Map<Integer, HourPoint> prev1,
		Map<Integer, HourPoint> prev24, Map<Integer, HourPoint> day24)
	{
		if (now == null)
		{
			now = Collections.emptyMap();
		}
		int[] liquid = new int[now.size()];
		int n = 0;
		for (Integer id : now.keySet())
		{
			HourPoint d = day24 == null ? null : day24.get(id);
			if (d != null && d.lowPriceVolume / 24.0 > 5)
			{
				liquid[n++] = id;
			}
		}
		int[] ids = Arrays.copyOf(liquid, n);
		return new MarketContext(median(now, prev1, ids), median(now, prev24, ids));
	}

	private static double median(Map<Integer, HourPoint> now, Map<Integer, HourPoint> prev, int[] ids)
	{
		double[] r = new double[ids.length];
		int n = 0;
		for (int id : ids)
		{
			double x = StrictMath.log(midOf(now.get(id))) - StrictMath.log(midOf(prev == null ? null : prev.get(id)));
			if (Js.finite(x))
			{
				r[n++] = x;
			}
		}
		if (n == 0)
		{
			return 0;
		}
		Arrays.sort(r, 0, n);
		return r[n >> 1];
	}

	static double midOf(HourPoint d)
	{
		return d != null && d.avgHighPrice > 0 && d.avgLowPrice > 0 ? (d.avgHighPrice + d.avgLowPrice) / 2 : Double.NaN;
	}

	@Override
	public String toString()
	{
		return "MarketContext{1h=" + mktRet1h + ", 24h=" + mktRet24h + "}";
	}
}
