package com.flipplanner.ui;

import com.flipplanner.engine.HourPoint;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class PriceGraphPanelTest
{
	private static final long NOW_SEC = StatsPanelTest.NOW / 1000;
	private static final int W = 520;
	private static final int H = 320;

	/** 168 hourly bars up to NOW_SEC around 1.5M, with a few hours missing on one or both sides. */
	static List<HourPoint> points()
	{
		Random r = new Random(11);
		List<HourPoint> out = new ArrayList<>();
		long first = (NOW_SEC / 3600 - 168) * 3600;
		double mid = 1_500_000;
		for (int i = 0; i < 168; i++)
		{
			mid += (r.nextDouble() - 0.48) * 9_000;
			double spread = 18_000 + r.nextDouble() * 14_000;
			double high = mid + spread / 2;
			double low = mid - spread / 2;
			if (i >= 40 && i <= 42)
			{
				high = Double.NaN;
			}
			if (i == 100 || i == 101)
			{
				low = Double.NaN;
			}
			if (i >= 130 && i <= 134 && i != 132)
			{
				high = Double.NaN;
				low = Double.NaN;
			}
			out.add(new HourPoint(first + i * 3600L, high, low, Double.isNaN(high) ? 0 : 40 + r.nextInt(80), Double.isNaN(low) ? 0 : 40 + r.nextInt(80)));
		}
		return out;
	}

	private static PriceGraphPanel graph(List<HourPoint> pts, Double buy, Double sell)
	{
		PriceGraphPanel g = new PriceGraphPanel();
		g.setData("Abyssal whip", pts, buy, sell, NOW_SEC);
		return g;
	}

	@Test
	public void paintsAWeekWithSuggestedPrices() throws Exception
	{
		PriceGraphPanel g = graph(points(), 1_490_000d, 1_528_000d);
		UiTestSupport.save(UiTestSupport.paint(g, W, H), "graph.png");

		g.hover(new Point(300, 150));
		UiTestSupport.save(UiTestSupport.paint(g, W, H), "graph-hover.png");
		g.hover(new Point(W - 12, 260));
		UiTestSupport.save(UiTestSupport.paint(g, W, H), "graph-hover-right.png");
		g.hover(new Point(2, 2));
		UiTestSupport.paint(g, W, H);
		g.hover(null);
		UiTestSupport.save(UiTestSupport.paint(g, 225, 160), "graph-small.png");
	}

	@Test
	public void suggestionsOutsideThePriceRangeAndClose() throws Exception
	{
		UiTestSupport.save(UiTestSupport.paint(graph(points(), 1_400_000d, 1_401_000d), W, H), "graph-close-targets.png");
		UiTestSupport.paint(graph(points(), null, 1_700_000d), W, H);
		UiTestSupport.paint(graph(points(), Double.NaN, null), W, H);
	}

	@Test
	public void noUsableHistory() throws Exception
	{
		UiTestSupport.save(UiTestSupport.paint(graph(Collections.<HourPoint>emptyList(), 1_490_000d, 1_528_000d), W, H), "graph-empty.png");
		UiTestSupport.paint(graph(null, null, null), W, H);
		List<HourPoint> nan = new ArrayList<>();
		for (int i = 0; i < 168; i++)
		{
			nan.add(new HourPoint(NOW_SEC - i * 3600L, Double.NaN, Double.NaN, 0, 0));
		}
		PriceGraphPanel g = graph(nan, 100d, 120d);
		g.hover(new Point(200, 150));
		UiTestSupport.paint(g, W, H);
		// Only data older than the week shown.
		UiTestSupport.paint(graph(Collections.singletonList(new HourPoint(NOW_SEC - 30 * 86400L, 5, 4, 1, 1)), null, null), W, H);
	}

	@Test
	public void flatAndCheapSeries() throws Exception
	{
		List<HourPoint> flat = new ArrayList<>();
		for (int i = 0; i < 168; i++)
		{
			flat.add(new HourPoint(NOW_SEC - (167 - i) * 3600L, 187, i % 5 == 0 ? Double.NaN : 186, 5000, 5000));
		}
		PriceGraphPanel g = graph(flat, 185d, 188d);
		g.hover(new Point(100, 100));
		UiTestSupport.save(UiTestSupport.paint(g, W, H), "graph-cheap.png");
	}

	@Test
	public void axis()
	{
		assertEquals(25_000, PriceGraphPanel.niceStep(23_000), 0);
		assertEquals(1, PriceGraphPanel.niceStep(0.2), 0);
		assertEquals(5, PriceGraphPanel.niceStep(4.1), 0);
		assertEquals("1.225M", PriceGraphPanel.axisGp(1_225_000, 25_000, 1_300_000));
		assertEquals("1.20M", PriceGraphPanel.axisGp(1_200_000, 20_000, 1_300_000));
		assertEquals("14.5K", PriceGraphPanel.axisGp(14_500, 500, 15_000));
		assertEquals("9,500", PriceGraphPanel.axisGp(9_500, 500, 9_900));
		assertEquals("2B", PriceGraphPanel.axisGp(2e9, 1e9, 3e9));
	}
}
