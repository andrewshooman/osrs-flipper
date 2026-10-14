package com.getycoon.ui;

import com.getycoon.engine.Fmt;
import com.getycoon.engine.HourPoint;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.QuantityFormatter;

/**
 * Seven days of hourly instant-buy and instant-sell prices, with the suggested buy and sell
 * prices as dashed lines. Hovering shows a crosshair with the hour and both prices.
 */
public class PriceGraphPanel extends JPanel
{
	/** Instant buy (avgHighPrice). */
	static final Color HIGH = new Color(0x39, 0x87, 0xE5);
	/** Instant sell (avgLowPrice). */
	static final Color LOW = new Color(0xD9, 0x59, 0x26);
	private static final Color GOLD = new Color(0xE0, 0xB4, 0x4C);
	private static final Color GRID = new Color(0x34, 0x34, 0x34);
	private static final Color TARGET = new Color(0xD0, 0xD0, 0xD0);
	private static final Color CROSS = new Color(0x8A, 0x8A, 0x8A);
	private static final Color TEXT = ColorScheme.LIGHT_GRAY_COLOR;
	private static final long WINDOW_SEC = 7 * 86400L;
	/** Points further apart than this are not joined: the hours between are missing. */
	private static final long MAX_JOIN_SEC = 2 * 3600L;
	private static final int PAD = 8;
	private static final Stroke LINE = new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	private static final Stroke DASH = new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{5f, 4f}, 0f);
	private static final Stroke THIN = new BasicStroke(1f);
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d", Locale.ENGLISH);
	private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH);

	private final ZoneId zone = ZoneId.systemDefault();
	private String title = "";
	/** Oldest first, only the shown window. */
	private List<HourPoint> points = Collections.emptyList();
	private double buy = Double.NaN;
	private double sell = Double.NaN;
	private long from;
	private long to;
	private Point hover;

	public PriceGraphPanel()
	{
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setPreferredSize(new Dimension(520, 280));
		MouseAdapter mouse = new MouseAdapter()
		{
			@Override
			public void mouseMoved(MouseEvent e)
			{
				hover(e.getPoint());
			}

			@Override
			public void mouseDragged(MouseEvent e)
			{
				hover(e.getPoint());
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				hover(null);
			}
		};
		addMouseListener(mouse);
		addMouseMotionListener(mouse);
	}

	/**
	 * Shows the 7 days up to {@code nowSec} of {@code points}. Null or non-finite prices
	 * draw no dashed line. Call on the Swing thread.
	 */
	public void setData(String title, List<HourPoint> points, Double buyPrice, Double sellPrice, long nowSec)
	{
		this.title = title == null ? "" : title;
		this.to = nowSec;
		this.from = nowSec - WINDOW_SEC;
		List<HourPoint> shown = new ArrayList<>();
		if (points != null)
		{
			for (HourPoint p : points)
			{
				if (p != null && p.timestamp >= from && p.timestamp <= to)
				{
					shown.add(p);
				}
			}
		}
		shown.sort((a, b) -> Long.compare(a.timestamp, b.timestamp));
		this.points = shown;
		this.buy = buyPrice == null ? Double.NaN : buyPrice;
		this.sell = sellPrice == null ? Double.NaN : sellPrice;
		repaint();
	}

	/** Moves the crosshair; null hides it. */
	void hover(Point p)
	{
		hover = p;
		repaint();
	}

	@Override
	protected void paintComponent(Graphics g0)
	{
		super.paintComponent(g0);
		Graphics2D g = (Graphics2D) g0.create();
		try
		{
			paintGraph(g);
		}
		finally
		{
			g.dispose();
		}
	}

	private void paintGraph(Graphics2D g)
	{
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		// The RuneScape fonts are pixel fonts: sharpest without text antialiasing.
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		Font small = FontManager.getRunescapeSmallFont();
		Font bold = FontManager.getRunescapeBoldFont();
		FontMetrics fm = g.getFontMetrics(small);
		FontMetrics bm = g.getFontMetrics(bold);
		int w = getWidth();
		int h = getHeight();

		// Header: title on the left, legend on the right, or under the title when both don't fit.
		int base = PAD + bm.getAscent();
		int legendW = legendWidth(fm);
		boolean wrap = bm.stringWidth(title) + 12 + legendW > w - 2 * PAD;
		g.setFont(bold);
		g.setColor(GOLD);
		g.drawString(fit(title, bm, w - 2 * PAD - (wrap ? 0 : legendW + 12)), PAD, base);
		g.setFont(small);
		int legendBase = wrap ? base + bm.getDescent() + fm.getAscent() + 3 : base;
		legend(g, fm, wrap ? PAD : w - PAD - legendW, legendBase);
		int top = (wrap ? legendBase + fm.getDescent() : base + bm.getDescent()) + 6;
		int bottom = h - PAD - fm.getHeight();

		double lo = Double.POSITIVE_INFINITY;
		double hi = Double.NEGATIVE_INFINITY;
		for (HourPoint p : points)
		{
			for (double v : new double[]{p.avgHighPrice, p.avgLowPrice})
			{
				if (Double.isFinite(v))
				{
					lo = Math.min(lo, v);
					hi = Math.max(hi, v);
				}
			}
		}
		if (lo > hi)
		{
			String msg = "No price history";
			g.setColor(TEXT);
			g.drawString(msg, (w - fm.stringWidth(msg)) / 2, (top + h) / 2);
			return;
		}
		if (bottom - top < 20)
		{
			return;
		}
		for (double v : new double[]{buy, sell})
		{
			if (Double.isFinite(v))
			{
				lo = Math.min(lo, v);
				hi = Math.max(hi, v);
			}
		}
		double spread = hi - lo;
		double margin = spread > 0 ? spread * 0.08 : Math.max(1, Math.abs(hi) * 0.02);
		double yLo = Math.max(0, lo - margin);
		double yHi = hi + margin;
		double step = niceStep((yHi - yLo) / Math.max(2, (bottom - top) / 40));

		// Y labels decide the left margin.
		List<Double> ticks = new ArrayList<>();
		double first = Math.ceil(yLo / step) * step;
		for (int i = 0; first + i * step <= yHi; i++)
		{
			ticks.add(first + i * step);
		}
		List<String> labels = new ArrayList<>();
		int labelW = 0;
		for (double v : ticks)
		{
			String s = axisGp(v, step, yHi);
			labels.add(s);
			labelW = Math.max(labelW, fm.stringWidth(s));
		}
		Rectangle plot = new Rectangle(PAD + labelW + 6, top, w - 2 * PAD - labelW - 6, bottom - top);
		if (plot.width < 40)
		{
			return;
		}
		Scale sc = new Scale(plot, from, to, yLo, yHi);

		g.setColor(ColorScheme.DARKER_GRAY_COLOR);
		g.fill(plot);
		g.setStroke(THIN);
		for (int i = 0; i < ticks.size(); i++)
		{
			int y = (int) Math.round(sc.y(ticks.get(i)));
			g.setColor(GRID);
			g.drawLine(plot.x, y, plot.x + plot.width - 1, y);
			g.setColor(TEXT);
			String s = labels.get(i);
			g.drawString(s, plot.x - 6 - fm.stringWidth(s), y + fm.getAscent() / 2 - 1);
		}
		days(g, fm, sc, plot);

		Shape clip = g.getClip();
		g.clipRect(plot.x, plot.y, plot.width, plot.height);
		series(g, sc, true, HIGH);
		series(g, sc, false, LOW);
		List<Rectangle> labelBoxes = targets(g, fm, sc, plot);
		g.setClip(clip);

		crosshair(g, fm, sc, plot, labelBoxes);
	}

	/** Midnight grid lines with the day centred under each: "Wed 7", or just "7" when narrow. */
	private void days(Graphics2D g, FontMetrics fm, Scale sc, Rectangle plot)
	{
		ZonedDateTime d = Instant.ofEpochSecond(from).atZone(zone).toLocalDate().atStartOfDay(zone);
		int y = plot.y + plot.height + fm.getAscent() + 3;
		boolean names = sc.x(from + 86400) - sc.x(from) >= fm.stringWidth("Wed 30") + 6;
		while (d.toEpochSecond() < to)
		{
			ZonedDateTime next = d.plusDays(1);
			long s = d.toEpochSecond();
			if (s > from)
			{
				int x = (int) Math.round(sc.x(s));
				g.setColor(GRID);
				g.drawLine(x, plot.y, x, plot.y + plot.height - 1);
			}
			double a = sc.x(Math.max(s, from));
			double b = sc.x(Math.min(next.toEpochSecond(), to));
			String label = names ? DAY.format(d) : Integer.toString(d.getDayOfMonth());
			int lw = fm.stringWidth(label);
			if (b - a >= lw + 4)
			{
				g.setColor(TEXT);
				g.drawString(label, (int) Math.round((a + b - lw) / 2), y);
			}
			d = next;
		}
	}

	private void series(Graphics2D g, Scale sc, boolean high, Color color)
	{
		Path2D path = new Path2D.Double();
		List<double[]> dots = new ArrayList<>();
		int run = 0;
		long prevTs = 0;
		double px = 0;
		double py = 0;
		for (HourPoint p : points)
		{
			double v = high ? p.avgHighPrice : p.avgLowPrice;
			if (!Double.isFinite(v))
			{
				if (run == 1)
				{
					dots.add(new double[]{px, py});
				}
				run = 0;
				continue;
			}
			double x = sc.x(p.timestamp);
			double y = sc.y(v);
			if (run > 0 && p.timestamp - prevTs <= MAX_JOIN_SEC)
			{
				path.lineTo(x, y);
				run++;
			}
			else
			{
				if (run == 1)
				{
					dots.add(new double[]{px, py});
				}
				path.moveTo(x, y);
				run = 1;
			}
			prevTs = p.timestamp;
			px = x;
			py = y;
		}
		if (run == 1)
		{
			dots.add(new double[]{px, py});
		}
		g.setColor(color);
		g.setStroke(LINE);
		g.draw(path);
		for (double[] d : dots)
		{
			g.fill(new Ellipse2D.Double(d[0] - 2, d[1] - 2, 4, 4));
		}
	}

	/**
	 * Dashed suggested prices, labelled at the right: the higher label above its line, the
	 * lower below. Returns where the labels went.
	 */
	private List<Rectangle> targets(Graphics2D g, FontMetrics fm, Scale sc, Rectangle plot)
	{
		List<Mark> marks = new ArrayList<>();
		if (Double.isFinite(sell))
		{
			marks.add(new Mark("Sell " + QuantityFormatter.formatNumber(Math.round(sell)), sc.y(sell)));
		}
		if (Double.isFinite(buy))
		{
			marks.add(new Mark("Buy " + QuantityFormatter.formatNumber(Math.round(buy)), sc.y(buy)));
		}
		marks.sort((a, b) -> Double.compare(a.y, b.y));
		g.setStroke(DASH);
		g.setColor(TARGET);
		for (Mark m : marks)
		{
			g.draw(new Line2D.Double(plot.x, m.y, plot.x + plot.width, m.y));
		}
		g.setStroke(THIN);
		int th = fm.getAscent() + 2;
		List<Rectangle> boxes = new ArrayList<>();
		Rectangle prev = null;
		for (Mark m : marks)
		{
			int y = (int) Math.round(m.y);
			int tw = fm.stringWidth(m.text) + 6;
			Rectangle r = new Rectangle(plot.x + plot.width - tw - 4, prev == null ? y - th - 2 : y + 2, tw, th);
			if (r.y < plot.y)
			{
				r.y = y + 2;
			}
			else if (r.y + r.height > plot.y + plot.height)
			{
				r.y = y - th - 2;
			}
			if (prev != null && r.intersects(prev))
			{
				r.x = prev.x - tw - 6;
			}
			g.setColor(ColorScheme.DARKER_GRAY_COLOR);
			g.fill(r);
			g.setColor(TARGET);
			g.drawString(m.text, r.x + 3, r.y + fm.getAscent());
			boxes.add(r);
			prev = r;
		}
		return boxes;
	}

	private void crosshair(Graphics2D g, FontMetrics fm, Scale sc, Rectangle plot, List<Rectangle> avoid)
	{
		if (hover == null || !plot.contains(hover) || points.isEmpty())
		{
			return;
		}
		double t = sc.t(hover.x);
		HourPoint near = null;
		for (HourPoint p : points)
		{
			if (near == null || Math.abs(p.timestamp - t) < Math.abs(near.timestamp - t))
			{
				near = p;
			}
		}
		int x = (int) Math.round(sc.x(near.timestamp));
		g.setStroke(THIN);
		g.setColor(CROSS);
		g.drawLine(x, plot.y, x, plot.y + plot.height - 1);
		g.drawLine(plot.x, hover.y, plot.x + plot.width - 1, hover.y);

		// The price under the pointer, on the left edge of the plot.
		String at = QuantityFormatter.formatNumber(Math.round(sc.v(hover.y)));
		int aw = fm.stringWidth(at) + 6;
		int ah = fm.getAscent() + 2;
		g.setColor(ColorScheme.MEDIUM_GRAY_COLOR);
		g.fillRect(plot.x, hover.y - ah / 2, aw, ah);
		g.setColor(Color.WHITE);
		g.drawString(at, plot.x + 3, hover.y - ah / 2 + fm.getAscent());

		marker(g, sc, x, near.avgHighPrice, HIGH);
		marker(g, sc, x, near.avgLowPrice, LOW);

		String[] rows = {
			HOUR.format(Instant.ofEpochSecond(near.timestamp).atZone(zone)),
			"Instant buy  " + full(near.avgHighPrice),
			"Instant sell  " + full(near.avgLowPrice),
		};
		int lh = fm.getHeight();
		int bw = 0;
		for (int i = 0; i < rows.length; i++)
		{
			bw = Math.max(bw, fm.stringWidth(rows[i]) + (i > 0 ? 12 : 0));
		}
		bw += 12;
		int bh = rows.length * lh + 8;
		Rectangle box = place(new Rectangle(0, 0, bw, bh), x, hover.y, plot, avoid);
		int bx = box.x;
		int by = box.y;
		g.setColor(new Color(0x10, 0x10, 0x10));
		g.fillRect(bx, by, bw, bh);
		g.setColor(ColorScheme.MEDIUM_GRAY_COLOR);
		g.drawRect(bx, by, bw - 1, bh - 1);
		for (int i = 0; i < rows.length; i++)
		{
			int ty = by + 4 + i * lh + fm.getAscent();
			int tx = bx + 6;
			if (i > 0)
			{
				g.setColor(i == 1 ? HIGH : LOW);
				g.fillRect(tx, ty - fm.getAscent() / 2 - 3, 8, 3);
				tx += 12;
			}
			g.setColor(i == 0 ? Color.WHITE : TEXT);
			g.drawString(rows[i], tx, ty);
		}
	}

	/**
	 * Puts {@code box} beside the crosshair at {@code x}, preferably right of it and just below
	 * the pointer at {@code y}: the nearest spot inside the plot that covers no price label.
	 */
	private static Rectangle place(Rectangle box, int x, int y, Rectangle plot, List<Rectangle> avoid)
	{
		int want = y + 14;
		List<Integer> ys = new ArrayList<>();
		ys.add(want);
		ys.add(y - 14 - box.height);
		for (Rectangle a : avoid)
		{
			ys.add(a.y - box.height - 2);
			ys.add(a.y + a.height + 2);
		}
		Rectangle best = null;
		int bestScore = Integer.MAX_VALUE;
		for (int bx : new int[]{x + 12, x - 12 - box.width})
		{
			for (int by : ys)
			{
				Rectangle r = new Rectangle(bx, by, box.width, box.height);
				boolean clear = plot.contains(r);
				for (Rectangle a : avoid)
				{
					clear &= !r.intersects(a);
				}
				int score = Math.abs(by - want) + (bx < x ? 40 : 0);
				if (clear && score < bestScore)
				{
					best = r;
					bestScore = score;
				}
			}
		}
		if (best != null)
		{
			return best;
		}
		// No clear spot: stay inside the plot as far as it goes.
		int bx = x + 12 + box.width <= plot.x + plot.width ? x + 12 : x - 12 - box.width;
		int by = Math.min(want, plot.y + plot.height - box.height);
		return new Rectangle(Math.max(plot.x, bx), Math.max(plot.y, by), box.width, box.height);
	}

	private static int legendWidth(FontMetrics fm)
	{
		return 16 + fm.stringWidth("Instant buy") + 12 + 16 + fm.stringWidth("Instant sell");
	}

	private static void legend(Graphics2D g, FontMetrics fm, int x, int base)
	{
		int y = base - fm.getAscent() / 2 - 1;
		x = legendEntry(g, fm, x, y, base, "Instant buy", HIGH);
		legendEntry(g, fm, x, y, base, "Instant sell", LOW);
	}

	private static int legendEntry(Graphics2D g, FontMetrics fm, int x, int y, int base, String text, Color color)
	{
		g.setColor(color);
		g.setStroke(LINE);
		g.drawLine(x, y, x + 11, y);
		g.setColor(TEXT);
		g.drawString(text, x + 16, base);
		return x + 16 + fm.stringWidth(text) + 12;
	}

	/** A dot on a series at the crosshair, ringed in the plot colour. */
	private static void marker(Graphics2D g, Scale sc, int x, double v, Color color)
	{
		if (!Double.isFinite(v))
		{
			return;
		}
		double y = sc.y(v);
		g.setColor(ColorScheme.DARKER_GRAY_COLOR);
		g.fill(new Ellipse2D.Double(x - 4.5, y - 4.5, 9, 9));
		g.setColor(color);
		g.fill(new Ellipse2D.Double(x - 3, y - 3, 6, 6));
	}

	/** {@code s} cut to fit {@code width} pixels, ending in "…" when cut. */
	private static String fit(String s, FontMetrics fm, int width)
	{
		if (fm.stringWidth(s) <= width)
		{
			return s;
		}
		for (int n = s.length() - 1; n > 0; n--)
		{
			String t = s.substring(0, n) + "…";
			if (fm.stringWidth(t) <= width)
			{
				return t;
			}
		}
		return "";
	}

	private static String full(double v)
	{
		return Double.isFinite(v) ? QuantityFormatter.formatNumber(Math.round(v)) : Fmt.NONE;
	}

	/** 1, 2, 2.5 or 5 times a power of ten, at least {@code raw}; never under 1 gp. */
	static double niceStep(double raw)
	{
		if (!(raw > 0) || !Double.isFinite(raw))
		{
			return 1;
		}
		double mag = Math.pow(10, Math.floor(Math.log10(raw)));
		double f = raw / mag;
		double nice = f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10;
		return Math.max(1, nice * mag);
	}

	/**
	 * Axis gp: one unit for the whole axis (K from 10K, M from 1M, B from 1B), with as many
	 * decimals as the step needs. 950 · 9,500 · 14.5K · 1.22M.
	 */
	static String axisGp(double v, double step, double max)
	{
		double unit = max >= 1e9 ? 1e9 : max >= 1e6 ? 1e6 : max >= 1e4 ? 1e3 : 1;
		if (unit == 1)
		{
			return QuantityFormatter.formatNumber(Math.round(v));
		}
		String suffix = unit == 1e3 ? "K" : unit == 1e6 ? "M" : "B";
		double s = step / unit;
		int decimals = 0;
		while (decimals < 6 && Math.abs(Math.round(s * Math.pow(10, decimals)) - s * Math.pow(10, decimals)) > 1e-6)
		{
			decimals++;
		}
		return String.format(Locale.US, "%." + decimals + "f", v / unit) + suffix;
	}

	/** A suggested price line and its label. */
	private static final class Mark
	{
		private final String text;
		private final double y;

		Mark(String text, double y)
		{
			this.text = text;
			this.y = y;
		}
	}

	/** Maps time (seconds) and price to pixels and back. */
	private static final class Scale
	{
		private final Rectangle r;
		private final long t0;
		private final long t1;
		private final double v0;
		private final double v1;

		Scale(Rectangle r, long t0, long t1, double v0, double v1)
		{
			this.r = r;
			this.t0 = t0;
			this.t1 = Math.max(t0 + 1, t1);
			this.v0 = v0;
			this.v1 = v1 > v0 ? v1 : v0 + 1;
		}

		double x(long t)
		{
			return r.x + (double) (t - t0) / (t1 - t0) * (r.width - 1);
		}

		double t(int x)
		{
			return t0 + (double) (x - r.x) / Math.max(1, r.width - 1) * (t1 - t0);
		}

		double y(double v)
		{
			return r.y + r.height - 1 - (v - v0) / (v1 - v0) * (r.height - 1);
		}

		double v(int y)
		{
			return v0 + (double) (r.y + r.height - 1 - y) / Math.max(1, r.height - 1) * (v1 - v0);
		}
	}
}
