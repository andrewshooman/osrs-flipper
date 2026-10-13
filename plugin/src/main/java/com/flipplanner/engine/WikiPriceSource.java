package com.flipplanner.engine;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Semaphore;
import java.util.function.LongSupplier;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * OSRS Wiki real-time prices client (https://oldschool.runescape.wiki/w/RuneScape:Real-time_Prices).
 * Thread-safe. Responses are cached (mapping 24h, /24h 30 min, /latest 60 s, hourly
 * snapshots for good, per-item history until the next hourly bar is due) and a stale copy is
 * served when a refresh fails. At most 6 requests run at once, as the wiki asks for modest load.
 */
public class WikiPriceSource implements PriceSource
{
	public static final String API_BASE = "https://prices.runescape.wiki/api/v1/osrs";
	/** The wiki asks every client to identify itself. */
	public static final String USER_AGENT = "Flip Planner RuneLite plugin (github.com/andrewshooman/osrs-flipper)";

	private static final long MINUTE = 60_000;
	private static final long HOUR = 60 * MINUTE;
	static final long MAPPING_TTL = 24 * HOUR;
	static final long AVG24H_TTL = 30 * MINUTE;
	static final long LATEST_TTL = MINUTE;
	static final long SERIES_TTL = 30 * MINUTE;
	/** An hourly bar shows up in /timeseries shortly after its hour ends. */
	static final long BAR_GRACE = MINUTE;
	private static final int SNAPSHOTS_KEPT = 10;
	private static final int SERIES_KEPT = 1000;
	private static final int MAX_CONCURRENT_REQUESTS = 6;

	private final OkHttpClient http;
	private final Gson gson;
	private final String base;
	private final LongSupplier clock;
	private final Semaphore permits = new Semaphore(MAX_CONCURRENT_REQUESTS);

	private final Slot<Map<Integer, ItemMeta>> mapping = new Slot<>();
	private final Slot<Map<Integer, HourPoint>> avg24h = new Slot<>();
	private final Slot<Map<Integer, long[]>> latest = new Slot<>();
	private final Map<Long, Map<Integer, HourPoint>> snapshots = lru(SNAPSHOTS_KEPT);
	private final Map<Integer, Cached<List<HourPoint>>> series = lru(SERIES_KEPT);

	public WikiPriceSource(OkHttpClient http)
	{
		this(http, new Gson());
	}

	public WikiPriceSource(OkHttpClient http, Gson gson)
	{
		this(http, gson, API_BASE, System::currentTimeMillis);
	}

	WikiPriceSource(OkHttpClient http, Gson gson, String base, LongSupplier clock)
	{
		this.http = http;
		this.gson = gson;
		this.base = base;
		this.clock = clock;
	}

	@Override
	public Map<Integer, ItemMeta> mapping() throws IOException
	{
		return mapping.get(MAPPING_TTL, () ->
		{
			JsonElement json = get("/mapping");
			Map<Integer, ItemMeta> out = new HashMap<>();
			if (json != null && json.isJsonArray())
			{
				for (JsonElement e : json.getAsJsonArray())
				{
					if (!e.isJsonObject())
					{
						continue;
					}
					JsonObject o = e.getAsJsonObject();
					int id = (int) num(o, "id", 0);
					JsonElement name = o.get("name");
					JsonElement members = o.get("members");
					out.put(id, new ItemMeta(id, name == null || name.isJsonNull() ? null : name.getAsString(),
						members != null && !members.isJsonNull() && members.getAsBoolean(),
						(int) num(o, "limit", 0), (int) num(o, "highalch", 0), (int) num(o, "value", 0)));
				}
			}
			return Collections.unmodifiableMap(out);
		});
	}

	@Override
	public Map<Integer, HourPoint> snapshot1h(long timestampSec) throws IOException
	{
		synchronized (snapshots)
		{
			Map<Integer, HourPoint> hit = snapshots.get(timestampSec);
			if (hit != null)
			{
				return hit;
			}
		}
		Map<Integer, HourPoint> data = points(get("/1h?timestamp=" + timestampSec));
		if (!data.isEmpty())
		{
			synchronized (snapshots)
			{
				snapshots.put(timestampSec, data);
			}
		}
		return data;
	}

	@Override
	public Map<Integer, HourPoint> avg24h()
	{
		try
		{
			return avg24h.get(AVG24H_TTL, () -> points(get("/24h")));
		}
		catch (IOException e)
		{
			return null; // not every deployment of the API has this route
		}
	}

	@Override
	public List<HourPoint> timeseries1h(int itemId) throws IOException
	{
		Cached<List<HourPoint>> hit;
		synchronized (series)
		{
			hit = series.get(itemId);
		}
		if (hit != null && seriesFresh(hit.at, clock.getAsLong()))
		{
			return hit.data;
		}
		try
		{
			JsonElement json = get("/timeseries?timestep=1h&id=" + itemId);
			List<HourPoint> out = new ArrayList<>();
			JsonElement data = json != null && json.isJsonObject() ? json.getAsJsonObject().get("data") : null;
			if (data != null && data.isJsonArray())
			{
				for (JsonElement e : data.getAsJsonArray())
				{
					if (e.isJsonObject())
					{
						JsonObject o = e.getAsJsonObject();
						out.add(point(o, (long) num(o, "timestamp", 0)));
					}
				}
			}
			List<HourPoint> pts = Collections.unmodifiableList(out);
			synchronized (series)
			{
				series.put(itemId, new Cached<>(clock.getAsLong(), pts));
			}
			return pts;
		}
		catch (IOException e)
		{
			if (hit != null)
			{
				return hit.data;
			}
			throw e;
		}
	}

	/** Fresh for 30 minutes, but never past the time the next hourly bar is due. */
	static boolean seriesFresh(long fetchedAt, long now)
	{
		long due = Math.floorDiv(now - BAR_GRACE, HOUR) * HOUR + BAR_GRACE;
		return now - fetchedAt < SERIES_TTL && fetchedAt >= due;
	}

	@Override
	public Map<Integer, long[]> latest() throws IOException
	{
		return latest.get(LATEST_TTL, () ->
		{
			Map<Integer, long[]> out = new HashMap<>();
			for (Map.Entry<Integer, JsonObject> e : byId(get("/latest")).entrySet())
			{
				JsonObject o = e.getValue();
				out.put(e.getKey(), new long[]{(long) num(o, "high", 0), (long) num(o, "low", 0)});
			}
			return Collections.unmodifiableMap(out);
		});
	}

	// ---- HTTP + parsing ----

	private JsonElement get(String path) throws IOException
	{
		Request req = new Request.Builder()
			.url(base + path)
			.header("User-Agent", USER_AGENT)
			.header("Accept", "application/json")
			.build();
		try
		{
			permits.acquire();
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new InterruptedIOException("Interrupted waiting to fetch " + path);
		}
		try (Response res = http.newCall(req).execute())
		{
			if (!res.isSuccessful())
			{
				throw new IOException("HTTP " + res.code() + " for " + path);
			}
			ResponseBody body = res.body();
			if (body == null)
			{
				throw new IOException("Empty response for " + path);
			}
			return gson.fromJson(body.charStream(), JsonElement.class);
		}
		catch (JsonParseException e)
		{
			throw new IOException("Bad JSON for " + path, e);
		}
		finally
		{
			permits.release();
		}
	}

	/** {data: {"id": {...}}} → sorted by id (the order JS iterates integer keys in). */
	private static Map<Integer, JsonObject> byId(JsonElement json)
	{
		Map<Integer, JsonObject> out = new TreeMap<>();
		JsonElement data = json != null && json.isJsonObject() ? json.getAsJsonObject().get("data") : null;
		if (data == null || !data.isJsonObject())
		{
			return out;
		}
		for (Map.Entry<String, JsonElement> e : data.getAsJsonObject().entrySet())
		{
			if (!e.getValue().isJsonObject())
			{
				continue;
			}
			try
			{
				out.put(Integer.parseInt(e.getKey()), e.getValue().getAsJsonObject());
			}
			catch (NumberFormatException ignored)
			{
				// not an item id
			}
		}
		return out;
	}

	private static Map<Integer, HourPoint> points(JsonElement json)
	{
		long ts = json != null && json.isJsonObject() ? (long) num(json.getAsJsonObject(), "timestamp", 0) : 0;
		Map<Integer, HourPoint> out = new TreeMap<>();
		for (Map.Entry<Integer, JsonObject> e : byId(json).entrySet())
		{
			out.put(e.getKey(), point(e.getValue(), ts));
		}
		return Collections.unmodifiableMap(out);
	}

	private static HourPoint point(JsonObject o, long ts)
	{
		return new HourPoint(ts, num(o, "avgHighPrice", Double.NaN), num(o, "avgLowPrice", Double.NaN),
			(long) num(o, "highPriceVolume", 0), (long) num(o, "lowPriceVolume", 0));
	}

	private static double num(JsonObject o, String key, double missing)
	{
		JsonElement e = o.get(key);
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber())
		{
			return missing;
		}
		return e.getAsDouble();
	}

	// ---- caching ----

	private interface Fetch<T>
	{
		T run() throws IOException;
	}

	private static final class Cached<T>
	{
		final long at;
		final T data;

		Cached(long at, T data)
		{
			this.at = at;
			this.data = data;
		}
	}

	/** One cached response with a TTL; serves the stale copy if a refresh fails. */
	private final class Slot<T>
	{
		private volatile Cached<T> cached;

		T get(long ttl, Fetch<T> fetch) throws IOException
		{
			Cached<T> hit = cached;
			if (hit != null && clock.getAsLong() - hit.at < ttl)
			{
				return hit.data;
			}
			try
			{
				T data = fetch.run();
				cached = new Cached<>(clock.getAsLong(), data);
				return data;
			}
			catch (IOException e)
			{
				if (hit != null)
				{
					return hit.data;
				}
				throw e;
			}
		}
	}

	private static <K, V> Map<K, V> lru(int max)
	{
		return new LinkedHashMap<K, V>(16, 0.75f, true)
		{
			@Override
			protected boolean removeEldestEntry(Map.Entry<K, V> eldest)
			{
				return size() > max;
			}
		};
	}
}
