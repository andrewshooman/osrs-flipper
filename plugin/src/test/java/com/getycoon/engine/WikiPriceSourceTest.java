package com.getycoon.engine;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Parsing and caching, against canned responses (no network). */
public class WikiPriceSourceTest
{
	private static final long HOUR = 3_600_000;

	private final List<Request> requests = new ArrayList<>();
	private final AtomicLong clock = new AtomicLong(1791900000000L);
	private int status = 200;

	private WikiPriceSource source()
	{
		OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain ->
		{
			Request req = chain.request();
			requests.add(req);
			String path = req.url().encodedPath().replace("/api/v1/osrs", "")
				+ (req.url().encodedQuery() == null ? "" : "?" + req.url().encodedQuery());
			String body;
			if (path.equals("/mapping"))
			{
				body = "[{\"examine\":\"x\",\"id\":2,\"members\":true,\"lowalch\":2,\"limit\":11000,\"value\":5,\"highalch\":3,\"icon\":\"a.png\",\"name\":\"Cannonball\"},"
					+ "{\"id\":13190,\"members\":false,\"value\":1,\"name\":\"Old school bond\"}]";
			}
			else if (path.startsWith("/1h?timestamp=") || path.equals("/24h") || path.equals("/5m"))
			{
				body = "{\"data\":{\"561\":{\"avgHighPrice\":110,\"highPriceVolume\":900,\"avgLowPrice\":null,\"lowPriceVolume\":0},"
					+ "\"2\":{\"avgHighPrice\":170,\"highPriceVolume\":10,\"avgLowPrice\":165,\"lowPriceVolume\":12}},\"timestamp\":1791896400}";
			}
			else if (path.startsWith("/timeseries?timestep=1h&id="))
			{
				body = "{\"data\":[{\"timestamp\":1791892800,\"avgHighPrice\":171,\"avgLowPrice\":null,\"highPriceVolume\":5,\"lowPriceVolume\":null}],\"itemId\":2}";
			}
			else if (path.equals("/latest"))
			{
				body = "{\"data\":{\"2\":{\"high\":172,\"highTime\":1,\"low\":null,\"lowTime\":null}}}";
			}
			else
			{
				body = "{}";
			}
			return new Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(status).message("x")
				.body(ResponseBody.create(MediaType.parse("application/json"), body)).build();
		}).build();
		return new WikiPriceSource(http, new Gson(), WikiPriceSource.API_BASE, clock::get);
	}

	@Test
	public void parsesEveryEndpoint() throws IOException
	{
		WikiPriceSource src = source();
		Map<Integer, ItemMeta> items = src.mapping();
		assertEquals("Cannonball", items.get(2).name);
		assertEquals(11000, items.get(2).limit);
		assertTrue(items.get(2).members);
		assertEquals(0, items.get(13190).limit);
		assertEquals(0, items.get(13190).highalch);

		Map<Integer, HourPoint> snap = src.snapshot1h(1791896400L);
		assertEquals(List.of(2, 561), new ArrayList<>(snap.keySet()));
		assertTrue(Double.isNaN(snap.get(561).avgLowPrice));
		assertEquals(900, snap.get(561).highPriceVolume);
		assertEquals(165, snap.get(2).avgLowPrice, 0);

		List<HourPoint> ts = src.timeseries1h(2);
		assertEquals(1, ts.size());
		assertEquals(1791892800L, ts.get(0).timestamp);
		assertEquals(0, ts.get(0).lowPriceVolume);

		long[] lt = src.latest().get(2);
		assertEquals(172, lt[0]);
		assertEquals(0, lt[1]);
		assertEquals(2, src.avg24h().size());
		Map<Integer, HourPoint> five = src.latest5m();
		assertEquals(165, five.get(2).avgLowPrice, 0);
		assertEquals(1791896400L, five.get(2).timestamp);

		for (Request r : requests)
		{
			assertEquals(WikiPriceSource.USER_AGENT, r.header("User-Agent"));
			assertTrue(r.url().toString().startsWith(WikiPriceSource.API_BASE));
		}
	}

	@Test
	public void cachesAndServesStaleOnErrors() throws IOException
	{
		WikiPriceSource src = source();
		Map<Integer, ItemMeta> first = src.mapping();
		src.snapshot1h(1791896400L);
		src.latest();
		int n = requests.size();
		assertSame(first, src.mapping());
		src.snapshot1h(1791896400L);
		src.latest();
		assertEquals(n, requests.size());

		clock.addAndGet(61_000);
		src.latest();
		assertEquals(n + 1, requests.size());

		status = 500;
		clock.addAndGet(25 * HOUR);
		assertSame(first, src.mapping()); // stale beats nothing
		assertNull(source().avg24h());
	}

	@Test
	public void historyExpiresWhenTheNextBarIsDue()
	{
		long h = 1791900000000L - 1791900000000L % HOUR; // an hour boundary
		long grace = WikiPriceSource.BAR_GRACE;
		assertTrue(WikiPriceSource.seriesFresh(h + grace, h + grace + 10 * 60_000));
		assertFalse(WikiPriceSource.seriesFresh(h + grace, h + grace + 30 * 60_000));
		assertFalse(WikiPriceSource.seriesFresh(h + 50 * 60_000, h + HOUR + grace)); // the new bar is due
		assertTrue(WikiPriceSource.seriesFresh(h + 50 * 60_000, h + HOUR + grace - 1));
		assertFalse(WikiPriceSource.seriesFresh(h + HOUR + 30_000, h + HOUR + grace + 1)); // fetched before it was published
	}
}
