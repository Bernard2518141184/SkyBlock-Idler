package me.codemetry.sbidler;

import java.awt.Color;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.util.Properties;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.GuiScreenEvent.DrawScreenEvent;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent.KeyInputEvent;

@Mod(modid = "sbidler", version = "1.0")
public class SkyBlockIdler extends CommandBase {

	private static final Task[] CMDS;

	static {
		CMDS = new Task[] { new CmdTask("/map"), new CmdTask("/whereami"), new CmdTask("/warp home"), new CmdTask("/l"),
				new CmdTask("/play sb", true), new CmdTask("/play sb"), new CmdTask("/warp home", true) };
	}

	private static final File CONFIG_FILE = new File("mods" + File.separator + "sbidler.ini");

	private final Task AFK_TIMER = new Task(60, false, false) {

		@Override
		public void run() {
			afk = true;
			mc.thePlayer.addChatMessage(new ChatComponentText("[SkyBlock Idler] You are now AFK."));
			if (afkfps > 0) {
				fps = mc.gameSettings.limitFramerate;
				mc.gameSettings.limitFramerate = afkfps;
			}
		}

	};

	private Task reconnect;
	private Minecraft mc;
	private Scheduler scheduler;
	private int count;
	private boolean afk;
	// private String cmd;
	private boolean vip;
	private LocalDateTime fix;
	private Properties config;
	private int fps;
	private ServerData server;

	private transient int timeout;
	private transient int afkfps;
	private transient boolean autowarp;

	@EventHandler
	public void preInit(FMLPreInitializationEvent event) throws URISyntaxException, FileNotFoundException, IOException {
		mc = Minecraft.getMinecraft();
		scheduler = new Scheduler();
		config = new Properties();
		config.setProperty("afk-timeout", "300");
		config.setProperty("afk-fps", "5");
		config.setProperty("autowarp", "true");
		timeout = 300;
		afkfps = 5;
		autowarp = true;
		load();
		MinecraftForge.EVENT_BUS.register(this);
		ClientCommandHandler.instance.registerCommand(this);
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try {
				save();
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			} catch (URISyntaxException e) {
			}
		}));
	}

	public void load() throws URISyntaxException, FileNotFoundException, IOException {
		if (CONFIG_FILE.createNewFile())
			try (OutputStream stream = new FileOutputStream(CONFIG_FILE)) {
				config.store(stream, "Configuration for SkyBlock Idler");
			}
		else
			try (InputStream stream = new FileInputStream(CONFIG_FILE)) {
				config.load(stream);
				updateAFKTimeout(Integer.parseInt(config.getProperty("afk-timeout")));
				updateAFKFps(Integer.parseInt(config.getProperty("afk-fps")));
				autowarp = Boolean.parseBoolean(config.getProperty("autowarp"));
			}

	}

	private void updateAFKTimeout(int timeout) {
		this.timeout = timeout;
		timeout *= 20;
		int exetick = scheduler.getExecutingTick(AFK_TIMER);
		if (exetick != -1) {
			AFK_TIMER.setTickDelay(
					Integer.max((exetick - AFK_TIMER.getTickDelay() + timeout) - scheduler.getCurrentTick(), 0));
			scheduler.register(AFK_TIMER);
		}
		AFK_TIMER.setTickDelay(timeout);
	}

	private void updateAFKFps(int afkfps) {
		this.afkfps = afkfps;
		if (afk && afkfps > 0) {
			fps = mc.gameSettings.limitFramerate;
			mc.gameSettings.limitFramerate = afkfps;
		}
	}

	public void save() throws URISyntaxException, FileNotFoundException, IOException {
		CONFIG_FILE.createNewFile();
		try (OutputStream stream = new FileOutputStream(CONFIG_FILE)) {
			config.store(stream, "Configuration for SkyBlock Idler");
		}
	}

	/* Auto-Reconnect */

	@SubscribeEvent
	public void onGuiOpen(GuiOpenEvent event) {
		GuiScreen gui = event.gui;
		if (gui instanceof GuiDisconnected) {
			if (server == null)
				server = mc.getCurrentServerData();
			count = 10;
			gui.drawCenteredString(mc.fontRendererObj, "", event.gui.width / 2, 30, Color.WHITE.getRGB());
			scheduler.register(reconnect == null ? reconnect = new Task(20, true, false) {

				@Override
				public void run() {
					count--;
					gui.drawCenteredString(mc.fontRendererObj, "", event.gui.width / 2, 30, Color.WHITE.getRGB());
				}

			} : reconnect);
		} else {
			if (gui instanceof GuiMultiplayer) {
				scheduler.unregister(AFK_TIMER);
				afk = false;
			}
			scheduler.unregister(reconnect);
		}
	}

	@SubscribeEvent
	public void onDrawScreen(DrawScreenEvent event) {
		GuiScreen gui = event.gui;
		if (gui instanceof GuiDisconnected) {
			if (count == 0) {
				scheduler.unregister(reconnect);
				mc.displayGuiScreen(
						server == null ? new GuiMainMenu() : new GuiConnecting(new GuiMainMenu(), mc, server));
			} else
				gui.drawCenteredString(mc.fontRendererObj,
						"Reconnecting in " + count + " second" + (count > 1 ? 's' : ""), event.gui.width / 2, 30,
						Color.WHITE.getRGB());
		}
	}

	/* AFK Detection Component */

	@SubscribeEvent
	public void onKeyInput(KeyInputEvent event) {
		active();
	}

	@SubscribeEvent
	public void onMouse(MouseEvent event) {
		active();
	}

	private void active() {
		scheduler.register(AFK_TIMER);
		if (afk) {
			afk = false;
			if (afkfps > 0)
				mc.gameSettings.limitFramerate = fps;
			EntityPlayerSP player = mc.thePlayer;
			player.addChatMessage(new ChatComponentText("[SkyBlock Idler] You are no longer AFK."));
			if (fix != null) {
				player.addChatMessage(new ChatComponentText(
						"[SkyBlock Idler] At " + fix.toLocalTime() + " on " + fix.toLocalDate() + ","));
				player.addChatMessage(
						new ChatComponentText("[SkyBlock Idler] SkyBlock was detected to be under maintenance."));
			}
			if (vip) {
				player.addChatMessage(new ChatComponentText(
						"[SkyBlock Idler] At " + fix.toLocalTime() + " on " + fix.toLocalDate() + ","));
				player.addChatMessage(
						new ChatComponentText("[SkyBlock Idler] SkyBlock was detected to be for VIP only."));
			}
		}
	}

	@SubscribeEvent
	public void onEntityJoinWorld(EntityJoinWorldEvent event) {
		EntityPlayerSP player = mc.thePlayer;
		if (player == event.entity && afk && autowarp)
			scheduler.register(CMDS[0]);
	}

	/*
	 * @SubscribeEvent(priority = EventPriority.LOWEST) public void
	 * onServerChat(ServerChatEvent event) { String msg = event.message; if
	 * (msg.startsWith("/")) cmd = msg; }
	 */

	/* Warp to Island Component */

	@SubscribeEvent
	public void onClientChatReceived(ClientChatReceivedEvent event) {
		EntityPlayerSP player = mc.thePlayer;
		String msg = event.message.getUnformattedText();
		if (!(afk && autowarp))
			return;
		/*
		 * switch (msg) { case
		 * "You are trying to do that too fast. Try again in a moment.": case
		 * "This command is not available on this server!": if (cmd != null) return;
		 * case "You are currently playing on Private World": case
		 * "You are currently playing on Hub": if ("/map".equals(cmd)) return; case
		 * "You are already playing SkyBlock!": case
		 * "There was a problem joining SkyBlock. try again in a moment!": if
		 * ("/play sb".equals(cmd)) return; case
		 * "Oops! Couldn't find a SkyBlock server for you! Try again later": case
		 * "You were kicked while joining that server!": if ("/play sb".equals(cmd) ||
		 * "/warp hub".equals(cmd) || "/warp home".equals(cmd)) return; }
		 */
		switch (msg) {
		case "You are currently playing on Private World":
			fix = null;
			break;
		case "This command is not available on this server!":
			scheduler.register(CMDS[1]);
			player.addChatMessage(
					new ChatComponentText("[SkyBlock Idler] Teleporting to your private island in 2 seconds."));
			break;
		case "You are currently playing on Hub":
		case "You are already playing SkyBlock!":
			fix = null;
			scheduler.register(CMDS[2]);
			player.addChatMessage(
					new ChatComponentText("[SkyBlock Idler] Teleporting to your private island in 1 second."));
			break;
		case "You are trying to do that too fast. Try again in a moment.":
		case "There was a problem joining SkyBlock. try again in a moment!":
		case "Oops! Couldn't find a SkyBlock server for you! Try again later":
		case "You were kicked while joining that server!":
			scheduler.register(CMDS[4]);
			player.addChatMessage(
					new ChatComponentText("[SkyBlock Idler] Teleporting to your private island in 5 seconds."));
			break;
		case "You are AFK. Move around to return from AFK.":
		case "You were spawned in limbo.":
			player.addChatMessage(
					new ChatComponentText("[SkyBlock Idler] Teleporting to your private island in 4 seconds."));
			scheduler.register(CMDS[3]);
			return;
		case "SkyBlock is currently under maintenance!":
			fix = LocalDateTime.now();
			break;
		case "SkyBlock is currently available to VIP and above! You can access the store at https://store.hypixel.net":
			vip = true;
			break;
		default:
			if (msg.startsWith("You are currently connected to server ") && msg.contains("lobby")) {
				scheduler.register(CMDS[5]);
				player.addChatMessage(
						new ChatComponentText("[SkyBlock Idler] Teleporting to your private island in 1 second."));
			} else if (msg.startsWith("Couldn't warp you! Try again later.")) {
				scheduler.register(CMDS[6]);
				player.addChatMessage(
						new ChatComponentText("[SkyBlock Idler] Teleporting to your private island in 5 seconds."));
			} else
				return;
			break;
		}
		event.setCanceled(true);
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
			Minecraft.getMinecraft().thePlayer.sendChatMessage(cmd);
		}

	}

	@Override
	public String getCommandName() {
		return "sbidler";
	}

	@Override
	public String getCommandUsage(ICommandSender sender) {
		return "Main command of SkyBlock Idler";
	}

	@Override
	public int getRequiredPermissionLevel() {
		return 0;
	}

	@Override
	public void processCommand(ICommandSender sender, String[] args) throws CommandException {
		if (args.length == 0) {
			sender.addChatMessage(new ChatComponentText("Commands for SkyBlock Idler:"));
			sender.addChatMessage(new ChatComponentText("/sbidler - Shows this command list."));
			sender.addChatMessage(new ChatComponentText("/sbidler load - Loads the configuration from sbidler.ini."));
			sender.addChatMessage(new ChatComponentText("/sbidler save - Saves the configuration to sbidler.ini."));
			sender.addChatMessage(new ChatComponentText("/sbidler autowarp - Toggles the auto warp functionality."));
			sender.addChatMessage(new ChatComponentText("/sbidler afk-timeout - Shows the current AFK timeout."));
			sender.addChatMessage(new ChatComponentText("/sbidler afk-timeout [seconds] - Sets the AFK timeout."));
			sender.addChatMessage(new ChatComponentText("/sbidler afk-fps - Shows the current FPS for AFK."));
			sender.addChatMessage(new ChatComponentText("/sbidler afk-fps [fps] - Sets the FPS for AFK."));
			return;
		}
		switch (args[0]) {
		case "load":
			try {
				load();
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			} catch (URISyntaxException e) {
			}
			sender.addChatMessage(new ChatComponentText("[SkyBlock Idler] Configuration is successfully loaded."));
			break;
		case "save":
			try {
				save();
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			} catch (URISyntaxException e) {
			}
			sender.addChatMessage(new ChatComponentText("[SkyBlock Idler] Configuration is successfully saved."));
			break;
		case "afk-timeout":
			if (args.length < 2) {
				if (!autowarp)
					sender.addChatMessage(new ChatComponentText("[SkyBlock Idler] Auto warp is currently disabled."));
				else
					sender.addChatMessage(new ChatComponentText("[SkyBlock Idler] AFK timeout is currently set to "
							+ config.getProperty("afk-timeout") + " seconds."));
				break;
			}
			try {
				updateAFKTimeout(Integer.parseInt(args[1]));
				config.setProperty("afk-timeout", args[1]);
				sender.addChatMessage(
						new ChatComponentText("[SkyBlock Idler] AFK timeout is set to " + args[1] + " seconds."));
			} catch (NumberFormatException e) {
				sender.addChatMessage(new ChatComponentText(
						"[SkyBlock Idler] Only integers are accepted while setting AFK timeout."));
			}
			break;
		case "afk-fps":
			if (args.length < 2) {
				if (afkfps < 1)
					sender.addChatMessage(new ChatComponentText("[SkyBlock Idler] FPS for AFK is currently disabled."));
				else
					sender.addChatMessage(new ChatComponentText(
							"[SkyBlock Idler] FPS for AFK is currently set to " + config.getProperty("afk-fps") + "."));
				break;
			}
			try {
				updateAFKFps(Integer.parseInt(args[1]));
				config.setProperty("afk-fps", args[1]);
				if (afkfps < 1)
					sender.addChatMessage(new ChatComponentText("[SkyBlock Idler] FPS for AFK is disabled."));
				else
					sender.addChatMessage(
							new ChatComponentText("[SkyBlock Idler] FPS for AFK is set to " + args[1] + "."));
			} catch (NumberFormatException e) {
				sender.addChatMessage(new ChatComponentText(
						"[SkyBlock Idler] Only integers are accepted while setting FPS for AFK."));
			}
			break;
		case "autowarp":
			autowarp = !autowarp;
			config.setProperty("autowarp", Boolean.toString(autowarp));
			sender.addChatMessage(new ChatComponentText(
					"[SkyBlock Idler] Auto warp is " + (autowarp ? "enabled" : "disabled") + "."));
			break;
		default:
			sender.addChatMessage(new ChatComponentText("[SkyBlock Idler] Unknown command. Type /sbidler for help."));
			break;
		}
	}

}
