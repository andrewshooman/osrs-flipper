package com.getycoon.tracking;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import net.runelite.client.util.Filepath;

/**
 * The trade history on disk: one JSON file per account, plus an append-only
 * log of every raw GE offer update (so the history can be rebuilt even if the
 * JSON is ever damaged). Writes go to a temporary file first and then replace
 * the old one, so a crash mid-write never leaves a half-written file.
 * Files live in the plugin's own data directory (RuneLite's Filepath API).
 */
public class TradeStore<T>
{
	private final Gson gson;
	private final Filepath dir;
	private final String name;
	private final Class<T> type;

	/** The file couldn't be parsed and has been moved aside, so starting a new one is safe. */
	public static class DamagedException extends IOException
	{
		DamagedException(String message, Throwable cause)
		{
			super(message, cause);
		}
	}

	public TradeStore(Gson gson, Filepath dir, String name, Class<T> type)
	{
		this.gson = gson;
		this.dir = dir;
		this.name = name;
		this.type = type;
	}

	public Filepath file()
	{
		return dir.joinSegment(name + ".json");
	}

	private Filepath log()
	{
		return dir.joinSegment(name + "-offers.jsonl");
	}

	/**
	 * The saved data, or null when there's none yet. A damaged file is set aside, not
	 * overwritten ({@link DamagedException}). Any other IOException means the file is
	 * still there but couldn't be read right now: don't start a new one over it.
	 */
	public T load() throws IOException
	{
		Filepath file = file();
		if (!file.exists())
		{
			return null;
		}
		try (Reader r = file.openBufferedReader())
		{
			return gson.fromJson(r, type);
		}
		catch (JsonParseException e)
		{
			Filepath aside = dir.joinSegment(name + ".json.damaged-" + System.currentTimeMillis());
			file.moveTo(aside);
			throw new DamagedException("Trade file was damaged; moved it to " + aside.getFileName() + " and started a new one", e);
		}
	}

	public synchronized void save(T data) throws IOException
	{
		dir.createDirectories();
		Filepath tmp = dir.joinSegment(name + ".json.tmp");
		try (Writer w = tmp.openBufferedWriter())
		{
			gson.toJson(data, w);
		}
		try
		{
			tmp.moveTo(file(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (AtomicMoveNotSupportedException e)
		{
			tmp.moveTo(file(), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** Appends one raw offer update to the log. */
	public synchronized void append(Object event) throws IOException
	{
		dir.createDirectories();
		log().write((gson.toJson(event) + "\n").getBytes(StandardCharsets.UTF_8),
			StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
	}

	/** Writes a copy of the data to a file the player picked (Export). */
	public void exportTo(Filepath target, T data) throws IOException
	{
		try (Writer w = target.openBufferedWriter())
		{
			gson.toJson(data, w);
		}
	}
}
