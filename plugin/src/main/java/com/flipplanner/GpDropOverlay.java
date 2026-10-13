package com.flipplanner;

import com.flipplanner.engine.Fmt;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * A floating "+12.3K gp" at the right of the game view when a sell finishes, rising
 * and fading like an XP drop. Drawing only.
 */
public class GpDropOverlay extends Overlay
{
	static final long LIFE_MS = 2_500;
	private static final int RISE = 40;
	private static final int ROW = 18;
	private static final Color GOLD = new Color(0xFF, 0xD7, 0x00);
	private static final Color RED = new Color(0xE0, 0x5A, 0x4C);

	private static final class Drop
	{
		final String text;
		final Color color;
		final long at;

		Drop(String text, Color color, long at)
		{
			this.text = text;
			this.color = color;
			this.at = at;
		}
	}

	private final Client client;
	private final List<Drop> drops = new ArrayList<>();

	@Inject
	GpDropOverlay(Client client)
	{
		this.client = client;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	/** Shows a drop for a sell's profit after tax. Client thread. */
	void add(double profit)
	{
		synchronized (drops)
		{
			drops.add(new Drop(text(profit), profit >= 0 ? GOLD : RED, System.currentTimeMillis()));
		}
	}

	static String text(double profit)
	{
		return (profit >= 0 ? "+" : "-") + Fmt.gp(Math.abs(profit)) + " gp";
	}

	/** 255 for most of a drop's life, then fading to 0. */
	static int alpha(long age)
	{
		double p = (double) age / LIFE_MS;
		return p < 0.7 ? 255 : (int) Math.max(0, Math.min(255, Math.round((1 - p) / 0.3 * 255)));
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		long now = System.currentTimeMillis();
		List<Drop> live;
		synchronized (drops)
		{
			drops.removeIf(d -> now - d.at >= LIFE_MS);
			if (drops.isEmpty())
			{
				return null;
			}
			live = new ArrayList<>(drops);
		}
		g.setFont(FontManager.getRunescapeBoldFont());
		FontMetrics fm = g.getFontMetrics();
		int right = client.getViewportXOffset() + client.getViewportWidth() - 12;
		int base = client.getViewportYOffset() + 110;
		int row = 0;
		for (Iterator<Drop> it = live.iterator(); it.hasNext(); row++)
		{
			Drop d = it.next();
			long age = now - d.at;
			int y = base - (int) (RISE * age / LIFE_MS) + row * ROW;
			int x = right - fm.stringWidth(d.text);
			int a = alpha(age);
			g.setColor(new Color(0, 0, 0, a));
			g.drawString(d.text, x + 1, y + 1);
			g.setColor(new Color(d.color.getRed(), d.color.getGreen(), d.color.getBlue(), a));
			g.drawString(d.text, x, y);
		}
		return null;
	}
}
