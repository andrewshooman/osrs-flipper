package com.geflipper;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Starts RuneLite with GE Flipper added, until the plugin is on the Plugin Hub.
 * Run it with your installed RuneLite's own files on the classpath (see the
 * start scripts in plugin/dist); nothing of RuneLite is bundled here.
 * RuneLite only allows this with Java assertions on (-ea).
 */
public class Launcher
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(GeFlipperPlugin.class);
		RuneLite.main(args);
	}
}
