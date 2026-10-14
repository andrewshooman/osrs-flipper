package com.getycoon.engine;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Market data from the OSRS Wiki real-time prices API (or a fake, in tests). */
public interface PriceSource
{
	/** Item metadata by id (/mapping). */
	Map<Integer, ItemMeta> mapping() throws IOException;

	/** Whole-market averages for the hour starting at {@code timestampSec} (/1h?timestamp=). */
	Map<Integer, HourPoint> snapshot1h(long timestampSec) throws IOException;

	/** Whole-market 24h averages (/24h); null when unavailable. */
	Map<Integer, HourPoint> avg24h() throws IOException;

	/** Up to 365 hourly points for one item, oldest first (/timeseries?timestep=1h&id=). */
	List<HourPoint> timeseries1h(int itemId) throws IOException;

	/** Latest instant-buy / instant-sell price per item, as {high, low}; 0 when unknown (/latest). */
	Map<Integer, long[]> latest() throws IOException;
}
