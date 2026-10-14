package com.getycoon.tracking;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.runelite.client.util.Filepath;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TradeStoreTest
{
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	static class Data
	{
		String app = "ge-tycoon-plugin";
		int n;
	}

	private static Filepath rooted(Path p) throws IOException
	{
		Files.createDirectories(p);
		return Filepath.Unchecked.getRooted(p);
	}

	@Test
	public void saveLoadLogAndExport() throws IOException
	{
		Path root = tmp.getRoot().toPath().resolve("plugin-data");
		Path dir = root.resolve("ge-tycoon");
		TradeStore<Data> s = new TradeStore<>(new Gson(), rooted(root).joinSegment("ge-tycoon"), "acct", Data.class);
		assertNull(s.load());
		Data d = new Data();
		d.n = 42;
		s.save(d);
		d.n = 43;
		s.save(d);
		assertEquals(43, s.load().n);
		assertTrue(Files.exists(dir.resolve("acct.json")));
		s.append(d);
		s.append(d);
		List<String> lines = Files.readAllLines(dir.resolve("acct-offers.jsonl"), StandardCharsets.UTF_8);
		assertEquals(2, lines.size());
		assertEquals("{\"app\":\"ge-tycoon-plugin\",\"n\":43}", lines.get(0));

		Path out = tmp.getRoot().toPath().resolve("elsewhere");
		s.exportTo(rooted(out).joinSegment("copy.json"), d);
		assertTrue(new String(Files.readAllBytes(out.resolve("copy.json")), StandardCharsets.UTF_8).contains("\"n\":43"));
	}

	@Test
	public void damagedFileIsSetAside() throws IOException
	{
		Path dir = tmp.getRoot().toPath();
		Files.write(dir.resolve("acct.json"), "{not json".getBytes(StandardCharsets.UTF_8));
		TradeStore<Data> s = new TradeStore<>(new Gson(), rooted(dir), "acct", Data.class);
		try
		{
			s.load();
			fail("expected an error");
		}
		catch (TradeStore.DamagedException e)
		{
			assertTrue(e.getMessage().contains("damaged"));
		}
		assertNull(s.load());
		try (java.util.stream.Stream<Path> files = Files.list(dir))
		{
			assertEquals(1, files.filter(p -> p.getFileName().toString().startsWith("acct.json.damaged-")).count());
		}
	}
}
