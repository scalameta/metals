package scala.meta.infra;

public abstract class MonitoringClient {
	public abstract void recordUsage(Metric metric);

	public abstract void recordEvent(Event event);

	public abstract void shutdown();
}