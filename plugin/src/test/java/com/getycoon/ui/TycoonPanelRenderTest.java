package com.getycoon.ui;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import org.junit.Test;

/** Paints the side panel off-screen (and saves a PNG when FLIP_PLANNER_PNG_DIR names a folder). */
public class TycoonPanelRenderTest
{
	private static final TycoonPanel.Actions NOOP = new TycoonPanel.Actions()
	{
		public void refresh()
		{
		}

		public void export()
		{
		}

		public void skip()
		{
		}

		public void togglePause()
		{
		}

		public void block(int itemId, String name)
		{
		}

		public void graph(int itemId, String name, Double buy, Double sell)
		{
		}
	};

	@Test
	public void rendersPlanAndNextAction() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			net.runelite.client.ui.laf.RuneLiteLAF.setup();
			TycoonPanel p = new TycoonPanel(null, NOOP);
			PanelModel m = new PanelModel();
			m.status = "Updated 17:15 from the 16:00 prices";
			m.freeGp = 4_750_000;
			m.expectedProfit = 115_707;
			String[][] buys = {{"Garlic", "21", "8596", "24", "20768", "4.7"}, {"Armadyl chaps", "143363", "3", "156349", "19205", "8.0"}};
			for (String[] b : buys)
			{
				PanelModel.Buy x = new PanelModel.Buy();
				x.itemId = 1;
				x.name = b[0];
				x.price = Long.parseLong(b[1]);
				x.qty = Long.parseLong(b[2]);
				x.sellTarget = Long.parseLong(b[3]);
				x.expected = Double.parseDouble(b[4]);
				x.hours = Double.parseDouble(b[5]);
				m.buys.add(x);
			}
			PanelModel.Hold h = new PanelModel.Hold();
			h.itemId = 2;
			h.name = "Steel cannonball";
			h.qty = 5000;
			h.advice = "List at 259 (profit +520K)";
			h.tone = "good";
			m.holds.add(h);
			m.tidy.add("Cancel your Coal buy: it's been up 7h and the price has likely moved.");
			m.profitToday = 41_200;
			m.profitTotal = 386_000;
			m.flips = 23;
			m.winRate = 0.78;
			m.tradeFile = ".runelite/plugin-data/ge-tycoon/account-123.json";
			p.show(m);
			p.showNext("Sell 5,000 Steel cannonball", "at 259 gp each", false, false);
			p.setSize(242, 900);
			layout(p);
			BufferedImage img = new BufferedImage(242, 900, BufferedImage.TYPE_INT_ARGB);
			p.paint(img.getGraphics());
			String out = System.getenv("FLIP_PLANNER_PNG_DIR");
			File dir = out == null ? null : new File(out);
			if (dir != null && dir.isDirectory())
			{
				try
				{
					ImageIO.write(img, "png", new File(dir, "panel.png"));
				}
				catch (Exception e)
				{
					throw new RuntimeException(e);
				}
			}
		});
	}

	private static void layout(java.awt.Component c)
	{
		c.doLayout();
		if (c instanceof java.awt.Container)
		{
			for (java.awt.Component k : ((java.awt.Container) c).getComponents())
			{
				layout(k);
			}
		}
	}
}
