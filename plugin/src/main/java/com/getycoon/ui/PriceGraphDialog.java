package com.getycoon.ui;

import com.getycoon.engine.HourPoint;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Window;
import java.util.List;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/** A small window with an item's 7-day price graph. */
public final class PriceGraphDialog
{
	private PriceGraphDialog()
	{
	}

	/** Opens a non-modal graph window over {@code parent}. Call on the Swing thread. */
	public static void open(Component parent, String title, List<HourPoint> points, Double buy, Double sell)
	{
		Window owner = parent instanceof Window ? (Window) parent : parent == null ? null : SwingUtilities.getWindowAncestor(parent);
		JDialog dialog = new JDialog(owner, "GE Tycoon: " + title, Dialog.ModalityType.MODELESS);
		PriceGraphPanel graph = new PriceGraphPanel();
		graph.setData(title, points, buy, sell, System.currentTimeMillis() / 1000);
		dialog.getContentPane().add(graph, BorderLayout.CENTER);
		dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
		dialog.setSize(520, 320);
		dialog.setLocationRelativeTo(parent);
		dialog.setVisible(true);
	}
}
