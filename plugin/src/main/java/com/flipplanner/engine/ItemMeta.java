package com.flipplanner.engine;

/** Item metadata from the wiki's /mapping. */
public final class ItemMeta
{
	/** Stand-in for an item missing from /mapping (JS: {@code items.get(id) || {}}). */
	static final ItemMeta UNKNOWN = new ItemMeta(0, null, false, 0, 0, 0);

	public final int id;
	public final String name;
	public final boolean members;
	/** GE buy limit per 4 hours; 0 when unknown. */
	public final int limit;
	public final int highalch;
	public final int value;

	public ItemMeta(int id, String name, boolean members, int limit, int highalch, int value)
	{
		this.id = id;
		this.name = name;
		this.members = members;
		this.limit = limit;
		this.highalch = highalch;
		this.value = value;
	}

	@Override
	public String toString()
	{
		return "ItemMeta{" + id + " " + name + "}";
	}
}
