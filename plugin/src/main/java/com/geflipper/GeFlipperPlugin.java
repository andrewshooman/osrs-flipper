package com.geflipper;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(name = "GE Flipper", description = "Grand Exchange flipping assistant", tags = {"ge", "flipping"})
public class GeFlipperPlugin extends Plugin
{
	@Provides
	GeFlipperConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GeFlipperConfig.class);
	}
}
