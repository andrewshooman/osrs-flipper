package com.flipplanner.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** JsSort against V8's Array.prototype.sort, including comparators that return NaN. */
public class JsSortParityTest
{
	@Test
	public void sameOrderAsV8()
	{
		int n = 0;
		for (JsonElement ce : Fixtures.load("sort.json").getAsJsonArray("cases"))
		{
			JsonObject c = ce.getAsJsonObject();
			JsonArray keys = c.getAsJsonArray("keys");
			double[] k = new double[keys.size()];
			List<Integer> idx = new ArrayList<>();
			for (int i = 0; i < k.length; i++)
			{
				k[i] = Fixtures.num(keys.get(i));
				idx.add(i);
			}
			boolean desc = c.get("desc").getAsBoolean();
			JsSort.sort(idx, desc ? (a, b) -> k[b] - k[a] : (a, b) -> k[a] - k[b]);
			List<Integer> want = new ArrayList<>();
			for (JsonElement p : c.getAsJsonArray("perm"))
			{
				want.add(p.getAsInt());
			}
			assertEquals("case " + n + " (" + k.length + " keys, desc " + desc + ")", want, idx);
			n++;
		}
	}
}
