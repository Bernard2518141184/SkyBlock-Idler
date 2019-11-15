package me.codemetry.sbidler;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent.Phase;

public class Scheduler {

	private final Map<Task, Integer> tasks;
	private transient int tick;

	public Scheduler() {
		this.tasks = new HashMap<Task, Integer>();
		MinecraftForge.EVENT_BUS.register(this);
	}

	public int getCurrentTick() {
		return tick;
	}

	public int register(Task task) {
		if (task.getTickDelay() == 0) {
			if (task.isAsynchronous())
				new Thread(task::run).start();
			else
				task.run();
			if (task.isRepeating())
				throw new IllegalArgumentException();
		}
		Integer old = tasks.put(task, task.getTickDelay() + tick);
		return old == null ? -1 : old;
	}

	public int unregister(Task task) {
		Integer i = tasks.remove(task);
		return i == null ? -1 : i;
	}

	public boolean unregister(Task task, int executingTick) {
		return tasks.remove(task, executingTick);
	}

	public Set<Task> getScheduledTasks() {
		return tasks.keySet();
	}

	public Set<Task> getScheduledTasks(int executingTick) {
		return tasks.entrySet().stream().filter(entry -> entry.getValue() == executingTick).map(Entry::getKey)
				.collect(Collectors.toSet());
	}

	public int getExecutingTick(Task task) {
		Integer i = tasks.get(task);
		return i == null ? -1 : i;
	}

	public Set<Task> getRepeatingTasks() {
		return tasks.keySet().stream().filter(Task::isRepeating).collect(Collectors.toSet());
	}

	@SubscribeEvent
	public void onTick(ClientTickEvent event) {
		if (!(event.phase == Phase.START))
			return;
		if (!tasks.isEmpty()) {
			Map<Task, Integer> repeat = new HashMap<Task, Integer>();
			tick++;
			Iterator<Entry<Task, Integer>> itr = tasks.entrySet().iterator();
			while (itr.hasNext()) {
				Entry<Task, Integer> entry = itr.next();
				Task task = entry.getKey();
				if (entry.getValue() == tick) {
					if (task.isAsynchronous())
						new Thread(task::run).start();
					else
						task.run();
					if (tasks.size() == 1)
						tick = 0;
					if (task.isRepeating())
						repeat.put(task, task.getTickDelay() + tick);
					else
						itr.remove();
				}
			}
			tasks.putAll(repeat);
		}
	}

}
