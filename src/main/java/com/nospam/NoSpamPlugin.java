package com.nospam;

import javax.inject.Inject;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.OverheadTextChanged;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;

import java.awt.Color;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

@Slf4j
@PluginDescriptor(
	name = "No Spam"
)
public class NoSpamPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private NoSpamConfig config;

	File configDir;
	File userGoodCorpusFile;
	File userBadCorpusFile;

	// corpuses resulting from user selecting "mark spam" and "mark ham"
	private List<String> userGoodCorpus;
	private List<String> userBadCorpus;

	String[] regexPatterns;

	@Provides
	NoSpamConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(NoSpamConfig.class);
	}

	@Override
	protected void startUp() throws Exception {
		configDir = new File(RuneLite.RUNELITE_DIR, "spam-filter");
		userGoodCorpusFile = new File(configDir, "user_good_corpus.txt");
		userBadCorpusFile = new File(configDir, "user_bad_corpus.txt");
		if (configDir.mkdir()) {
			log.info("Made spam-filter directory");
			boolean good = userGoodCorpusFile.createNewFile();
			boolean bad = userBadCorpusFile.createNewFile();
			assert good && bad;
		}

		userGoodCorpus = Files.readAllLines(userGoodCorpusFile.toPath());
		userBadCorpus = Files.readAllLines(userBadCorpusFile.toPath());
		log.info("Loaded user corpus files with " + userGoodCorpus.size() + " (g) & " + userBadCorpus.size() + " (b) entries");

		regexPatterns = config.messageRegex().split(",");
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event) {
		if (event.getGroup().equals("nospam") && event.getKey().equals("messageregex")) {
			regexPatterns = config.messageRegex().split(",");
		}
	}


	@Subscribe
	public void onMenuOpened(MenuOpened event) {
		// Only used for collecting data for the dataset
		if (!config.corpusEnabled()){
			return;
		}
		MenuEntry[] entries = event.getMenuEntries();
		// take second entry since the first may be something like "walk here" if chatbox is transparent
		MenuEntry secondEntry = entries[entries.length - 2];

		int clickedWidgetId = secondEntry.getParam1();
		Widget widget = client.getWidget(clickedWidgetId);
		if (widget == null) {
			return;
		}

		// did user click on a chat?
		if (widget.getParentId() != WidgetInfo.CHATBOX_MESSAGE_LINES.getId()) {
			return;
		}

		// As far as I can tell by skimming the builtin chat history and hiscores plugins:
		// Click doesn't happen on a chat message, it happens on the *sender* of the chat message.
		// There is a static list of senders. First static child is the most recent sender,
		// Second static child is second most recent sender, and so on.
		// Chat messages are dynamic children of CHATBOX_MESSAGES_LINES.
		int firstChatSender = WidgetInfo.CHATBOX_FIRST_MESSAGE.getChildId();
		int clickedChatSender = WidgetInfo.TO_CHILD(clickedWidgetId);
		int clickOffset = clickedChatSender - firstChatSender;
		// can calculate the offset between "clicked-on chat message" and "most recent chat message"
		// by looking at the offset between "clicked-on chat sender" and "most recent chat sender"
		int selectedChatOffset = (clickOffset * 4) + 1;

		Widget selectedChatWidget = widget.getParent().getChild(selectedChatOffset);
		if (selectedChatWidget == null) {
			return;
		}
		String selectedChat = Text.removeTags(selectedChatWidget.getText());
			client.createMenuEntry(1)
					.setOption("Mark spam")
					.setType(MenuAction.RUNELITE)
					.setTarget(ColorUtil.wrapWithColorTag("message", Color.RED))
					.onClick(e -> {
						markSpam(selectedChat);
					});
			client.createMenuEntry(1)
					.setOption("Mark ham")
					.setType(MenuAction.RUNELITE)
					.setTarget(ColorUtil.wrapWithColorTag("message", Color.GREEN))
					.onClick(e -> {
						markHam(selectedChat);
					});
	}

	private void appendToUserCorpus(String message, List<String> corpus, File corpusFile) {
		corpus.add(message);
		try {
			Files.write(corpusFile.toPath(), corpus, StandardOpenOption.TRUNCATE_EXISTING);
		} catch (Exception e) {
			log.warn("Something went wrong writing a corpus file", e);
		}
	}

	private void markSpam(String chatLine) {
		appendToUserCorpus(chatLine, userBadCorpus, userBadCorpusFile);
	}

	private void markHam(String chatLine) {
		appendToUserCorpus(chatLine, userGoodCorpus, userGoodCorpusFile);
	}

	@Subscribe
	public void onOverheadTextChanged(OverheadTextChanged event) {
		if (!(event.getActor() instanceof Player)) {
			return;
		}

		String message = event.getOverheadText();

		if (containsExcessiveNonAscii(message) || matchesMessageContentRegex(message)) {
			event.getActor().setOverheadText(" ");
		}
	}

	@Subscribe
	public void onScriptCallbackEvent(ScriptCallbackEvent event) {
		if (!event.getEventName().equals("chatFilterCheck")) {
			return;
		}

		int[] intStack = client.getIntStack();
		int intStackSize = client.getIntStackSize();
		String[] stringStack = client.getStringStack();
		int stringStackSize = client.getStringStackSize();

		final int messageType = intStack[intStackSize - 2];
		final int messageId = intStack[intStackSize - 1];
		String message = stringStack[stringStackSize - 1];
		String normalizedMessage = removeAccents(message);
		ChatMessageType chatMessageType = ChatMessageType.of(messageType);
		if (chatMessageType != ChatMessageType.PUBLICCHAT) {
			return;
		}

		log.debug(normalizedMessage);

		boolean matchesRegex = matchesMessageContentRegex(normalizedMessage);

		if (containsExcessiveNonAscii(message) || matchesRegex) {
			if (config.filterType() == NoSpamType.HIDE_MESSAGES) {
				intStack[intStackSize - 3] = 0;
			} else if (config.filterType() == NoSpamType.GREY_MESSAGES){
				message = ColorUtil.wrapWithColorTag(message, Color.GRAY);
			}
		}
		stringStack[stringStackSize - 1] = message;

	}

	private boolean containsExcessiveNonAscii(String message)
	{
		int nonAsciiCount = 0;
		int totalCharacters = message.length();

		for (int i = 0; i < totalCharacters; i++)
		{
			char character = message.charAt(i);
			if ((int) character >= 128)
			{
				nonAsciiCount++;
			}

			double nonAsciiRatio = (double) nonAsciiCount / (i + 1);
			if (nonAsciiRatio > config.threshold())
			{
				return true;
			}
		}

		return false;
	}

	private boolean matchesUsernameRegex(String sender)
	{
		try
		{
			Pattern pattern = Pattern.compile(config.usernameRegex(), Pattern.CASE_INSENSITIVE);
			Matcher matcher = pattern.matcher(sender);
			return matcher.find();
		}
		catch (PatternSyntaxException e)
		{
			log.warn("Invalid regex pattern in usernameRegex config. Please check the configuration.");
			return false;
		}
	}

	private boolean matchesMessageContentRegex(String message)
	{
		String trimmedMessage = message.trim();
		boolean matches = false;

		try
		{
			for (String patternStr : regexPatterns)
			{
				Pattern pattern = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE);
				Matcher matcher = pattern.matcher(trimmedMessage);
				if (matcher.find())
				{
					matches = true;
					break;
				}
			}
		}
		catch (PatternSyntaxException e)
		{
			log.warn("Invalid regex pattern in contentRegex config. Please check the configuration.");
		}

		return matches;
	}

	private String removeAccents(String message)
	{
		String normalizedMessage = Normalizer.normalize(message, Normalizer.Form.NFD);
		return normalizedMessage.replaceAll("[^\\p{ASCII}]", "");
	}

}
