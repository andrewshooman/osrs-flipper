package com.getycoon.ui;

import com.getycoon.engine.Ledger;
import com.getycoon.engine.LedgerData;
import com.getycoon.engine.Trade;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import javax.swing.JPanel;
import net.runelite.client.ui.PluginPanel;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class StatsPanelTest
{
	static final ZoneId ZONE = ZoneId.systemDefault();
	static final long NOW = ZonedDateTime.of(2026, 10, 13, 18, 30, 0, 0, ZONE).toInstant().toEpochMilli();
	private static final long HOUR = 3_600_000L;
	/** Item 30750 has no name on purpose. */
	private static final int[] IDS = {4151, 11802, 12934, 560, 12695, 21003, 13576, 30750};
	private static final long[] PRICES = {1_520_000, 9_600_000, 158, 186, 12_400, 6_200_000, 28_000_000, 54_000};
	private static final long[] QTYS = {2, 1, 25_000, 12_000, 400, 1, 1, 100};

	static Map<Integer, String> names()
	{
		Map<Integer, String> m = new HashMap<>();
		m.put(4151, "Abyssal whip");
		m.put(11802, "Armadyl godsword");
		m.put(12934, "Zulrah's scales");
		m.put(560, "Death rune");
		m.put(12695, "Super combat potion(4)");
		m.put(21003, "Elder maul");
		m.put(13576, "Dragon warhammer");
		return m;
	}

	/** 40 flips over the 10 days before NOW, some winning and some losing; the newest sold minutes ago. */
	static LedgerData ledger()
	{
		Random r = new Random(7);
		List<Trade> trades = new ArrayList<>();
		for (int i = 0; i < 40; i++)
		{
			int k = r.nextInt(IDS.length);
			long sellTs = NOW - (39 - i) * 6 * HOUR - (i == 39 ? 12 * 60_000L : r.nextInt(2 * 3600) * 1000L);
			long buyTs = sellTs - HOUR / 2 - r.nextInt(18 * 3600) * 1000L;
			long buy = Math.round(PRICES[k] * (0.97 + r.nextDouble() * 0.06));
			long sell = Math.round(buy * (0.99 + r.nextDouble() * 0.08));
			long qty = Math.max(1, Math.round(QTYS[k] * (0.5 + r.nextDouble())));
			trades.add(new Trade("b" + i, IDS[k], "buy", qty, buy, buyTs));
			trades.add(new Trade("s" + i, IDS[k], "sell", qty, sell, sellTs));
		}
		return new LedgerData(trades, new ArrayList<>());
	}

	private static Ledger.Stats stats()
	{
		return Ledger.stats(ledger(), ZONE, NOW);
	}

	@Test
	public void showsFlipsWithinTheSidePanel() throws Exception
	{
		Ledger.Stats s = stats();
		assertEquals(40, s.flips);
		assertTrue("need wins and losses", s.winRate > 0 && s.winRate < 1);

		StatsPanel panel = new StatsPanel(null);
		panel.show(s, names(), NOW);
		JPanel host = UiTestSupport.sidePanel(panel);
		BufferedImage img = UiTestSupport.paint(host, PluginPanel.PANEL_WIDTH, 900);
		UiTestSupport.save(img, "stats.png");
		UiTestSupport.assertNoOverflow(host, PluginPanel.PANEL_WIDTH);
		assertTrue("preferred width " + panel.getPreferredSize().width, panel.getPreferredSize().width <= PluginPanel.PANEL_WIDTH - 16);

		int full = host.getPreferredSize().height;
		UiTestSupport.save(UiTestSupport.paint(host, PluginPanel.PANEL_WIDTH, full), "stats-full.png");
		UiTestSupport.assertNoOverflow(host, PluginPanel.PANEL_WIDTH);

		List<String> texts = UiTestSupport.texts(panel);
		assertTrue(texts.contains("Item 30750"));
		assertTrue(texts.contains("Recent flips"));
		assertTrue(texts.contains("12m ago"));
	}

	@Test
	public void narrowPanelCutsTextInsteadOfOverflowing()
	{
		StatsPanel panel = new StatsPanel(null);
		panel.show(stats(), names(), NOW);
		JPanel host = UiTestSupport.sidePanel(panel);
		UiTestSupport.paint(host, 170, 900);
		UiTestSupport.assertNoOverflow(host, 170);
	}

	@Test
	public void weekSumsTheLastSevenLocalDays()
	{
		Ledger.Stats s = stats();
		LocalDate today = LocalDate.of(2026, 10, 13);
		double week = 0;
		for (Ledger.DayProfit d : s.byDay)
		{
			LocalDate day = LocalDate.parse(d.day);
			if (!day.isBefore(today.minusDays(6)) && !day.isAfter(today))
			{
				week += d.profit;
			}
		}
		StatsPanel panel = new StatsPanel(null);
		panel.show(s, names(), NOW);
		List<String> texts = UiTestSupport.texts(panel);
		assertEquals(StatsPanel.profit(week), texts.get(texts.indexOf("Last 7 days") + 1));
		assertEquals(StatsPanel.profit(s.today), texts.get(texts.indexOf("Today") + 1));
		assertEquals(StatsPanel.profit(s.profit), texts.get(texts.indexOf("All time") + 1));
	}

	@Test
	public void dayBarsHaveTooltips()
	{
		StatsPanel panel = new StatsPanel(null);
		panel.show(stats(), names(), NOW);
		UiTestSupport.paint(UiTestSupport.sidePanel(panel), PluginPanel.PANEL_WIDTH, 900);
		StatsPanel.DayBars bars = UiTestSupport.find(panel, StatsPanel.DayBars.class);
		assertNotNull(bars);
		int last = bars.getWidth() - 6;
		String tip = bars.getToolTipText(new MouseEvent(bars, MouseEvent.MOUSE_MOVED, 0, 0, last, 20, 0, false));
		assertTrue(tip, tip.startsWith("Tue 13 Oct: "));
		assertNull(bars.getToolTipText(new MouseEvent(bars, MouseEvent.MOUSE_MOVED, 0, 0, 1, 20, 0, false)));
	}

	@Test
	public void emptyState() throws Exception
	{
		StatsPanel panel = new StatsPanel(null);
		panel.show(Ledger.stats(new LedgerData(), ZONE, NOW), Collections.emptyMap(), NOW);
		JPanel host = UiTestSupport.sidePanel(panel);
		UiTestSupport.save(UiTestSupport.paint(host, PluginPanel.PANEL_WIDTH, 200), "stats-empty.png");
		UiTestSupport.assertNoOverflow(host, PluginPanel.PANEL_WIDTH);
		List<String> texts = UiTestSupport.texts(panel);
		assertEquals(1, texts.size());
		assertTrue(texts.get(0).contains(StatsPanel.EMPTY));

		panel.show(null, null, NOW);
		assertTrue(UiTestSupport.texts(panel).get(0).contains(StatsPanel.EMPTY));
	}

	@Test
	public void formats()
	{
		assertEquals("+12.3K", StatsPanel.profit(12_345));
		assertEquals("-4.1M", StatsPanel.profit(-4_100_000));
		assertEquals("+850", StatsPanel.profit(850));
		assertEquals("+250K", StatsPanel.profit(250_400));
		assertEquals("+1M", StatsPanel.profit(999_600));
		assertEquals("+1.2B", StatsPanel.profit(1.2e9));
		assertEquals("0", StatsPanel.profit(0.4));
		assertEquals("—", StatsPanel.profit(Double.NaN));

		assertEquals("12,345", StatsPanel.price(12_345));
		assertEquals("123.5K", StatsPanel.price(123_456));
		assertEquals("1.235M", StatsPanel.price(1_234_567));
		assertEquals("28M", StatsPanel.price(28_000_000));

		assertEquals("just now", StatsPanel.ago(30_000));
		assertEquals("5m ago", StatsPanel.ago(5 * 60_000));
		assertEquals("3h ago", StatsPanel.ago(3 * HOUR + 59 * 60_000));
		assertEquals("2d ago", StatsPanel.ago(50 * HOUR));
		assertEquals("just now", StatsPanel.ago(-HOUR));
	}
}
