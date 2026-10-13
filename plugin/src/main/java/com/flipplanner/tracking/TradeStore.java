package com.flipplanner.tracking;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * The trade history on disk: one JSON file per account, plus an append-only
 * log of every raw GE offer update (so the history can be rebuilt even if the
 * JSON is ever damaged). Writes go to a temporary file first and then replace
 * the old one, so a crash mid-write never leaves a half-written file.
 */
public class TradeStore<T>
{
	private final Gson gson;
	private final Path file;
	private final Path log;
	private final Class<T> type;

	public TradeStore(Gson gson, Path dir, String name, Class<T> type)
	{
		this.gson = gson;
		this.file = dir.resolve(name + ".json");
		this.log = dir.resolve(name + "-offers.jsonl");
		this.type = type;
	}

	public Path file()
	{
		return file;
	}

	/** The saved data, or null when there's none yet. A damaged file is set aside, not overwritten. */
	public T load() throws IOException
	{
		if (!Files.exists(file))
		{
			return null;
		}
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8))
		{
			return gson.fromJson(r, type);
		}
		catch (JsonParseException e)
		{
			Path aside = file.resolveSibling(file.getFileName() + ".damaged-" + System.currentTimeMillis());
			Files.move(file, aside);
			throw new IOException("Trade file was damaged; moved it to " + aside.getFileName() + " and started a new one", e);
		}
	}

	public synchronized void save(T data) throws IOException
	{
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8))
		{
			gson.toJson(data, w);
		}
		try
		{
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (AtomicMoveNotSupportedException e)
		{
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** Appends one raw offer update to the log. */
	public synchronized void append(Object event) throws IOException
	{
		Files.createDirectories(log.getParent());
		Files.write(log, (gson.toJson(event) + "\n").getBytes(StandardCharsets.UTF_8),
			StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}
}
