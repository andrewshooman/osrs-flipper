package com.geflipper.engine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** Model.predict (the bundled model) against js/gbm.js. */
public class ModelParityTest
{
	static final double TOL = 1e-9;
	private static final JsonObject FX = Fixtures.load("predict.json");
	private static Model model;

	@BeforeClass
	public static void loadModel() throws IOException
	{
		model = Model.loadDefault();
	}

	@AfterClass
	public static void report()
	{
		System.out.println("ModelParityTest: " + Fixtures.summary());
	}

	@Test
	public void boostersAndFeatures()
	{
		List<String> boosters = new ArrayList<>();
		for (JsonElement b : FX.getAsJsonArray("boosters"))
		{
			boosters.add(b.getAsString());
		}
		assertEquals(boosters, model.boosterNames());
		assertEquals(42, model.features().size());
		JsonObject keys = Fixtures.load("features.json").getAsJsonArray("cases").get(0).getAsJsonObject().getAsJsonObject("features");
		assertEquals(new java.util.TreeSet<>(keys.keySet()), new java.util.TreeSet<>(model.features()));
	}

	@Test
	public void predictions()
	{
		int n = 0;
		for (JsonElement ce : FX.getAsJsonArray("cases"))
		{
			JsonObject c = ce.getAsJsonObject();
			String name = c.get("name").getAsString();
			Map<String, Double> out = model.predict(Fixtures.numMap(c.get("feats")));
			JsonObject want = c.getAsJsonObject("out");
			assertEquals(name + ": boosters", new ArrayList<>(want.keySet()), new ArrayList<>(out.keySet()));
			for (String k : want.keySet())
			{
				Fixtures.assertNum(name + ": " + k, want.get(k), out.get(k), TOL);
			}
			n++;
		}
		assertEquals(true, n > 100);
	}
}
