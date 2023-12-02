package scala.meta.internal.telemetry;

public interface TelemetryService {

	void sendErrorReport(ErrorReport report);

	static final ServiceEndpoint<ErrorReport, Void> SendErrorReportEndpoint = new ServiceEndpoint<>("POST",
			"/v1/telemetry/sendErrorReport", ErrorReport.class, Void.class);

	void sendCrashReport(CrashReport report);

	static final ServiceEndpoint<CrashReport, Void> SendCrashReportEndpoint = new ServiceEndpoint<>("POST",
			"/v1/telemetry/sendCrashReport", CrashReport.class, Void.class);
}
