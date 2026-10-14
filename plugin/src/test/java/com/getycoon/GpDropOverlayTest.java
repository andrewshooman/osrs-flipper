package com.getycoon;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class GpDropOverlayTest
{
	@Test
	public void text()
	{
		assertEquals("+12.3K gp", GpDropOverlay.text(12_345));
		assertEquals("-4,200 gp", GpDropOverlay.text(-4_200));
		assertEquals("+1.50M gp", GpDropOverlay.text(1_500_000));
	}

	@Test
	public void fadesOutAtTheEnd()
	{
		assertEquals(255, GpDropOverlay.alpha(0));
		assertEquals(255, GpDropOverlay.alpha(GpDropOverlay.LIFE_MS * 6 / 10));
		assertEquals(128, GpDropOverlay.alpha(GpDropOverlay.LIFE_MS * 85 / 100), 1);
		assertEquals(0, GpDropOverlay.alpha(GpDropOverlay.LIFE_MS));
	}
}
