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
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;
import net.runelite.client.util.QuantityFormatter;

/** The Flip Planner side panel. Rebuilt from a {@link PanelModel} on the Swing thread. */
public class FlipperPanel extends PluginPanel
{
	private static final Color GOOD = new Color(0x4C, 0xC0, 0x5A);
	private static final Color WARN = new Color(0xFA, 0xB2, 0x19);
	private static final Color BAD = new Color(0xE0, 0x5A, 0x4C);
	private static final Color GOLD = new Color(0xE0, 0xB4, 0x4C);

	/** What the panel's buttons and menus do (implemented by the plugin). */
	public interface Actions
	{
		void refresh();

		void export();

		void skip();

		void togglePause();

		void block(int itemId, String name);

		void graph(int itemId, String name, Double buy, Double sell);
	}

	private static final Color BLUE = new Color(0x4C, 0x9A, 0xFF);
	// Text widths that fit RuneLite's side panel (PluginPanel.PANEL_WIDTH is 225 px).
	private static final int TEXT_W = 190;
	private static final int CARD_W = 140;
	private static final int NEXT_W = 185;

	private final ItemManager itemManager;
	private final Actions actions;
	private final JPanel body = new JPanel();
	private final JLabel status = new JLabel();
	private final JButton refresh = new JButton("Refresh");
	private final JButton export = new JButton("Export trades…");
	private final JPanel next = new JPanel(new BorderLayout(0, 4));
	private final JLabel nextTitle = new JLabel();
	private final JLabel nextDetail = new JLabel();
	private final JButton skip = new JButton("Skip");
	private final JButton pause = new JButton("Pause");
	private final StatsPanel statsPanel;
	private com.flipplanner.engine.Ledger.Stats shownStats;

	public FlipperPanel(ItemManager itemManager, Actions actions)
	{
		this.itemManager = itemManager;
		this.actions = actions;
		this.statsPanel = new StatsPanel(itemManager);
		setLayout(new BorderLayout());
		setBorder(new EmptyBorder(8, 8, 8, 8));

		JPanel head = new JPanel(new BorderLayout());
		JLabel title = new JLabel("Flip Planner");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(GOLD);
		head.add(title, BorderLayout.WEST);
		refresh.setFocusable(false);
		refresh.addActionListener(e -> actions.refresh());
		head.add(refresh, BorderLayout.EAST);
		status.setFont(FontManager.getRunescapeSmallFont());
		status.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		status.setBorder(new EmptyBorder(4, 0, 4, 0));
		head.add(status, BorderLayout.SOUTH);

		// The one next action, like a co-pilot: what to do right now.
		next.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		next.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 3, 0, 0, BLUE), new EmptyBorder(6, 8, 6, 6)));
		nextTitle.setFont(FontManager.getRunescapeBoldFont());
		nextTitle.setForeground(Color.WHITE);
		nextDetail.setFont(FontManager.getRunescapeSmallFont());
		nextDetail.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		JPanel nextText = new JPanel(new BorderLayout());
		nextText.setOpaque(false);
		nextText.add(nextTitle, BorderLayout.NORTH);
		nextText.add(nextDetail, BorderLayout.CENTER);
		next.add(nextText, BorderLayout.CENTER);
		JPanel nextButtons = new JPanel(new java.awt.GridLayout(1, 2, 4, 0));
		nextButtons.setOpaque(false);
		skip.setFocusable(false);
		skip.setToolTipText("Suggest a different item instead");
		skip.addActionListener(e -> actions.skip());
		pause.setFocusable(false);
		pause.setToolTipText("Stop the suggestions and GE highlights for now");
		pause.addActionListener(e -> actions.togglePause());
		nextButtons.add(skip);
		nextButtons.add(pause);
		next.add(nextButtons, BorderLayout.SOUTH);
		JPanel top = new JPanel(new BorderLayout(0, 6));
		top.add(head, BorderLayout.NORTH);
		top.add(next, BorderLayout.CENTER);
		top.setBorder(new EmptyBorder(0, 0, 4, 0));
		add(top, BorderLayout.NORTH);
		showNext("Waiting for prices…", "The first plan takes about 10 seconds after you log in.", false, false);

		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		// Plan | Stats tabs under the next-action card.
		JPanel display = new JPanel(new BorderLayout());
		MaterialTabGroup tabs = new MaterialTabGroup(display);
		MaterialTab planTab = new MaterialTab("Plan", tabs, body);
		MaterialTab statsTab = new MaterialTab("Stats", tabs, statsPanel);
		tabs.addTab(planTab);
		tabs.addTab(statsTab);
		tabs.select(planTab);
		JPanel center = new JPanel(new BorderLayout(0, 4));
		center.add(tabs, BorderLayout.NORTH);
		center.add(display, BorderLayout.CENTER);
		add(center, BorderLayout.CENTER);
		export.setFocusable(false);
		export.setToolTipText("Save a copy of your trade history anywhere, e.g. on another drive");
		export.addActionListener(e -> actions.export());
		add(export, BorderLayout.SOUTH);
		show(new PanelModel());
	}

	/** Updates the next-action card. Call on the Swing thread. */
	public void showNext(String title, String detail, boolean canSkip, boolean paused)
	{
		nextTitle.setText("<html><div style='width:" + NEXT_W + "px'>" + esc(title) + "</div></html>");
		nextDetail.setText("<html><div style='width:" + NEXT_W + "px'>" + esc(detail) + "</div></html>");
		skip.setVisible(canSkip && !paused);
		pause.setText(paused ? "Resume" : "Pause");
		next.revalidate();
		next.repaint();
	}

	private void popup(JPanel card, int itemId, String name, Double buy, Double sell, boolean blockable)
	{
		JPopupMenu menu = new JPopupMenu();
		JMenuItem graph = new JMenuItem("Price graph");
		graph.addActionListener(e -> actions.graph(itemId, name, buy, sell));
		menu.add(graph);
		if (blockable)
		{
			JMenuItem never = new JMenuItem("Never suggest " + name);
			never.addActionListener(e -> actions.block(itemId, name));
			menu.add(never);
		}
		card.setComponentPopupMenu(menu);
		card.setToolTipText("Right-click for the price graph" + (blockable ? " or to never suggest this item" : ""));
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
		JLabel l = new JLabel("<html><div style='width:" + TEXT_W + "px'>" + html + "</div></html>");
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(color);
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		l.setBorder(new EmptyBorder(2, 0, 2, 0));
		l.setMaximumSize(new Dimension(Integer.MAX_VALUE, l.getPreferredSize().height));
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
		if (itemId > 0 && itemManager != null)
		{
			itemManager.getImage(itemId).addTo(icon);
		}
		p.add(icon, BorderLayout.WEST);
		JPanel text = new JPanel();
		text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
		text.setOpaque(false);
		JLabel a = new JLabel("<html><div style='width:" + CARD_W + "px'>" + line1 + "</div></html>");
		a.setFont(FontManager.getRunescapeSmallFont());
		a.setForeground(Color.WHITE);
		JLabel b = new JLabel("<html><div style='width:" + CARD_W + "px'>" + line2 + "</div></html>");
		b.setFont(FontManager.getRunescapeSmallFont());
		b.setForeground(line2Color);
		text.add(a);
		text.add(b);
		p.add(text, BorderLayout.CENTER);
		JPanel wrap = new JPanel(new BorderLayout());
		wrap.setAlignmentX(Component.LEFT_ALIGNMENT);
		wrap.setBorder(new EmptyBorder(0, 0, 4, 0));
		wrap.add(p);
		wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, wrap.getPreferredSize().height));
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
		if (m.stats != null && m.stats != shownStats)
		{
			// Status-only updates (model progress) keep the same stats: don't rebuild the Stats tab for those.
			shownStats = m.stats;
			statsPanel.show(m.stats, m.names, System.currentTimeMillis());
		}

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
				JPanel c = card(b.itemId, l1, l2, GOOD);
				popup((JPanel) c.getComponent(0), b.itemId, b.name, (double) b.price, (double) b.sellTarget, true);
				body.add(c);
			}
		}

		if (!m.holds.isEmpty())
		{
			body.add(section("Your items"));
			for (PanelModel.Hold h : m.holds)
			{
				JPanel c = card(h.itemId, "<b>" + esc(h.name) + "</b> × " + QuantityFormatter.formatNumber(h.qty), esc(h.advice), tone(h.tone));
				popup((JPanel) c.getComponent(0), h.itemId, h.name, null, null, false);
				body.add(c);
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
		if (m.stackNote != null)
		{
			body.add(note(esc(m.stackNote), ColorScheme.LIGHT_GRAY_COLOR));
		}
		if (m.sessionHours > 0.05)
		{
			body.add(note(String.format(Locale.US, "This session %s in %s (%s/h)", signed(m.sessionProfit), hours(m.sessionHours),
				signed(m.sessionProfit / Math.max(m.sessionHours, 0.25))), Color.WHITE));
		}
		body.add(note("Today " + signed(m.profitToday) + " · all time " + signed(m.profitTotal)
			+ " · " + m.flips + " flips" + (Double.isFinite(m.winRate) ? String.format(Locale.US, " · %.0f%% won", m.winRate * 100) : ""), Color.WHITE));
		if (!m.tradeFile.isEmpty())
		{
			JLabel saved = note("Trades are saved on your PC in .runelite / plugin-data / flip-planner. Export trades… makes a copy anywhere.", ColorScheme.LIGHT_GRAY_COLOR);
			saved.setToolTipText(m.tradeFile);
			body.add(saved);
		}
		body.add(Box.createVerticalGlue());
		body.revalidate();
		body.repaint();
	}
}
