package me.codemetry.sbidler;

import static net.minecraft.client.Minecraft.getMinecraft;

import java.awt.Color;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.lwjgl.input.Keyboard;

import com.google.common.collect.ImmutableList;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiOptionButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.GameSettings.Options;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer.EnumChatVisibility;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.ClickEvent.Action;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.GuiScreenEvent.ActionPerformedEvent;
import net.minecraftforge.client.event.GuiScreenEvent.DrawScreenEvent;
import net.minecraftforge.client.event.GuiScreenEvent.KeyboardInputEvent;
import net.minecraftforge.client.event.GuiScreenEvent.MouseInputEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent.ClientConnectedToServerEvent;

@Mod(modid = "sbidler", clientSideOnly = true, version = "1.1")
public class SkyBlockIdler extends CommandBase {

	private static WeakReference<SkyBlockIdler> instance;

	public static SkyBlockIdler getInstance() {
		return instance.get();
	}

	private Scheduler scheduler;

	public SkyBlockIdler() throws IllegalArgumentException, FileNotFoundException, IOException {
		this(new Scheduler(), 30, 3600, 5);
	}

	public SkyBlockIdler(Scheduler scheduler, int timeout, int warpInt, int fps)
			throws IllegalArgumentException, FileNotFoundException, IOException {
		if (instance != null && instance.get() != null)
			throw new IllegalStateException("Already instantiated.");
		instance = new WeakReference<SkyBlockIdler>(this);
		this.scheduler = scheduler;
		this.timeout = timeout;
		this.warpInt = warpInt;
		this.fps = fps;
		this.warpInt = warpInt;
		this.properties = new Properties();
		this.afk_timer = new Task(this.timeout * 20, false, false) {

			@Override
			public void run() {
				inactive();
			}

		};
		this.warp_timer = new Task(this.warpInt * 20, false, false) {

			@Override
			public void run() {
				warp();
			}

		};
		properties.setProperty("afk-timeout", Integer.toString(timeout));
		properties.setProperty("warp-interval", Integer.toString(warpInt));
		properties.setProperty("afk-fps", Integer.toString(fps));
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try {
				saveConfiguration();
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}));
		loadConfiguration();
	}

	Scheduler getScheduler() {
		return scheduler;
	}

	@EventHandler
	public void preInit(FMLPreInitializationEvent event) {
		MinecraftForge.EVENT_BUS.register(this);
		ClientCommandHandler.instance.registerCommand(this);
	}

	// Configuration Component

	private static final File CONFIG_FILE = new File("mods" + File.separator + "sbidler.ini");

	private Properties properties;

	public void loadConfiguration() throws IllegalArgumentException, FileNotFoundException, IOException {
		if (CONFIG_FILE.createNewFile()) {
			properties.store(new FileOutputStream(CONFIG_FILE), "Configuration file for SkyBlock Idler.");
		} else {
			properties.load(new FileInputStream(CONFIG_FILE));
			this.setTimeout(Integer.parseInt(properties.getProperty("afk-timeout")));
			this.setFps(Integer.parseInt(properties.getProperty("afk-fps")));
			this.warpInterval(Integer.parseInt(properties.getProperty("warp-interval")));
		}
	}

	public void saveConfiguration() throws FileNotFoundException, IOException {
		CONFIG_FILE.createNewFile();
		properties.store(new FileOutputStream(CONFIG_FILE), "Configuration file for SkyBlock Idler.");
	}

	// AFK Detection Component

	private int timeout;
	private boolean afk;
	private final Task afk_timer;

	public boolean isAfk() {
		return afk;
	}

	public int getTimeout() {
		return timeout;
	}

	public void setTimeout(int timeout) {
		setTimeout0(timeout);
		properties.setProperty("afk-timeout", Integer.toString(timeout));
		int real = timeout * 20;
		int exe = scheduler.getExecutingTick(afk_timer);
		if (exe != -1) {
			int past = afk_timer.getTickDelay() - scheduler.getExecutingTick(afk_timer) + scheduler.getCurrentTick();
			if (past >= real)
				inactive();
			else {
				afk_timer.setTickDelay(real - past);
				scheduler.register(afk_timer);
			}
		}
		afk_timer.setTickDelay(real);
	}

	private void setTimeout0(int timeout) {
		if (timeout < 1)
			throw new IllegalArgumentException("timeout < 1");
		this.timeout = timeout;
	}

	public void active() {
		scheduler.register(afk_timer);
		if (isAfk())
			active0();
	}

	protected void active0() {
		MinecraftForge.EVENT_BUS.post(new PlayerActiveEvent());
		afk = false;
		Minecraft mc = getMinecraft();
		EntityPlayerSP player = mc.thePlayer;

		// FPS Changer Component

		mc.gameSettings.limitFramerate = activeFps;

		if (player != null) {
			IChatComponent msg = new ChatComponentText("You are no longer AFK.");
			msg.getChatStyle().setColor(EnumChatFormatting.GRAY);
			player.addChatMessage(PREFIX.createCopy().appendSibling(msg));

			// Auto-warp Component

			LocalDateTime vipOnly = vipOnlyTime();
			LocalDateTime down = downTime();
			if (vipOnly != null) {
				player.addChatMessage(BLANK);
				player.addChatMessage(LINE);
				IChatComponent report = new ChatComponentText(" SkyBlock Idler Report");
				report.getChatStyle().setColor(EnumChatFormatting.GOLD);
				player.addChatMessage(report);
				IChatComponent topic = new ChatComponentText(" We have detected a ");
				topic.getChatStyle().setColor(EnumChatFormatting.GRAY);
				IChatComponent vip = new ChatComponentText("VIP");
				vip.getChatStyle().setColor(EnumChatFormatting.GREEN);
				topic.appendSibling(vip).appendText(" requirement to play SkyBlock.");
				player.addChatMessage(topic);
				player.addChatMessage(BLANK);
				IChatComponent datepre = new ChatComponentText("    Date: ");
				datepre.getChatStyle().setColor(EnumChatFormatting.YELLOW);
				IChatComponent date = new ChatComponentText(vipOnly.toLocalDate().toString());
				date.getChatStyle().setColor(EnumChatFormatting.WHITE);
				datepre.appendSibling(date);
				player.addChatMessage(datepre);
				IChatComponent timepre = new ChatComponentText("    Time: ");
				timepre.getChatStyle().setColor(EnumChatFormatting.YELLOW);
				IChatComponent time = new ChatComponentText(vipOnly.toLocalTime().toString());
				time.getChatStyle().setColor(EnumChatFormatting.WHITE);
				timepre.appendSibling(time);
				player.addChatMessage(timepre);
				IChatComponent attemptpre = new ChatComponentText("    Attempts: ");
				attemptpre.getChatStyle().setColor(EnumChatFormatting.YELLOW);
				IChatComponent attempt = new ChatComponentText(Integer.toString(attempts));
				attempt.getChatStyle().setColor(succeed ? EnumChatFormatting.GREEN : EnumChatFormatting.RED);
				attemptpre.appendSibling(attempt);
				player.addChatMessage(attemptpre);
				player.addChatMessage(BLANK);
				IChatComponent joined = new ChatComponentText(succeed ? " Fortunately, we managed to join it."
						: " Unfortunately, we did not manage to join it.");
				joined.getChatStyle().setColor(EnumChatFormatting.GRAY);
				player.addChatMessage(joined);
				player.addChatMessage(LINE);
				vipOnly = null;
				attempts = 0;
				succeed = false;
			}
			if (down != null) {
				player.addChatMessage(BLANK);
				player.addChatMessage(LINE);
				IChatComponent report = new ChatComponentText(" SkyBlock Idler Report");
				report.getChatStyle().setColor(EnumChatFormatting.GOLD);
				player.addChatMessage(report);
				IChatComponent topic = new ChatComponentText(" We have detected SkyBlock being under maintenance.");
				topic.getChatStyle().setColor(EnumChatFormatting.GRAY);
				player.addChatMessage(topic);
				player.addChatMessage(BLANK);
				IChatComponent datepre = new ChatComponentText("    Date: ");
				datepre.getChatStyle().setColor(EnumChatFormatting.YELLOW);
				IChatComponent date = new ChatComponentText(down.toLocalDate().toString());
				date.getChatStyle().setColor(EnumChatFormatting.WHITE);
				datepre.appendSibling(date);
				player.addChatMessage(datepre);
				IChatComponent timepre = new ChatComponentText("    Time: ");
				timepre.getChatStyle().setColor(EnumChatFormatting.YELLOW);
				IChatComponent time = new ChatComponentText(down.toLocalTime().toString());
				time.getChatStyle().setColor(EnumChatFormatting.WHITE);
				timepre.appendSibling(time);
				player.addChatMessage(timepre);
				IChatComponent attemptpre = new ChatComponentText("    Attempts: ");
				attemptpre.getChatStyle().setColor(EnumChatFormatting.YELLOW);
				IChatComponent attempt = new ChatComponentText(Integer.toString(attempts));
				attempt.getChatStyle().setColor(succeed ? EnumChatFormatting.GREEN : EnumChatFormatting.RED);
				attemptpre.appendSibling(attempt);
				player.addChatMessage(attemptpre);
				player.addChatMessage(BLANK);
				IChatComponent joined = new ChatComponentText(succeed ? " Fortunately, we managed to join it."
						: " Unfortunately, we did not manage to join it.");
				joined.getChatStyle().setColor(EnumChatFormatting.GRAY);
				player.addChatMessage(joined);
				player.addChatMessage(LINE);
				down = null;
				attempts = 0;
				succeed = false;
			}
		}
	}

	public void inactive() {
		if (!isAfk())
			inactive0();
	}

	protected void inactive0() {
		MinecraftForge.EVENT_BUS.post(new PlayerInactiveEvent());
		afk = true;
		EntityPlayerSP player = getMinecraft().thePlayer;
		if (player != null) {
			IChatComponent msg = new ChatComponentText("You are now AFK.");
			msg.getChatStyle().setColor(EnumChatFormatting.GRAY);
			player.addChatMessage(PREFIX.createCopy().appendSibling(msg));
		}

		// FPS Changer Component

		applyFps();
	}

	public static class PlayerActiveEvent extends Event {
	}

	public static class PlayerInactiveEvent extends Event {
	}

	@SubscribeEvent
	public void onInput(InputEvent event) {
		active();
	}

	@SubscribeEvent
	public void onMouseInput(MouseInputEvent event) {
		active();
	}

	@SubscribeEvent
	public void onKeyboardInput(KeyboardInputEvent event) throws IllegalArgumentException, IllegalAccessException {
		active();
		GuiScreen gui = event.gui;
		int key = Keyboard.getEventKey();
		if (event instanceof KeyboardInputEvent.Pre && gui instanceof GuiChat
				&& (key == Keyboard.KEY_NUMPADENTER || key == Keyboard.KEY_RETURN)) {
			Field field = null;
			for (Field f : gui.getClass().getDeclaredFields())
				if (GuiTextField.class.isAssignableFrom(f.getType()))
					field = f;
			field.setAccessible(true);
			this.cmd = ((GuiTextField) field.get(gui)).getText();

		}
	}

	// Auto-reconnect Component

	private static final int WHITE_RGB = Color.WHITE.getRGB();

	private final Task reconnect_timer=new Task(20,true,false){

	@Override public void run(){reconnect--;GuiScreen gui=getMinecraft().currentScreen;if(gui instanceof GuiDisconnected)triggerDraw(gui);}

	};

	private transient ServerData server;
	private int reconnect;

	public ServerData getServer() {
		return server;
	}

	private void triggerDraw(GuiScreen gui) {
		gui.drawCenteredString(getMinecraft().fontRendererObj, "", 0, 0, WHITE_RGB);
	}

	@SubscribeEvent
	public void onGuiOpen(GuiOpenEvent event) {
		GuiScreen gui = event.gui;
		if (gui instanceof GuiDisconnected) {
			ServerData server = getMinecraft().getCurrentServerData();
			if (server != null)
				this.server = server;
			reconnect = 10;
			scheduler.register(reconnect_timer);
			triggerDraw(gui);
		}
	}

	@SubscribeEvent
	public void onClientConnectedToServer(ClientConnectedToServerEvent event) {
		server = getMinecraft().getCurrentServerData();
		getScheduler().unregister(reconnect_timer);
	}

	@SubscribeEvent
	public void onDrawScreen(DrawScreenEvent event) {
		GuiScreen gui = event.gui;
		if (gui instanceof GuiDisconnected) {
			Minecraft mc = getMinecraft();
			if (reconnect == 0) {
				getScheduler().register(reconnect_timer);
				ServerData server = getServer();
				mc.displayGuiScreen(
						server == null ? new GuiMainMenu() : new GuiConnecting(new GuiMainMenu(), mc, server));
			} else {
				gui.drawCenteredString(mc.fontRendererObj,
						"Reconnecting in " + reconnect + " second" + (reconnect > 1 ? 's' : ""), event.gui.width / 2,
						30, WHITE_RGB);
			}
		}
	}

	// Auto-warp Component

	private static final Task[] CMDS = new Task[] { new CmdTask("/map"), new CmdTask("/whereami"),
			new CmdTask("/warp home"), new CmdTask("/l"), new CmdTask("/play sb", true), new CmdTask("/play sb"),
			new CmdTask("/warp home", true) };

	private int warpInt;
	private boolean succeed;
	private boolean warn;

	private final Task warp_timer;

	public boolean isWarp() {
		return warpInterval() >= 1;
	}

	public int warpInterval() {
		return warpInt;
	}

	public boolean warpInterval(int warpInt) {
		boolean val = warpInterval0(warpInt);
		properties.setProperty("warp-interval", Integer.toString(warpInt));
		if (!val) {
			scheduler.unregister(warp_timer);
			return val;
		}
		int real = warpInt * 20;
		int exe = scheduler.getExecutingTick(warp_timer);
		if (exe != -1) {
			int past = warp_timer.getTickDelay() - scheduler.getExecutingTick(warp_timer) + scheduler.getCurrentTick();
			if (past >= real)
				warp();
			else {
				warp_timer.setTickDelay(real - past);
				scheduler.register(warp_timer);
			}
		}
		warp_timer.setTickDelay(real);
		return val;
	}
	private boolean warpInterval0(int warpInt) {
		if (warpInt < -1)
			warpInt = -1;
		this.warpInt = warpInt;
		return warpInt != -1;
	}

	private void warp() {
		if (!(isAfk() && isWarp()))
			return;
		scheduler.register(CMDS[3]);
		attempts++;
	}

	private static class CmdTask extends Task {

		private String cmd;

		public CmdTask(String cmd) {
			this(cmd, false);
		}

		public CmdTask(String cmd, boolean wait) {
			super(wait ? 100 : 20, false, false);
			this.cmd = cmd;
		}

		@Override
		public void run() {
			getMinecraft().thePlayer.sendChatMessage(cmd);
		}

	}

	@SubscribeEvent
	public void onActionPerformed(ActionPerformedEvent.Post event) {
		GuiButton button = event.button;
		Minecraft mc = getMinecraft();
		GameSettings settings = mc.gameSettings;
		EntityPlayerSP player = mc.thePlayer;
		if (!warn && isWarp() && button instanceof GuiOptionButton
				&& ((GuiOptionButton) button).returnEnumOptions() == Options.CHAT_VISIBILITY
				&& settings.chatVisibility == EnumChatVisibility.HIDDEN) {
			player.closeScreen();
			settings.setOptionValue(Options.CHAT_VISIBILITY, 1);
			IChatComponent autowarp = new ChatComponentText("Auto Warp");
			autowarp.getChatStyle().setColor(EnumChatFormatting.AQUA);
			player.addChatMessage(PREFIX.createCopy().appendSibling(autowarp).appendText(" requires visible chat to function."));
			ChatComponentText hide = new ChatComponentText("Click here");
			hide.getChatStyle().setChatClickEvent(new ClickEvent(Action.RUN_COMMAND, "/sbi hidechat"))
					.setColor(EnumChatFormatting.YELLOW);
			player.addChatMessage(
					PREFIX.createCopy().appendSibling(hide).appendText(" to hide the chat and suppress the warning."));
		}
	}

	@SubscribeEvent
	public void onEntityJoinWorld(EntityJoinWorldEvent event) {
		if (getMinecraft().thePlayer == event.entity && isAfk() && isWarp())
			scheduler.register(CMDS[0]);
	}

	private LocalDateTime vipOnly;
	private LocalDateTime down;
	private int attempts;

	public boolean isVipOnly() {
		return vipOnlyTime() != null;
	}

	public LocalDateTime vipOnlyTime() {
		return vipOnly;
	}

	public boolean isDown() {
		return downTime() != null;
	}

	public LocalDateTime downTime() {
		return down;
	}

	// FPS Changer Component

	private int fps;
	private int activeFps;

	public int getFps() {
		return fps;
	}

	public boolean setFps(int fps) {
		boolean val = setFps0(fps);
		properties.setProperty("afk-fps", Integer.toString(fps));
		if (isAfk())
			applyFps();
		return val;
	}

	private boolean setFps0(int fps) {
		if (fps < 0)
			fps = 0;
		this.fps = fps;
		return fps != 0;
	}

	public int getActiveFps() {
		return activeFps;
	}

	private void applyFps() {
		if (fps < 1)
			return;
		GameSettings settings = getMinecraft().gameSettings;
		activeFps = settings.limitFramerate;
		settings.limitFramerate = fps;
	}

	// Chat Handling Component

	public static final IChatComponent PREFIX;
	public static final IChatComponent LINE;
	public static final IChatComponent BLANK;

	private String cmd;

	private void sendTpWarning(int sec) {
		IChatComponent num = new ChatComponentText(Integer.toString(sec));
		EnumChatFormatting color = null;
		switch (sec) {
		case 2:
			color = EnumChatFormatting.YELLOW;
			break;
		case 1:
			color = EnumChatFormatting.RED;
			break;
		default:
			color = EnumChatFormatting.GREEN;
		}
		num.getChatStyle().setColor(color);
		getMinecraft().thePlayer.addChatMessage(PREFIX.createCopy().appendText("Teleporting to your island in ")
				.appendSibling(num).appendText(" second" + (sec == 1 ? "." : "s.")));
	}

	@SubscribeEvent
	public void onClientChatReceived(ClientChatReceivedEvent event) {
		Minecraft mc = getMinecraft();
		EntityPlayerSP player = mc.thePlayer;
		String msg = event.message.getUnformattedText();
		String cmd = this.cmd;
		this.cmd = null;
		if (cmd == null) {
			event.setCanceled(true);
			switch (msg) {

			// Start of AutoWarp Component

			case "You are currently playing on Private World":
				if (isVipOnly() || isDown())
					succeed = true;
				break;
			case "This command is not available on this server!":
				if (isWarp() && isAfk()) {
					scheduler.register(CMDS[1]);
					sendTpWarning(2);
				}
				break;
			case "You are currently playing on Hub":
			case "You are already playing SkyBlock!":
				if (isWarp() && isAfk()) {
					scheduler.register(CMDS[2]);
					sendTpWarning(1);
				}
				break;
			case "You are trying to do that too fast. Try again in a moment.":
			case "There was a problem joining SkyBlock. try again in a moment!":
			case "Oops! Couldn't find a SkyBlock server for you! Try again later":
			case "You were kicked while joining that server!":
				if (isWarp() && isAfk()) {
					scheduler.register(CMDS[4]);
					sendTpWarning(5);
				}
				break;
			case "You are AFK. Move around to return from AFK.":
			case "You were spawned in limbo.":
				if (isWarp() && isAfk()) {
					scheduler.register(CMDS[3]);
					sendTpWarning(4);
				}
				break;
			case "Cannot send chat message":
				if (isAfk())
					if (mc.gameSettings.chatVisibility != EnumChatVisibility.HIDDEN && isWarp()) {
						scheduler.register(CMDS[3]);
						sendTpWarning(4);
					} else
						event.setCanceled(false);
				break;

			// Start of Maintenance Component

			case "SkyBlock is currently under maintenance!":
				if (!isDown()) {
					down = LocalDateTime.now();
					player.addChatMessage(PREFIX.createCopy().appendText("SkyBlock is currently under maintennace!"));
					if (isWarp()) {
						scheduler.register(warp_timer);
						sendTpWarning(warpInterval());
					}
				}
				break;
			case "SkyBlock is currently available to VIP and above! You can access the store at https://store.hypixel.net":
				if (!isVipOnly()) {
					vipOnly = LocalDateTime.now();
					IChatComponent pre = PREFIX.createCopy().appendText("SkyBlock is currently available to ");
					IChatComponent mid = new ChatComponentText("VIP");
					mid.getChatStyle().setColor(EnumChatFormatting.GREEN);
					player.addChatMessage(pre.appendSibling(mid).appendText(" or above!"));
					if (isWarp()) {
						scheduler.register(warp_timer);
						sendTpWarning(warpInterval());
					}
				}
				break;

			default:
				// Start of AutoWarp Component

				if (msg.startsWith("You are currently connected to server ") && msg.contains("lobby") && isWarp()) {
					if (isAfk()) {
						scheduler.register(CMDS[5]);
						sendTpWarning(1);
					}
				} else if (msg.startsWith("Couldn't warp you! Try again later.") && isWarp()) {
					if (isAfk()) {
						scheduler.register(CMDS[6]);
						sendTpWarning(5);
					}
				} else
					event.setCanceled(false);
				break;
			}
		}
	}

	// Command Component

	private static final List<String> ALIASES = ImmutableList.of("sbidler", "si");
	private static final IChatComponent HIVEN;
	private static final IChatComponent INS_CMD;
	private static final IChatComponent DISABLE;

	static {
		IChatComponent sb = new ChatComponentText("SB");
		IChatComponent i = new ChatComponentText("I ");
		IChatComponent arrow = new ChatComponentText("> ");
		sb.getChatStyle().setColor(EnumChatFormatting.YELLOW);
		i.getChatStyle().setColor(EnumChatFormatting.GOLD);
		arrow.getChatStyle().setColor(EnumChatFormatting.DARK_GRAY);
		PREFIX = new ChatComponentText("");
		PREFIX.getChatStyle().setColor(EnumChatFormatting.WHITE);
		PREFIX.appendSibling(sb).appendSibling(i).appendSibling(arrow);
		LINE = new ChatComponentText("-----------------------------------------------------");
		LINE.getChatStyle().setColor(EnumChatFormatting.DARK_GRAY);
		BLANK = new ChatComponentText("");
		HIVEN = new ChatComponentText(" - ");
		HIVEN.getChatStyle().setColor(EnumChatFormatting.DARK_GRAY);
		INS_CMD = new ChatComponentText("Click to insert the command!");
		INS_CMD.getChatStyle().setColor(EnumChatFormatting.AQUA);
		DISABLE = new ChatComponentText(" (Can be disabled)");
		DISABLE.getChatStyle().setColor(EnumChatFormatting.GRAY);
	}

	private void sendCmdUsage(ICommandSender sender, String cmd, String usage) {
		IChatComponent cmdcompo = new ChatComponentText(" " + cmd);
		IChatComponent click = INS_CMD.createCopy();
		cmdcompo.getChatStyle().setColor(EnumChatFormatting.YELLOW)
				.setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, INS_CMD))
				.setChatClickEvent(new ClickEvent(Action.SUGGEST_COMMAND, cmd));
		IChatComponent usagecompo = new ChatComponentText(usage.replaceAll("\\[.+\\]", ""));
		usagecompo.getChatStyle().setColor(EnumChatFormatting.WHITE);
		sender.addChatMessage(cmdcompo.appendSibling(HIVEN).appendSibling(usagecompo));
	}

	private void sendFeature(ICommandSender sender, boolean disable, String name, String... desc) {
		IChatComponent[] desc1 = new IChatComponent[desc.length];
		for (int i = 0; i < desc.length; i++)
			desc1[i] = new ChatComponentText(desc[i]);
		sendFeature(sender, disable, name, desc1);
	}

	private void sendFeature(ICommandSender sender, boolean disable, String name, IChatComponent... desc) {
		sender.addChatMessage(BLANK);
		IChatComponent title = new ChatComponentText(" " + name);
		title.getChatStyle().setColor(EnumChatFormatting.AQUA);
		if (disable)
			title.appendSibling(DISABLE);
		sender.addChatMessage(title);
		for (IChatComponent s : desc)
			sender.addChatMessage(s);
	}

	@Override
	public String getCommandName() {
		return "sbi";
	}

	@Override
	public List<String> getCommandAliases() {
		return ALIASES;
	}

	@Override
	public String getCommandUsage(ICommandSender sender) {
		return "Main command of SkyBlock Idler";
	}

	@Override
	public int getRequiredPermissionLevel() {
		return 0;
	}

	private void displayCmdList(ICommandSender sender) {
		sender.addChatMessage(BLANK);
		sender.addChatMessage(LINE);
		IChatComponent title = new ChatComponentText(" SkyBlock Idler Commands");
		title.getChatStyle().setColor(EnumChatFormatting.GOLD);
		sender.addChatMessage(title);
		sender.addChatMessage(BLANK);
		sendCmdUsage(sender, "/sbi features", "Displays the features of SkyBlock Idler.");
		sendCmdUsage(sender, "/sbi help", "Displays this command list for SkyBlock Idler.");
		sendCmdUsage(sender, "/sbi info", "Displays the version of this SkyBlock Idler.");
		sendCmdUsage(sender, "/sbi load", "Loads the configuration from sbidler.ini.");
		sendCmdUsage(sender, "/sbi save", "Saves the configuration to sbidler.ini.");
		sendCmdUsage(sender, "/sbi afk-timeout", "Shows the current AFK timeout.");
		sendCmdUsage(sender, "/sbi afk-timeout [secs]", "Sets the AFK timeout.");
		sendCmdUsage(sender, "/sbi warp-int", "Shows the current warp attempt interval.");
		sendCmdUsage(sender, "/sbi warp-int [secs/-1]", "Sets the warp attempt interval, or disables it.");
		sendCmdUsage(sender, "/sbi afk-fps", "Shows the current FPS for AFK.");
		sendCmdUsage(sender, "/sbi afk-fps [fps/-1]", "Sets the FPS for AFK, or disables it.");
		sendCmdUsage(sender, "/sbi hidechat", "Hides the chat and suppresses warning temporarily.");
		sender.addChatMessage(LINE);
	}

	@Override
	public void processCommand(ICommandSender sender, String[] args) throws CommandException {
		if (args.length == 0) {
			displayCmdList(sender);
			return;
		}
		switch (args[0].toLowerCase()) {
		case "features":
			sender.addChatMessage(BLANK);
			sender.addChatMessage(LINE);
			IChatComponent title = new ChatComponentText(" SkyBlock Idler Features");
			title.getChatStyle().setColor(EnumChatFormatting.GOLD);
			sender.addChatMessage(title);
			sendFeature(sender, false, "AFK Detector", " This mod includes an AFK detector based on keyboard and",
					" mouse inputs. When there are no more inputs, you will be",
					" considered as AFK after a configurable period of time.");
			sendFeature(sender, true, "Auto Warp", " When AFK, this mod will automatically warp you back to your",
					" private SkyBlock island when you got teleported to another",
					" world. There is a configurable time interval for each attempt.",
					" If more than one attempt has done, there will be a report", " when you are back.");
			IChatComponent autologin = new ChatComponentText(" When disconnected, this mod will log in within ");
			IChatComponent ten = new ChatComponentText("10");
			ten.getChatStyle().setColor(EnumChatFormatting.YELLOW);
			autologin.appendSibling(ten).appendText(" seconds");
			sendFeature(sender, false, "Auto Login", autologin, new ChatComponentText(" automatically."));
			sendFeature(sender, true, "FPS Changer", " When AFK, this mod will automatically set the FPS to a",
					" configurable value to save power. The FPS will be set back", " to normal when you are back.");
			IChatComponent undetectable = new ChatComponentText(" This mod is ");
			IChatComponent one = new ChatComponentText("100%");
			one.getChatStyle().setColor(EnumChatFormatting.YELLOW);
			undetectable.appendSibling(one).appendText(" undetectable due to its mechanics.");
			sendFeature(sender, false, "Undetectable", undetectable);
			IChatComponent config = new ChatComponentText(" The configuration file ");
			IChatComponent name = new ChatComponentText("sbidler.ini");
			name.getChatStyle().setColor(EnumChatFormatting.YELLOW);
			config.appendSibling(name).appendText(" stores the configuration of");
			sendFeature(sender, false, "Configuration File", config,
					new ChatComponentText(" this mod permanently. It also allows you to configure outside"),
					new ChatComponentText(" of Minecraft."));
			sender.addChatMessage(LINE);
			break;
		case "help":
			displayCmdList(sender);
			break;
		case "info":
			sender.addChatMessage(BLANK);
			sender.addChatMessage(LINE);
			IChatComponent title1 = new ChatComponentText(" SkyBlock Idler Info");
			title1.getChatStyle().setColor(EnumChatFormatting.GOLD);
			sender.addChatMessage(title1);
			sender.addChatMessage(BLANK);
			IChatComponent id = new ChatComponentText(" ID:");
			id.getChatStyle().setColor(EnumChatFormatting.YELLOW);
			IChatComponent idval = new ChatComponentText(" sbidler");
			idval.getChatStyle().setColor(EnumChatFormatting.WHITE);
			sender.addChatMessage(id.appendSibling(idval));
			IChatComponent dev = new ChatComponentText(" Developer:");
			dev.getChatStyle().setColor(EnumChatFormatting.YELLOW);
			IChatComponent devval = new ChatComponentText(" Codemetry");
			devval.getChatStyle().setColor(EnumChatFormatting.WHITE);
			sender.addChatMessage(dev.appendSibling(devval));
			IChatComponent ver = new ChatComponentText(" Version:");
			ver.getChatStyle().setColor(EnumChatFormatting.YELLOW);
			IChatComponent verval = new ChatComponentText(" 1.1");
			verval.getChatStyle().setColor(EnumChatFormatting.WHITE);
			sender.addChatMessage(ver.appendSibling(verval));
			sender.addChatMessage(LINE);
			break;
		case "load":
			try {
				this.loadConfiguration();
			} catch (IllegalArgumentException e) {
				sender.addChatMessage(PREFIX.createCopy().appendText("An error occured while attempting to parse sbidler.ini."));
				sender.addChatMessage(PREFIX.createCopy().appendText("sbidler.ini contains properties with wrong type of values."));
				break;
			} catch (IOException e) {
				sender.addChatMessage(PREFIX.createCopy().appendText("An unknown I/O error occurred while attempting to read sbidler.ini."));
				break;
			}
			sender.addChatMessage(PREFIX.createCopy().appendText("Configuration is successfully loaded."));
			break;
		case "save":
			try {
				this.saveConfiguration();
			} catch (IOException e) {
				sender.addChatMessage(PREFIX.createCopy().appendText("An unknown I/O error occurred while attempting to read sbidler.ini."));
				break;
			}
			sender.addChatMessage(PREFIX.createCopy().appendText("Configuration is successfully saved."));
			break;
		case "afk-timeout":
			if (args.length < 2) {
				IChatComponent val = new ChatComponentText(Integer.toString(this.getTimeout()));
				val.getChatStyle().setColor(EnumChatFormatting.YELLOW);
				sender.addChatMessage(PREFIX.createCopy().appendText("AFK timeout is currently set to ").appendSibling(val).appendText(" seconds."));
				break;
			}
			try {
				this.setTimeout(Integer.parseInt(args[1]));
			} catch (IllegalArgumentException e) {
				sender.addChatMessage(PREFIX.createCopy().appendText("AFK timeout must be an integer and > 0."));
				break;
			}
			IChatComponent afkval = new ChatComponentText(args[1]);
			afkval.getChatStyle().setColor(EnumChatFormatting.YELLOW);
			sender.addChatMessage(PREFIX.createCopy().appendText("AFK timeout is set to ").appendSibling(afkval).appendText(" seconds."));
			break;
		case "warp-int":
			if (args.length < 2) {
				int warp = this.warpInterval();
				if (warp == -1) {
					IChatComponent autowarp = new ChatComponentText("Auto Warp");
					autowarp.getChatStyle().setColor(EnumChatFormatting.AQUA);
					sender.addChatMessage(PREFIX.createCopy().appendSibling(autowarp).appendText(" is currently disabled."));
				} else {
					IChatComponent val = new ChatComponentText(Integer.toString(warp));
					val.getChatStyle().setColor(EnumChatFormatting.YELLOW);
					sender.addChatMessage(PREFIX.createCopy().appendText("Warp attempt interval is currently set to ").appendSibling(val).appendText("."));
				}
				break;
			}
			try {
				if (this.warpInterval(Integer.parseInt(args[1]))) {
					IChatComponent warpval = new ChatComponentText(args[1]);
					warpval.getChatStyle().setColor(EnumChatFormatting.YELLOW);
					sender.addChatMessage(PREFIX.createCopy().appendText("Warp attempt interval is set to ").appendSibling(warpval).appendText("."));
				} else {
					IChatComponent autowarp = new ChatComponentText("Auto Warp");
					autowarp.getChatStyle().setColor(EnumChatFormatting.AQUA);
					sender.addChatMessage(PREFIX.createCopy().appendSibling(autowarp).appendText(" is disabled."));
				}
			} catch (IllegalArgumentException e) {
				sender.addChatMessage(PREFIX.createCopy().appendText("Warp attempt interval must be an integer."));
			}
			break;
		case "afk-fps":
			if (args.length < 2) {
				int fps = this.getFps();
				if (fps == 0) {
					IChatComponent changer = new ChatComponentText("FPS Changer");
					changer.getChatStyle().setColor(EnumChatFormatting.AQUA);
					sender.addChatMessage(PREFIX.createCopy().appendSibling(changer).appendText(" is currently disabled."));
				} else {
					IChatComponent val = new ChatComponentText(Integer.toString(fps));
					val.getChatStyle().setColor(EnumChatFormatting.YELLOW);
					sender.addChatMessage(PREFIX.createCopy().appendText("FPS for AFK is currently set to ").appendSibling(val).appendText("."));
				}
				break;
			}
			try {
				if (this.setFps(Integer.parseInt(args[1]))) {
					IChatComponent fpsval = new ChatComponentText(args[1]);
					fpsval.getChatStyle().setColor(EnumChatFormatting.YELLOW);
					sender.addChatMessage(PREFIX.createCopy().appendText("FPS for AFK is set to ").appendSibling(fpsval).appendText("."));
				} else {
					IChatComponent changer = new ChatComponentText("FPS Changer");
					changer.getChatStyle().setColor(EnumChatFormatting.AQUA);
					sender.addChatMessage(PREFIX.createCopy().appendSibling(changer).appendText(" is disabled."));
				}
			} catch (IllegalArgumentException e) {
				sender.addChatMessage(PREFIX.createCopy().appendText("FPS for AFK must be an integer."));
			}
			break;
		case "hidechat":
			if (warn) {
				sender.addChatMessage(PREFIX.createCopy().appendText("Chat is now hidden, warning is already suppressed."));
			} else {
				warn = true;
				sender.addChatMessage(
					PREFIX.createCopy().appendText("Chat is now hidden and warning will be suppressed."));
			}
			getMinecraft().gameSettings.setOptionValue(Options.CHAT_VISIBILITY, 2);
			if (sender instanceof EntityPlayerSP) 
				((EntityPlayerSP) sender).closeScreen();
			break;
		default:
			displayCmdList(sender);
			break;
		}
	}

}
