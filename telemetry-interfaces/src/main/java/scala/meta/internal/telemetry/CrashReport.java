package scala.meta.internal.telemetry;

import java.util.Optional;

public class CrashReport {
	final private ExceptionSummary error;
	final private String componentName;
	final private Environment env;
	final private Optional<String> componentVersion;
	final private Optional<ReporterContextUnion> reporterContext;

	public CrashReport(ExceptionSummary error, String componentName, Optional<String> componentVersion,
			Optional<ReporterContextUnion> reporterContext) {
		this(error, componentName, Environment.get(), componentVersion, reporterContext);
	}

	public CrashReport(ExceptionSummary error, String componentName, Environment env, Optional<String> componentVersion,
			Optional<ReporterContextUnion> reporterContext) {
		this.error = error;
		this.componentName = componentName;
		this.env = env;
		this.componentVersion = componentVersion;
		this.reporterContext = reporterContext;
	}

	public ExceptionSummary getError() {
		return error;
	}

	public String getComponentName() {
		return componentName;
	}

	public Optional<String> getComponentVersion() {
		return componentVersion;
	}

	public Optional<ReporterContextUnion> getReporterContext() {
		return reporterContext;
	}

	public Environment getEnv() {
		return env;
	}

}
