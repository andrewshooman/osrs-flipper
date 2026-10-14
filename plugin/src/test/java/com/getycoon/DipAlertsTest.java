package com.getycoon;

import com.getycoon.engine.HourPoint;
import com.getycoon.engine.ItemMeta;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class DipAlertsTest
{
	/** A liquid item: 24h instant-sells average 1,000 gp on 288,000 a day (1,000 per 5 minutes), instant-buys 1,040. */
	private static final HourPoint DAY = new HourPoint(0, 1_040, 1_000, 280_000, 288_000);

	private static Map<Integer, ItemMeta> meta()
	{
		Map<Integer, ItemMeta> m = new HashMap<>();
		m.put(1, new ItemMeta(1, "Thing", true, 5_000, 0, 0));
		m.put(2, new ItemMeta(2, "Other", false, 0, 0, 0));
		return m;
	}

	private static List<DipAlerts.Alert> find(HourPoint five, HourPoint day, Set<Integer> skip, boolean members, double free)
	{
		return DipAlerts.find(Collections.singletonMap(1, five), Collections.singletonMap(1, day), meta(), skip, members, 0.08, free);
	}

	@Test
	public void dipOnHeavyVolumeAlerts()
	{
		List<DipAlerts.Alert> a = find(new HourPoint(1791900000L, 1_000, 880, 500, 3_000), DAY, Collections.emptySet(), true, 50_000_000);
		assertEquals(1, a.size());
		DipAlerts.Alert d = a.get(0);
		assertEquals(880, d.price);
		assertEquals(1_029, d.target);              // 1% under the 24h instant-buy average
		assertEquals(2_272, d.qty);                 // 2M cap / 880 (under the 5,000 limit)
		assertEquals(0.12, d.drop, 1e-9);
		assertEquals(2_272 * (1_029 - 20 - 880), d.expected, 1e-6); // 2% tax on 1,029 is 20
		assertEquals(1791900000L, d.bucket);
		assertTrue(d.text().startsWith("Thing dipped 12% to 880. Buy up to 2,272 at 880, sell at 1,029"));
	}

	@Test
	public void noAlertWithoutAllConditions()
	{
		// Only 5% under the average.
		assertTrue(find(new HourPoint(1, 1_000, 950, 500, 3_000), DAY, Collections.emptySet(), true, 50e6).isEmpty());
		// Normal volume (under twice the usual 1,000 per 5 minutes).
		assertTrue(find(new HourPoint(1, 1_000, 880, 500, 1_500), DAY, Collections.emptySet(), true, 50e6).isEmpty());
		// Illiquid: 24h instant-sells turn over less than 50M.
		assertTrue(find(new HourPoint(1, 1_000, 880, 50, 300), new HourPoint(0, 1_040, 1_000, 2_000, 2_880),
			Collections.emptySet(), true, 50e6).isEmpty());
		// Members item on a free-to-play setting, a blocked item, no free gp.
		assertTrue(find(new HourPoint(1, 1_000, 880, 500, 3_000), DAY, Collections.emptySet(), false, 50e6).isEmpty());
		assertTrue(find(new HourPoint(1, 1_000, 880, 500, 3_000), DAY, Collections.singleton(1), true, 50e6).isEmpty());
		assertTrue(find(new HourPoint(1, 1_000, 880, 500, 3_000), DAY, Collections.emptySet(), true, 500).isEmpty());
		// The sell price after tax doesn't clear the dip price.
		assertTrue(find(new HourPoint(1, 1_000, 900, 500, 3_000), new HourPoint(0, 910, 1_000, 280_000, 288_000),
			Collections.emptySet(), true, 50e6).isEmpty());
	}

	@Test
	public void limitAndFreeGpCapTheQuantity()
	{
		HourPoint five = new HourPoint(1, 1_000, 880, 500, 3_000);
		assertEquals(1_136, find(five, DAY, Collections.emptySet(), true, 1_000_000).get(0).qty);
		Map<Integer, ItemMeta> m = meta();
		m.put(1, new ItemMeta(1, "Thing", true, 100, 0, 0));
		assertEquals(100, DipAlerts.find(Collections.singletonMap(1, five), Collections.singletonMap(1, DAY), m,
			Collections.emptySet(), true, 0.08, 50e6).get(0).qty);
		// Only what's left of the buy limit (bought earlier in the window, or in open buy offers).
		assertEquals(40, DipAlerts.find(Collections.singletonMap(1, five), Collections.singletonMap(1, DAY), m,
			Collections.emptySet(), true, 0.08, 50e6, (id, limit) -> limit - 60).get(0).qty);
		assertTrue(DipAlerts.find(Collections.singletonMap(1, five), Collections.singletonMap(1, DAY), m,
			Collections.emptySet(), true, 0.08, 50e6, (id, limit) -> 0).isEmpty());
	}

	@Test
	public void bestFirst()
	{
		Map<Integer, HourPoint> five = new HashMap<>();
		five.put(1, new HourPoint(1, 1_000, 900, 500, 3_000));
		five.put(2, new HourPoint(1, 1_000, 800, 500, 3_000));
		Map<Integer, HourPoint> day = new HashMap<>();
		day.put(1, DAY);
		day.put(2, DAY);
		List<DipAlerts.Alert> a = DipAlerts.find(five, day, meta(), Collections.emptySet(), true, 0.08, 50e6);
		assertEquals(2, a.size());
		assertEquals(2, a.get(0).itemId);
	}
}
