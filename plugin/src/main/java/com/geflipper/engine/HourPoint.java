package com.geflipper.engine;

/**
 * One bucket of the wiki's /1h, /24h or /timeseries data. Prices are NaN and volumes 0
 * when the API has no trades for that side.
 */
public final class HourPoint
{
	/** Unix seconds of the bucket start (0 for whole-market snapshots). */
	public final long timestamp;
	/** Average instant-buy price (what sell offers fill at). */
	public final double avgHighPrice;
	/** Average instant-sell price (what buy offers fill at). */
	public final double avgLowPrice;
	/** Units instantly bought: volume your sell offers can fill against. */
	public final long highPriceVolume;
	/** Units instantly sold: volume your buy offers can fill against. */
	public final long lowPriceVolume;

	public HourPoint(long timestamp, double avgHighPrice, double avgLowPrice, long highPriceVolume, long lowPriceVolume)
	{
		this.timestamp = timestamp;
		this.avgHighPrice = avgHighPrice;
		this.avgLowPrice = avgLowPrice;
		this.highPriceVolume = highPriceVolume;
		this.lowPriceVolume = lowPriceVolume;
	}

	@Override
	public String toString()
	{
		return "HourPoint{" + timestamp + ", high " + avgHighPrice + " x" + highPriceVolume
			+ ", low " + avgLowPrice + " x" + lowPriceVolume + "}";
	}
}
