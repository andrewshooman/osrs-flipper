package com.getycoon.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.AfterClass;
import org.junit.Test;
import static com.getycoon.engine.Fixtures.assertNum;
import static com.getycoon.engine.Fixtures.isNull;
import static com.getycoon.engine.Fixtures.numOr;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Features.denseSeries / itemFeatures against js/features.js. */
public class FeaturesParityTest
{
	static final double TOL = 1e-12;
	private static final JsonObject FX = Fixtures.load("features.json");

	@AfterClass
	public static void report()
	{
		System.out.println("FeaturesParityTest: " + Fixtures.summary());
	}

	static ItemMeta item(JsonElement e)
	{
		if (isNull(e))
		{
			return null;
		}
		JsonObject o = e.getAsJsonObject();
		return new ItemMeta(0, null, false, (int) numOr(o, "limit", 0), (int) numOr(o, "highalch", 0), 0);
	}

	static MarketContext ctx(JsonElement e)
	{
		if (isNull(e))
		{
			return null;
		}
		JsonObject o = e.getAsJsonObject();
		return new MarketContext(numOr(o, "mkt_ret_1h", 0), numOr(o, "mkt_ret_24h", 0));
	}

	@Test
	public void features()
	{
		List<String> keys = new ArrayList<>();
		for (JsonElement k : FX.getAsJsonArray("keys"))
		{
			keys.add(k.getAsString());
		}
		assertEquals(42, keys.size());
		JsonArray cases = FX.getAsJsonArray("cases");
		assertTrue(cases.size() >= 40);
		for (JsonElement ce : cases)
		{
			JsonObject c = ce.getAsJsonObject();
			String name = c.get("name").getAsString();
			Series s = Features.denseSeries(Fixtures.points(c.getAsJsonArray("points")), c.get("barTs").getAsLong());
			Map<String, Double> f = Features.itemFeatures(s, item(c.get("item")), ctx(c.get("ctx")));
			assertEquals(name + ": key order", keys, new ArrayList<>(f.keySet()));
			JsonObject want = c.getAsJsonObject("features");
			assertEquals(name + ": key order (case)", new ArrayList<>(want.keySet()), new ArrayList<>(f.keySet()));
			for (String k : keys)
			{
				assertNum(name + ": " + k, want.get(k), f.get(k), TOL);
			}
			Bar bar = s.lastBar();
			JsonObject wb = c.getAsJsonObject("bar");
			assertNum(name + ": bar ah", wb.get("ah"), bar.ah, 0);
			assertNum(name + ": bar al", wb.get("al"), bar.al, 0);
			assertNum(name + ": bar hv", wb.get("hv"), bar.hv, 0);
			assertNum(name + ": bar lv", wb.get("lv"), bar.lv, 0);
		}
	}

	@Test
	public void denseSeries()
	{
		for (JsonElement ce : FX.getAsJsonArray("dense"))
		{
			JsonObject c = ce.getAsJsonObject();
			int bars = c.get("bars").getAsInt();
			Series s = Features.denseSeries(Fixtures.points(c.getAsJsonArray("points")), c.get("barTs").getAsLong(), bars);
			String[] names = {"ah", "al", "hv", "lv"};
			double[][] got = {s.ah, s.al, s.hv, s.lv};
			for (int k = 0; k < 4; k++)
			{
				JsonArray want = c.getAsJsonArray(names[k]);
				assertEquals(names[k] + " length", want.size(), got[k].length);
				for (int i = 0; i < bars; i++)
				{
					assertNum("dense " + names[k] + "[" + i + "]", want.get(i), got[k][i], 0);
				}
			}
		}
	}
}
