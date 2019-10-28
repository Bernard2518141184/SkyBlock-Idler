package me.codemetry.sbidler;

public abstract class Task {

	private int tickDelay;
	private boolean repeating;
	private boolean async;

	public Task(int tickDelay, boolean repeating, boolean async) {
		this.tickDelay = tickDelay;
		this.repeating = repeating;
		this.async = async;
	}

	public int getTickDelay() {
		return tickDelay;
	}

	// Once registered, tick delay modification will not affect the scheduler.
	public void setTickDelay(int tickDelay) {
		this.tickDelay = tickDelay;
	}

	public boolean isRepeating() {
		return repeating;
	}

	public void setRepeating(boolean repeating) {
		this.repeating = repeating;
	}

	public boolean isAsynchronous() {
		return async;
	}

	public void setAsynchronous(boolean async) {
		this.async = async;
	}

	// Do not call any methods of the scheduler this task is registered in that
	// modifies the task map, or ConcurrentModificationException will be thrown.
	public abstract void run();

}
