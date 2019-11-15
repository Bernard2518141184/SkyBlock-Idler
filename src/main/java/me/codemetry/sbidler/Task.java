package me.codemetry.sbidler;

/**
 * Represents a task to be executed in the future. It must be registered in a
 * {@link Scheduler} to execute.
 * <p>
 * The tick delay commands the scheduler to execute the task after a certain
 * amount of ticks, {@code 0} is not supported. This task is repeatable, and can
 * be executed asynchronously from the thread the scheduler is running on.
 * However, both tick, repeating and asynchronous execution algorithm are
 * defined by the scheduler, so registering this task in different type of
 * schedulers may have different effects.
 */
public abstract class Task {

	private int tickDelay;
	private boolean repeating;
	private boolean async;

	public Task(int tickDelay, boolean repeating, boolean async) {
		valTickDelay(tickDelay);
		this.tickDelay = tickDelay;
		this.repeating = repeating;
		this.async = async;
	}

	/**
	 * 
	 * @return the tick delay/
	 */
	public int getTickDelay() {
		return tickDelay;
	}

	/**
	 * 
	 * @param tickDelay New tick delay.
	 */
	// Once registered, tick delay modification will not affect the scheduler.
	public void setTickDelay(int tickDelay) {
		valTickDelay(tickDelay);
		this.tickDelay = tickDelay;
	}

	/**
	 * Ensures tick delay is not set to 0.
	 */
	private void valTickDelay(int tickDelay) {
		if (tickDelay < 1)
			throw new IllegalArgumentException();
	}

	/**
	 * 
	 * @return Whether this task should repeat.
	 */
	public boolean isRepeating() {
		return repeating;
	}

	/**
	 * 
	 * @return Whether this task should execute asynchronously.
	 */
	public boolean isAsynchronous() {
		return async;
	}

	/**
	 * The execution content of this task.
	 * <p>
	 * This method can be invoked everywhere.
	 */
	// Do not call any methods of the scheduler this task is registered in that
	// modifies the task map, or ConcurrentModificationException will be thrown.
	public abstract void run();

}
