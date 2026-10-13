package com.geflipper.engine;

/** One hourly bar: average high/low price (NaN if none) and volumes. */
public final class Bar
{
	public final double ah;
	public final double al;
	public final double hv;
	public final double lv;

	public Bar(double ah, double al, double hv, double lv)
	{
		this.ah = ah;
		this.al = al;
		this.hv = hv;
		this.lv = lv;
	}

	@Override
	public String toString()
	{
		return "Bar{ah=" + ah + ", al=" + al + ", hv=" + hv + ", lv=" + lv + "}";
	}
}
