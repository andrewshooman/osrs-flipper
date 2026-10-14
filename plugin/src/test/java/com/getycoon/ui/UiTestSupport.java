package com.getycoon.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import static org.junit.Assert.assertTrue;

/** Headless layout and painting for the UI tests, plus PNG snapshots for a human to look at. */
final class UiTestSupport
{
	/** Snapshots are written only when the FLIP_PLANNER_PNG_DIR environment variable names an existing directory. */
	private static final String PNG_DIR = System.getenv("FLIP_PLANNER_PNG_DIR");

	static
	{
		System.setProperty("java.awt.headless", "true");
	}

	private UiTestSupport()
	{
	}

	/** {@code c} as RuneLite's side panel hosts it: PANEL_WIDTH wide with an 8px border, like TycoonPanel. */
	static JPanel sidePanel(JComponent c)
	{
		JPanel host = new JPanel(new BorderLayout());
		host.setBackground(ColorScheme.DARK_GRAY_COLOR);
		host.setBorder(new EmptyBorder(8, 8, 8, 8));
		host.add(c, BorderLayout.CENTER);
		host.setSize(PluginPanel.PANEL_WIDTH, 10);
		return host;
	}

	/** Lays out the whole tree; Container.validate() does nothing without a native peer. */
	static void layout(Component c)
	{
		if (c instanceof Container)
		{
			Container k = (Container) c;
			k.doLayout();
			for (Component child : k.getComponents())
			{
				layout(child);
			}
		}
	}

	static BufferedImage paint(Component c, int w, int h)
	{
		c.setSize(w, h);
		layout(c);
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		try
		{
			c.printAll(g);
		}
		finally
		{
			g.dispose();
		}
		return img;
	}

	static void save(BufferedImage img, String name) throws IOException
	{
		if (PNG_DIR == null || PNG_DIR.isEmpty())
		{
			return;
		}
		File dir = new File(PNG_DIR);
		if (dir.isDirectory())
		{
			ImageIO.write(img, "png", new File(dir, name));
		}
	}

	/** Every visible component lies within {@code [0, width)} horizontally. */
	static void assertNoOverflow(Component root, int width)
	{
		assertNoOverflow(root, 0, width);
	}

	private static void assertNoOverflow(Component c, int x, int width)
	{
		if (!c.isVisible())
		{
			return;
		}
		assertTrue(c.getClass().getSimpleName() + " " + describe(c) + " spans " + x + ".." + (x + c.getWidth()) + " of " + width,
			x >= 0 && x + c.getWidth() <= width);
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				assertNoOverflow(child, x + child.getX(), width);
			}
		}
	}

	private static String describe(Component c)
	{
		return c instanceof JLabel ? "'" + ((JLabel) c).getText() + "'" : "";
	}

	/** Label texts in layout order. */
	static List<String> texts(Component c)
	{
		List<String> out = new ArrayList<>();
		collect(c, out);
		return out;
	}

	private static void collect(Component c, List<String> out)
	{
		if (c instanceof JLabel)
		{
			out.add(((JLabel) c).getText());
		}
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				collect(child, out);
			}
		}
	}

	static <T> T find(Component c, Class<T> type)
	{
		if (type.isInstance(c))
		{
			return type.cast(c);
		}
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				T t = find(child, type);
				if (t != null)
				{
					return t;
				}
			}
		}
		return null;
	}
}
