package com.flipplanner.ui;

import com.flipplanner.engine.Fmt;
import com.flipplanner.engine.Ledger;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.QuantityFormatter;

/**
 * Profit stats: a summary, daily profit bars, best and worst items and the latest flips.
 * Sized for the side panel; long text is cut short instead of widening the panel.
 */
public class StatsPanel extends JPanel
{
	private static final Color GOOD = new Color(0x4C, 0xC0, 0x5A);
	private static final Color BAD = new Color(0xE0, 0x5A, 0x4C);
	private static final int DAYS = 14;
	private static final int RECENT = 30;
	private static final int TOP_ITEMS = 3;
	private static final DateTimeFormatter TIP_DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);
	private static final DateTimeFormatter AXIS_DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
	private static final DateTimeFormatter SOLD_AT = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH);
	static final String EMPTY = "No finished flips yet. Trades show here once something you bought is sold.";

	private final ItemManager itemManager;
	private final JPanel content = new JPanel();

	public StatsPanel(ItemManager itemManager)
	{
		this.itemManager = itemManager;
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setOpaque(false);
		// NORTH keeps every row at its preferred height however tall the panel is
		add(content, BorderLayout.NORTH);
		show(null, Collections.emptyMap(), System.currentTimeMillis());
	}

	/** Rebuilds the panel. Call on the Swing thread; {@code names} may miss ids. */
	public void show(Ledger.Stats stats, Map<Integer, String> names, long nowMs)
	{
		content.removeAll();
		if (stats == null || stats.closed == null || stats.closed.isEmpty())
		{
			content.add(wrapped(EMPTY, ColorScheme.LIGHT_GRAY_COLOR));
		}
		else
		{
			Map<Integer, String> n = names == null ? Collections.<Integer, String>emptyMap() : names;
			LocalDate today = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDate();
			content.add(section("Profit", true));
			content.add(summary(stats, today));
			content.add(section("Daily profit", false));
			content.add(daily(stats, today));
			if (!stats.byItem.isEmpty())
			{
				content.add(section("Best and worst items", false));
				content.add(items(stats.byItem, n));
			}
			content.add(section("Recent flips", false));
			List<Ledger.ClosedTrip> recent = new ArrayList<>(stats.closed);
			recent.sort((a, b) -> Long.compare(b.sellTs, a.sellTs));
			for (Ledger.ClosedTrip c : recent.subList(0, Math.min(RECENT, recent.size())))
			{
				content.add(flip(c, name(n, c.itemId), nowMs));
			}
		}
		content.revalidate();
		content.repaint();
	}

	private JPanel summary(Ledger.Stats s, LocalDate today)
	{
		String from = today.minusDays(6).toString();
		String to = today.toString();
		double week = 0;
		for (Ledger.DayProfit d : s.byDay)
		{
			if (d.day.compareTo(from) >= 0 && d.day.compareTo(to) <= 0)
			{
				week += d.profit;
			}
		}
		JPanel grid = new JPanel(new GridLayout(0, 2, 4, 4));
		grid.setOpaque(false);
		grid.setAlignmentX(Component.LEFT_ALIGNMENT);
		grid.add(tile("Today", profit(s.today), tone(s.today)));
		grid.add(tile("Last 7 days", profit(week), tone(week)));
		grid.add(tile("All time", profit(s.profit), tone(s.profit)));
		grid.add(tile("Flips", QuantityFormatter.formatNumber(s.flips), Color.WHITE));
		grid.add(tile("Win rate", Fmt.pct(s.winRate, 0), Color.WHITE));
		grid.add(tile("ROI", Fmt.pct(s.roi, 1), tone(s.roi, 0.001)));
		grid.add(tile("Tax paid", Double.isFinite(s.tax) ? compact(Math.abs(s.tax)) : Fmt.NONE, Color.WHITE));
		grid.add(tile("Avg hold", Fmt.duration(s.avgHoldHours), Color.WHITE));
		return grid;
	}

	private JPanel tile(String label, String value, Color color)
	{
		JPanel p = new JPanel(new BorderLayout(0, 2));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setBorder(new EmptyBorder(5, 6, 5, 6));
		p.add(small(label, ColorScheme.LIGHT_GRAY_COLOR), BorderLayout.NORTH);
		JLabel v = new JLabel(value);
		v.setFont(FontManager.getRunescapeBoldFont());
		v.setForeground(color);
		p.add(v, BorderLayout.CENTER);
		return p;
	}

	private JPanel daily(Ledger.Stats s, LocalDate today)
	{
		String[] days = new String[DAYS];
		double[] profit = new double[DAYS];
		for (int i = 0; i < DAYS; i++)
		{
			LocalDate d = today.minusDays(DAYS - 1 - i);
			days[i] = TIP_DAY.format(d);
			profit[i] = Double.NaN;
			String key = d.toString();
			for (Ledger.DayProfit dp : s.byDay)
			{
				if (dp.day.equals(key))
				{
					profit[i] = dp.profit;
				}
			}
		}
		JPanel p = new JPanel(new BorderLayout(0, 2));
		p.setOpaque(false);
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		p.add(new DayBars(days, profit), BorderLayout.CENTER);
		JPanel axis = new JPanel(new BorderLayout());
		axis.setOpaque(false);
		axis.add(small(AXIS_DAY.format(today.minusDays(DAYS - 1)), ColorScheme.LIGHT_GRAY_COLOR), BorderLayout.WEST);
		axis.add(small("Today", ColorScheme.LIGHT_GRAY_COLOR), BorderLayout.EAST);
		p.add(axis, BorderLayout.SOUTH);
		return p;
	}

	private JPanel items(List<Ledger.ItemStats> byItem, Map<Integer, String> names)
	{
		// Best first; the worst list takes only items not already shown as best.
		int best = Math.min(TOP_ITEMS, byItem.size());
		int worst = Math.min(TOP_ITEMS, byItem.size() - best);
		JPanel box = new JPanel();
		box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
		box.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		box.setBorder(new EmptyBorder(4, 6, 5, 6));
		box.setAlignmentX(Component.LEFT_ALIGNMENT);
		box.add(subhead("Best", false));
		for (int i = 0; i < best; i++)
		{
			box.add(itemRow(byItem.get(i), names));
		}
		if (worst > 0)
		{
			box.add(subhead("Worst", true));
			for (int i = byItem.size() - 1; i >= byItem.size() - worst; i--)
			{
				box.add(itemRow(byItem.get(i), names));
			}
		}
		return box;
	}

	private JPanel itemRow(Ledger.ItemStats it, Map<Integer, String> names)
	{
		JPanel row = line(squeezed(small(name(names, it.itemId), Color.WHITE)), small(profit(it.profit), tone(it.profit)));
		row.setToolTipText(it.flips + (it.flips == 1 ? " flip, " : " flips, ") + it.wins + " won, ROI "
			+ Fmt.pct(it.cost != 0 ? it.profit / it.cost : Double.NaN, 1));
		return row;
	}

	private JPanel flip(Ledger.ClosedTrip c, String name, long nowMs)
	{
		JPanel card = new JPanel(new BorderLayout(6, 0));
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(new EmptyBorder(4, 6, 4, 6));
		if (itemManager != null && c.itemId > 0)
		{
			JLabel icon = new JLabel();
			icon.setPreferredSize(new Dimension(36, 32));
			icon.setHorizontalAlignment(SwingConstants.CENTER);
			itemManager.getImage(c.itemId).addTo(icon);
			card.add(icon, BorderLayout.WEST);
		}
		JPanel text = new JPanel(new GridLayout(2, 1, 0, 1));
		text.setOpaque(false);
		text.add(line(squeezed(small(name, Color.WHITE)), small(profit(c.profit), tone(c.profit))));
		String trade = price(c.qty) + " × " + price(c.buyPrice) + " → " + price(c.sellPrice);
		text.add(line(squeezed(small(trade, ColorScheme.LIGHT_GRAY_COLOR)), small(ago(nowMs - c.sellTs), ColorScheme.LIGHT_GRAY_COLOR)));
		card.add(text, BorderLayout.CENTER);
		card.setToolTipText("<html>" + QuantityFormatter.formatNumber(c.qty) + " × " + QuantityFormatter.formatNumber(Math.round(c.buyPrice))
			+ " → " + QuantityFormatter.formatNumber(c.sellPrice)
			+ "<br>" + signedFull(c.profit) + " (" + Fmt.pct(c.roi, 1) + ") after " + QuantityFormatter.formatNumber(Math.round(c.tax)) + " tax"
			+ "<br>Held " + Fmt.duration(c.holdHours) + ", sold " + SOLD_AT.format(Instant.ofEpochMilli(c.sellTs).atZone(ZoneId.systemDefault()))
			+ "</html>");
		JPanel wrap = new JPanel(new BorderLayout());
		wrap.setOpaque(false);
		wrap.setBorder(new EmptyBorder(0, 0, 3, 0));
		wrap.setAlignmentX(Component.LEFT_ALIGNMENT);
		wrap.add(card);
		return wrap;
	}

	/** One line: {@code left} takes the room left over by {@code right}. */
	private static JPanel line(JLabel left, JLabel right)
	{
		JPanel p = new JPanel(new BorderLayout(6, 0));
		p.setOpaque(false);
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		p.add(left, BorderLayout.CENTER);
		p.add(right, BorderLayout.EAST);
		return p;
	}

	private static JLabel section(String text, boolean first)
	{
		JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeBoldFont());
		l.setForeground(Color.WHITE);
		l.setBorder(new EmptyBorder(first ? 0 : 10, 0, 4, 0));
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	private static JLabel subhead(String text, boolean gap)
	{
		JLabel l = small(text, ColorScheme.LIGHT_GRAY_COLOR);
		l.setBorder(new EmptyBorder(gap ? 5 : 0, 0, 1, 0));
		return l;
	}

	private static JLabel small(String text, Color color)
	{
		JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(color);
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	/** A label that asks for no width of its own, so long text ends in "..." instead of widening the panel. */
	private static JLabel squeezed(JLabel l)
	{
		Dimension d = l.getPreferredSize();
		l.setPreferredSize(new Dimension(0, d.height));
		l.setMinimumSize(new Dimension(0, d.height));
		return l;
	}

	private static JLabel wrapped(String text, Color color)
	{
		JLabel l = small("<html><body style='width: 170px'>" + text + "</body></html>", color);
		l.setBorder(new EmptyBorder(4, 0, 4, 0));
		return l;
	}

	private static String name(Map<Integer, String> names, int itemId)
	{
		String n = names.get(itemId);
		return n == null || n.isEmpty() ? "Item " + itemId : n;
	}

	/** Green above zero, red below; grey when it shows as zero. */
	private static Color tone(double gp)
	{
		return tone(gp, 1);
	}

	/** As {@link #tone(double)}, where {@code unit} is the smallest step shown. */
	private static Color tone(double x, double unit)
	{
		if (!Double.isFinite(x) || Math.abs(x) < unit / 2)
		{
			return ColorScheme.LIGHT_GRAY_COLOR;
		}
		return x > 0 ? GOOD : BAD;
	}

	/** Signed compact gp: +12.3K, -4.1M, 0; an em dash when unknown. */
	static String profit(double n)
	{
		if (!Double.isFinite(n))
		{
			return Fmt.NONE;
		}
		long r = Math.round(n);
		if (r == 0)
		{
			return "0";
		}
		return (r > 0 ? "+" : "-") + compact(Math.abs((double) r));
	}

	/** 950 · 12.3K · 250K · 4.1M · 1.2B for a non-negative amount. */
	static String compact(double a)
	{
		if (a < 999.5)
		{
			return Long.toString(Math.round(a));
		}
		double unit = a < 999_500 ? 1e3 : a < 999_500_000 ? 1e6 : 1e9;
		String suffix = unit == 1e3 ? "K" : unit == 1e6 ? "M" : "B";
		double v = a / unit;
		String s = v < 99.95 ? String.format(Locale.US, "%.1f", v) : String.format(Locale.US, "%.0f", v);
		if (s.endsWith(".0"))
		{
			s = s.substring(0, s.length() - 2);
		}
		return s + suffix;
	}

	/** Exact below 100K, else four significant digits: 12,345 · 123.5K · 1.235M. */
	static String price(double p)
	{
		if (!Double.isFinite(p))
		{
			return Fmt.NONE;
		}
		long r = Math.round(p);
		if (Math.abs(r) < 100_000)
		{
			return QuantityFormatter.formatNumber(r);
		}
		double a = Math.abs((double) r);
		double unit = a < 999_950 ? 1e3 : a < 999_950_000 ? 1e6 : 1e9;
		String suffix = unit == 1e3 ? "K" : unit == 1e6 ? "M" : "B";
		double v = a / unit;
		int decimals = v < 10 ? 3 : v < 100 ? 2 : 1;
		String s = String.format(Locale.US, "%." + decimals + "f", v);
		if (s.indexOf('.') >= 0)
		{
			s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
		}
		return (r < 0 ? "-" : "") + s + suffix;
	}

	private static String signedFull(double n)
	{
		long r = Math.round(n);
		return (r > 0 ? "+" : "") + QuantityFormatter.formatNumber(r);
	}

	/** How long ago: just now · 5m ago · 3h ago · 2d ago. */
	static String ago(long ms)
	{
		long m = Math.max(0, ms) / 60_000;
		if (m < 1)
		{
			return "just now";
		}
		if (m < 60)
		{
			return m + "m ago";
		}
		long h = m / 60;
		return h < 24 ? h + "h ago" : h / 24 + "d ago";
	}

	/** Profit per day, oldest first: green bars up and red bars down from a zero line. */
	static final class DayBars extends JComponent
	{
		private static final int PAD = 4;
		private final String[] days;
		/** NaN for days without a finished flip. */
		private final double[] profit;

		DayBars(String[] days, double[] profit)
		{
			this.days = days;
			this.profit = profit;
			setPreferredSize(new Dimension(days.length * 4, 60));
			setToolTipText(""); // registers for tooltips; the text comes from getToolTipText(MouseEvent)
		}

		int slotAt(int x)
		{
			int w = getWidth() - 2 * PAD;
			if (w <= 0 || x < PAD || x >= PAD + w)
			{
				return -1;
			}
			return (int) ((long) (x - PAD) * profit.length / w);
		}

		@Override
		public String getToolTipText(MouseEvent e)
		{
			int i = slotAt(e.getX());
			if (i < 0)
			{
				return null;
			}
			return days[i] + ": " + (Double.isNaN(profit[i]) ? "no flips" : signedFull(profit[i]));
		}

		@Override
		protected void paintComponent(Graphics g0)
		{
			Graphics2D g = (Graphics2D) g0.create();
			try
			{
				int w = getWidth();
				int h = getHeight();
				g.setColor(ColorScheme.DARKER_GRAY_COLOR);
				g.fillRect(0, 0, w, h);
				double up = 0;
				double down = 0;
				for (double p : profit)
				{
					if (Double.isFinite(p))
					{
						up = Math.max(up, p);
						down = Math.max(down, -p);
					}
				}
				int top = PAD;
				int span = h - 2 * PAD - 1;
				double range = up + down;
				int zero = range > 0 ? top + (int) Math.round(span * up / range) : top + span / 2;
				g.setColor(ColorScheme.MEDIUM_GRAY_COLOR);
				g.drawLine(PAD, zero, w - PAD - 1, zero);
				if (range <= 0)
				{
					return;
				}
				double slot = (w - 2.0 * PAD) / profit.length;
				for (int i = 0; i < profit.length; i++)
				{
					double p = profit[i];
					if (!Double.isFinite(p) || p == 0)
					{
						continue;
					}
					int x0 = PAD + (int) Math.round(i * slot) + 1;
					int x1 = PAD + (int) Math.round((i + 1) * slot) - 1;
					int bh = Math.max(1, (int) Math.round(span * Math.abs(p) / range));
					if (p > 0)
					{
						g.setColor(GOOD);
						g.fillRect(x0, zero - bh, Math.max(1, x1 - x0), bh);
					}
					else
					{
						g.setColor(BAD);
						g.fillRect(x0, zero + 1, Math.max(1, x1 - x0), bh);
					}
				}
			}
			finally
			{
				g.dispose();
			}
		}
	}
}
