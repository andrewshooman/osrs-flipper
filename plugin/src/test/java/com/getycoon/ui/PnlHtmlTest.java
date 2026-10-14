package com.getycoon.ui;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class PnlHtmlTest
{
	@Test
	public void gainsGreenLossesRedWithPercentOfCost()
	{
		assertEquals("<font color='#4CC05A'>+40.5K (+3.4%)</font>", TycoonPanel.pnlHtml(40_500, 1_200_000));
		assertEquals("<font color='#E05A4C'>-310 (-0.6%)</font>", TycoonPanel.pnlHtml(-310, 50_000));
		assertEquals("<font color='#4CC05A'>0</font>", TycoonPanel.pnlHtml(0, 0));
	}
}
