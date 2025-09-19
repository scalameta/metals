package scala.meta.infra;

/**
 * A MonitoringClient records telemetry data. Normally, these are metrics that
 * get ingested into something like Grafana to let you track usage of different
 * features or measure the production performance of different features.
 * 
 * Metals never calls your monitoring client directly. Instead, every method
 * call is wrapped through a NonBlockingMonitoringClient to ensure that
 * processing of telemetry data does not block core functionality like LSP
 * completions/diagnostics.
 */
public abstract class MonitoringClient {
	public abstract void recordUsage(Metric metric);

	public abstract void recordEvent(Event event);

	public abstract void shutdown();
}