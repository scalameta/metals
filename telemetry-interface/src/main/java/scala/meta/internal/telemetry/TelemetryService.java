package scala.meta.internal.telemetry;

import scala.meta.internal.telemetry.ReportEvent;
import scala.meta.internal.telemetry.ServiceEndpoint;

public interface TelemetryService {

	void sendReportEvent(ReportEvent event);

	static final ServiceEndpoint<ReportEvent, Void> SendReportEventEndpoint = new ServiceEndpoint<>("POST",
			"/v1/telemetry/sendReportEvent", ReportEvent.class, Void.class);
}
