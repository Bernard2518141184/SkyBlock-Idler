package me.codemetry.sbidler;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;

public class CmdTask extends Task {

	private String cmd;

	/**
	 * Package internal constructor for convenience.
	 */
	CmdTask(String cmd, boolean wait) {
		this(cmd, wait ? 100 : 20, false);
	}

	public CmdTask(String cmd, int tickDelay, boolean repeating) {
		super(tickDelay, repeating, false);
		this.cmd = cmd;
	}

	/**
	 * 
	 * @return the command the task will send on behalf of the player.
	 */
	public String getCommand() {
		return cmd;
	}

	/**
	 * Sends the command on behalf of the player.
	 */
	@Override
	public void run() {
		EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
		if (player != null)
			player.sendChatMessage(cmd);
	}

}
