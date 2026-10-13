package com.flipplanner.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.QuantityFormatter;

/** The Flip Planner side panel. Rebuilt from a {@link PanelModel} on the Swing thread. */
public class FlipperPanel extends PluginPanel
{
	private static final Color GOOD = new Color(0x4C, 0xC0, 0x5A);
	private static final Color WARN = new Color(0xFA, 0xB2, 0x19);
	private static final Color BAD = new Color(0xE0, 0x5A, 0x4C);
	private static final Color GOLD = new Color(0xE0, 0xB4, 0x4C);

	private final ItemManager itemManager;
	private final JPanel body = new JPanel();
	private final JLabel status = new JLabel();
	private final JButton refresh = new JButton("Refresh");

	public FlipperPanel(ItemManager itemManager, Runnable onRefresh)
	{
		this.itemManager = itemManager;
		setLayout(new BorderLayout());
		setBorder(new EmptyBorder(8, 8, 8, 8));

		JPanel head = new JPanel(new BorderLayout());
		JLabel title = new JLabel("Flip Planner");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(GOLD);
		head.add(title, BorderLayout.WEST);
		refresh.setFocusable(false);
		refresh.addActionListener(e -> onRefresh.run());
		head.add(refresh, BorderLayout.EAST);
		status.setFont(FontManager.getRunescapeSmallFont());
		status.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		status.setBorder(new EmptyBorder(4, 0, 4, 0));
		head.add(status, BorderLayout.SOUTH);
		add(head, BorderLayout.NORTH);

		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		add(body, BorderLayout.CENTER);
		show(new PanelModel());
	}

	private static String gp(double n)
	{
		if (!Double.isFinite(n))
		{
			return "-";
		}
		String s = QuantityFormatter.quantityToStackSize(Math.round(Math.abs(n)));
		return (n < 0 ? "-" : "") + s;
	}

	private static String signed(double n)
	{
		return (n > 0 ? "+" : "") + gp(n);
	}

	private static String hours(double h)
	{
		if (!Double.isFinite(h))
		{
			return "-";
		}
		if (h < 1)
		{
			return Math.max(1, Math.round(h * 60)) + "m";
		}
		return h < 10 ? String.format(Locale.US, "%.1fh", h) : Math.round(h) + "h";
	}

	private JLabel section(String text)
	{
		JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeBoldFont());
		l.setForeground(Color.WHITE);
		l.setBorder(new EmptyBorder(10, 0, 4, 0));
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	private JLabel note(String html, Color color)
	{
		JLabel l = new JLabel("<html>" + html + "</html>");
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(color);
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		l.setBorder(new EmptyBorder(2, 0, 2, 0));
		return l;
	}

	private JPanel card(int itemId, String line1, String line2, Color line2Color)
	{
		JPanel p = new JPanel(new BorderLayout(6, 0));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(36, 32));
		icon.setHorizontalAlignment(SwingConstants.CENTER);
		if (itemId > 0)
		{
			itemManager.getImage(itemId).addTo(icon);
		}
		p.add(icon, BorderLayout.WEST);
		JPanel text = new JPanel();
		text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
		text.setOpaque(false);
		JLabel a = new JLabel("<html>" + line1 + "</html>");
		a.setFont(FontManager.getRunescapeSmallFont());
		a.setForeground(Color.WHITE);
		JLabel b = new JLabel("<html>" + line2 + "</html>");
		b.setFont(FontManager.getRunescapeSmallFont());
		b.setForeground(line2Color);
		text.add(a);
		text.add(b);
		p.add(text, BorderLayout.CENTER);
		JPanel wrap = new JPanel(new BorderLayout());
		wrap.setAlignmentX(Component.LEFT_ALIGNMENT);
		wrap.setBorder(new EmptyBorder(0, 0, 4, 0));
		wrap.add(p);
		return wrap;
	}

	private static String esc(String s)
	{
		return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static Color tone(String t)
	{
		if ("good".equals(t))
		{
			return GOOD;
		}
		if ("warn".equals(t))
		{
			return WARN;
		}
		if ("bad".equals(t))
		{
			return BAD;
		}
		return ColorScheme.LIGHT_GRAY_COLOR;
	}

	/** Shows a model. Call on the Swing thread. */
	public void show(PanelModel m)
	{
		refresh.setEnabled(!m.loading);
		status.setText("<html>" + esc(m.loading ? (m.status.isEmpty() ? "Working…" : m.status) : m.status) + "</html>");
		body.removeAll();

		if (m.error != null)
		{
			body.add(note(esc(m.error), BAD));
		}

		body.add(section("Buy"));
		if (m.buys.isEmpty())
		{
			body.add(note(esc(m.noBuys != null ? m.noBuys : "Press Refresh to get suggestions."), ColorScheme.LIGHT_GRAY_COLOR));
		}
		else
		{
			body.add(note("Expected profit " + signed(m.expectedProfit) + " from " + gp(m.freeGp) + " free gp.", ColorScheme.LIGHT_GRAY_COLOR));
			for (PanelModel.Buy b : m.buys)
			{
				String l1 = "<b>" + esc(b.name) + "</b><br>Buy " + QuantityFormatter.formatNumber(b.qty) + " @ " + QuantityFormatter.formatNumber(b.price);
				String l2 = "Sell ~" + QuantityFormatter.formatNumber(b.sellTarget) + " · " + signed(b.expected) + " in ~" + hours(b.hours)
					+ (b.warning != null ? "<br><font color='#FAB219'>" + esc(b.warning) + "</font>" : "");
				body.add(card(b.itemId, l1, l2, GOOD));
			}
		}

		if (!m.holds.isEmpty())
		{
			body.add(section("Your items"));
			for (PanelModel.Hold h : m.holds)
			{
				body.add(card(h.itemId, "<b>" + esc(h.name) + "</b> × " + QuantityFormatter.formatNumber(h.qty), esc(h.advice), tone(h.tone)));
			}
		}

		if (!m.tidy.isEmpty())
		{
			body.add(section("Tidy up"));
			for (String t : m.tidy)
			{
				body.add(note(esc(t), WARN));
			}
		}

		body.add(section("Profit"));
		body.add(note("Today " + signed(m.profitToday) + " · all time " + signed(m.profitTotal)
			+ " · " + m.flips + " flips" + (Double.isFinite(m.winRate) ? String.format(Locale.US, " · %.0f%% won", m.winRate * 100) : ""), Color.WHITE));
		if (!m.tradeFile.isEmpty())
		{
			body.add(note("Saved to " + esc(m.tradeFile), ColorScheme.LIGHT_GRAY_COLOR));
		}
		body.add(Box.createVerticalGlue());
		body.revalidate();
		body.repaint();
	}
}
