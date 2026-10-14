package com.getycoon.engine;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Reads the parity fixtures written by plugin/tools/make-fixtures.js (non-finite numbers
 * and -0 are the strings "NaN", "Infinity", "-Infinity", "-0") and compares Java results to them.
 */
final class Fixtures
{
	static final Gson GSON = new Gson();

	/** Doubles compared so far, and how many were not bit-identical to the JS. */
	private static int compared;
	private static int inexact;
	private static double worst;

	private Fixtures()
	{
	}

	static JsonObject load(String name)
	{
		String path = "/com/getycoon/fixtures/" + name;
		try (InputStream in = Fixtures.class.getResourceAsStream(path))
		{
			if (in == null)
			{
				throw new IllegalStateException("Missing fixture " + path + ": run TZ=UTC node plugin/tools/make-fixtures.js");
			}
			return GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	static boolean isNull(JsonElement e)
	{
		return e == null || e.isJsonNull();
	}

	/** A fixture number; null / absent read as NaN (what the Java API uses for "no value"). */
	static double num(JsonElement e)
	{
		return isNull(e) ? Double.NaN : e.getAsDouble();
	}

	static double num(JsonObject o, String key)
	{
		return num(o.get(key));
	}

	static double numOr(JsonObject o, String key, double missing)
	{
		return isNull(o.get(key)) ? missing : num(o.get(key));
	}

	static String str(JsonElement e)
	{
		return isNull(e) ? null : e.getAsString();
	}

	/** {key: number} → map; absent keys stay absent, "NaN" → NaN. */
	static Map<String, Double> numMap(JsonElement e)
	{
		if (isNull(e))
		{
			return null;
		}
		Map<String, Double> m = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> x : e.getAsJsonObject().entrySet())
		{
			m.put(x.getKey(), num(x.getValue()));
		}
		return m;
	}

	static List<HourPoint> points(JsonArray rows)
	{
		List<HourPoint> out = new ArrayList<>();
		for (JsonElement r : rows)
		{
			JsonArray a = r.getAsJsonArray();
			out.add(new HourPoint(a.get(0).getAsLong(), num(a.get(1)), num(a.get(2)),
				isNull(a.get(3)) ? 0 : a.get(3).getAsLong(), isNull(a.get(4)) ? 0 : a.get(4).getAsLong()));
		}
		return out;
	}

	static HourPoint snapshotPoint(JsonObject o)
	{
		return new HourPoint(0, num(o, "avgHighPrice"), num(o, "avgLowPrice"),
			isNull(o.get("highPriceVolume")) ? 0 : o.get("highPriceVolume").getAsLong(),
			isNull(o.get("lowPriceVolume")) ? 0 : o.get("lowPriceVolume").getAsLong());
	}

	static Bar bar(JsonElement e)
	{
		if (isNull(e))
		{
			return null;
		}
		JsonObject b = e.getAsJsonObject();
		return new Bar(num(b, "ah"), num(b, "al"), numOr(b, "hv", 0), numOr(b, "lv", 0));
	}

	/** Equal within {@code relTol} of the expected value; NaN equals NaN. */
	static void assertNum(String what, JsonElement expected, double actual, double relTol)
	{
		assertNum(what, num(expected), actual, relTol);
	}

	static synchronized void assertNum(String what, double e, double a, double relTol)
	{
		compared++;
		if (Double.isNaN(e) || Double.isNaN(a))
		{
			if (Double.isNaN(e) != Double.isNaN(a))
			{
				fail(what + ": expected " + e + " but was " + a);
			}
			return;
		}
		if (e == a)
		{
			return;
		}
		inexact++;
		double rel = Math.abs(a - e) / Math.max(Math.abs(e), Double.MIN_NORMAL);
		worst = Math.max(worst, rel);
		if (Double.isInfinite(e) || Double.isInfinite(a) || rel > relTol)
		{
			fail(what + ": expected " + e + " but was " + a + " (relative error " + rel + ")");
		}
	}

	/** Integer-valued results (prices, quantities, timestamps) must match exactly. */
	static void assertExact(String what, JsonElement expected, double actual)
	{
		assertNum(what, expected, actual, 0);
	}

	/** Nullable number: absent / null in the fixture means null in Java. */
	static void assertNullable(String what, JsonElement expected, Number actual, double relTol)
	{
		if (isNull(expected))
		{
			assertNull(what, actual);
			return;
		}
		assertTrue(what + ": expected " + expected + " but was null", actual != null);
		assertNum(what, expected, actual.doubleValue(), relTol);
	}

	static void assertIdea(String what, JsonObject e, BuyIdea a, double tol)
	{
		assertEquals(what + " id", e.get("id").getAsInt(), a.id);
		assertEquals(what + " name", str(e.get("name")), a.name);
		assertExact(what + " buy", e.get("buy"), a.buy);
		assertExact(what + " qty", e.get("qty"), a.qty);
		assertNum(what + " cost", e.get("cost"), a.cost, tol);
		assertNum(what + " expRet", e.get("expRet"), a.expRet, tol);
		assertNum(what + " expected", e.get("expected"), a.expected, tol);
		assertNum(what + " hours", e.get("hours"), a.hours, tol);
		assertExact(what + " sellTarget", e.get("sellTarget"), a.sellTarget);
		assertNum(what + " fill", e.get("fill"), a.fill, tol);
		assertNum(what + " q50", e.get("q50"), a.q50, tol);
		JsonArray reasons = e.getAsJsonArray("reasons");
		assertEquals(what + " reasons", reasons.size(), a.reasons.size());
		for (int i = 0; i < reasons.size(); i++)
		{
			assertEquals(what + " reason " + i, reasons.get(i).getAsString(), a.reasons.get(i));
		}
		assertNullable(what + " liveLow", e.get("liveLow"), a.liveLow, 0);
		assertEquals(what + " drift", str(e.get("drift")), a.drift);
	}

	static void assertAction(String what, JsonObject e, HoldingAction a)
	{
		assertEquals(what + " action", e.get("action").getAsString(), a.action);
		assertEquals(what + " label", e.get("label").getAsString(), a.label);
		assertEquals(what + " tone", e.get("tone").getAsString(), a.tone);
		assertNullable(what + " price", e.get("price"), a.price, 0);
	}

	/** Counts since the last summary (test classes run one after another). */
	static synchronized String summary()
	{
		String s = compared + " numbers compared, " + inexact + " not bit-identical"
			+ (inexact > 0 ? " (worst relative error " + worst + ")" : "");
		compared = 0;
		inexact = 0;
		worst = 0;
		return s;
	}
}
