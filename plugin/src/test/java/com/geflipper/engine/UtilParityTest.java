package com.geflipper.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.TreeSet;
import org.junit.AfterClass;
import org.junit.Test;
import static com.geflipper.engine.Fixtures.assertExact;
import static com.geflipper.engine.Fixtures.num;
import static org.junit.Assert.assertEquals;

/** Tax, breakeven and number formatting against js/util.js. */
public class UtilParityTest
{
	private static final JsonObject FX = Fixtures.load("util.json");

	@AfterClass
	public static void report()
	{
		System.out.println("UtilParityTest: " + Fixtures.summary());
	}

	@Test
	public void exemptList()
	{
		TreeSet<Integer> js = new TreeSet<>();
		for (JsonElement e : FX.getAsJsonArray("exempt"))
		{
			js.add(e.getAsInt());
		}
		assertEquals(48, Tax.TAX_EXEMPT.size());
		assertEquals(js, new TreeSet<>(Tax.TAX_EXEMPT));
	}

	@Test
	public void tax()
	{
		JsonArray rows = FX.getAsJsonArray("tax");
		for (JsonElement r : rows)
		{
			JsonArray a = r.getAsJsonArray();
			double price = num(a.get(0));
			int id = a.get(1).getAsInt();
			String what = "price " + price + " item " + id;
			assertExact(what + " tax", a.get(2), Tax.perItem(price, id));
			assertExact(what + " net", a.get(3), Tax.netSellPrice(price, id));
			if (price == Math.rint(price) && Math.abs(price) < 9e15)
			{
				assertExact(what + " tax (long)", a.get(2), Tax.perItem((long) price, id));
				assertExact(what + " net (long)", a.get(3), Tax.netSellPrice((long) price, id));
			}
		}
		assertEquals(true, rows.size() > 300);
	}

	@Test
	public void breakeven()
	{
		for (JsonElement r : FX.getAsJsonArray("breakeven"))
		{
			JsonArray a = r.getAsJsonArray();
			double cost = num(a.get(0));
			int id = a.get(1).getAsInt();
			assertExact("breakeven of " + cost + " item " + id, a.get(2), Tax.breakevenSellPrice(cost, id));
		}
	}

	@Test
	public void formatting()
	{
		for (JsonElement r : FX.getAsJsonArray("fmt"))
		{
			JsonArray a = r.getAsJsonArray();
			double n = num(a.get(0));
			String what = "n=" + a.get(0);
			assertEquals(what + " gp", a.get(1).getAsString(), Fmt.gp(n));
			assertEquals(what + " gp plus", a.get(2).getAsString(), Fmt.gp(n, true));
			assertEquals(what + " full", a.get(3).getAsString(), Fmt.full(n));
			assertEquals(what + " pct", a.get(4).getAsString(), Fmt.pct(n));
			assertEquals(what + " pct 0", a.get(5).getAsString(), Fmt.pct(n, 0));
			assertEquals(what + " pct 2", a.get(6).getAsString(), Fmt.pct(n, 2));
			assertEquals(what + " duration", a.get(7).getAsString(), Fmt.duration(n));
		}
	}

	@Test
	public void clamp()
	{
		for (JsonElement r : FX.getAsJsonArray("clamp"))
		{
			JsonArray a = r.getAsJsonArray();
			Fixtures.assertNum("clamp " + a.get(0), a.get(1), Fmt.clamp(num(a.get(0)), 0, 1), 0);
		}
	}
}
