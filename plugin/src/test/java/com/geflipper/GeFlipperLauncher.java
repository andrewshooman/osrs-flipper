package com.geflipper;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/** Starts RuneLite with GE Flipper loaded (used by the runnable jar and `gradle run`). */
public class GeFlipperLauncher
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(GeFlipperPlugin.class);
		RuneLite.main(args);
	}
}
