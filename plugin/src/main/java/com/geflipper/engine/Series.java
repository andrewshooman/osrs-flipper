package com.geflipper.engine;

/** Dense hourly arrays ending at {@code barTs} (see {@link Features#denseSeries}). */
public final class Series
{
	/** Average high / low price per hour; NaN where nothing traded. */
	public final double[] ah;
	public final double[] al;
	/** High / low volume per hour; 0 where nothing traded. */
	public final double[] hv;
	public final double[] lv;
	/** Unix seconds of the last bar. */
	public final long barTs;

	public Series(double[] ah, double[] al, double[] hv, double[] lv, long barTs)
	{
		this.ah = ah;
		this.al = al;
		this.hv = hv;
		this.lv = lv;
		this.barTs = barTs;
	}

	/** The last bar, unfilled (what the strategy prices against). */
	public Bar lastBar()
	{
		int last = ah.length - 1;
		return new Bar(ah[last], al[last], hv[last], lv[last]);
	}
}
