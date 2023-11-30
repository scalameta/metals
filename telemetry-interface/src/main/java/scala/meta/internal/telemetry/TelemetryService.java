package scala.meta.internal.telemetry;

public interface TelemetryService {

	void sendReportEvent(ReportEvent event);

	static final ServiceEndpoint<ReportEvent, Void> SendReportEventEndpoint = new ServiceEndpoint<>("POST",
			"/v1/telemetry/sendReportEvent", ReportEvent.class, Void.class);
}
