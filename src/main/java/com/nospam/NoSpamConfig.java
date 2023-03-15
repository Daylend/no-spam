package com.nospam;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Units;

@ConfigGroup("nospam")
public interface NoSpamConfig extends Config
{
	@Units(Units.PERCENT)
	@ConfigItem(
			keyName = "threshold",
			name = "Threshold",
			description = "Lowering this will make the filter block more messages (at the cost of more false positives)"
	)
	default double threshold() {
		return 0.1;
	}

	@ConfigItem(
			keyName = "filtertype",
			name = "Filter type",
			description = "Filter type for chatbox"
	)
	default NoSpamType filterType() {
		return NoSpamType.HIDE_MESSAGES;
	}

	@ConfigItem(
			keyName = "usernameregex",
			name = "Username Regex",
			description = "Regex to use to filter names from chat"
	)
	default String usernameRegex(){
		return "";
	}

	@ConfigItem(
			keyName = "messageregex",
			name = "Message Regex",
			description = "Regex to use to filter messages from chat"
	)
	default String messageRegex(){
		return "";
	}

	@ConfigItem(
			keyName = "enablecorpus",
			name = "Right-click flag spam",
			description = "Right click to save to good and bad corpus for training. Doesn't actually do much currently."
	)
	default boolean corpusEnabled(){
		return false;
	}
}
