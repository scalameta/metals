package scala.meta.internal.telemetry;

import java.util.Optional;

public class ReportEvent {
	final private String name;
	final private String text;
	final private String shortSummary;
	final private Optional<String> id;
	final private Optional<ReportedError> error;
	final private String reporterName;
	final private ReporterContextUnion reporterContext;
	final private Environment env;

	public ReportEvent(String name, String text, String shortSummary, Optional<String> id,
			Optional<ReportedError> error, String reporterName, ReporterContextUnion reporterContext, Environment env) {
		this.name = name;
		this.text = text;
		this.shortSummary = shortSummary;
		this.id = id;
		this.error = error;
		this.reporterName = reporterName;
		this.reporterContext = reporterContext;
		this.env = env;
	}

	public String getName() {
		return name;
	}

	public String getText() {
		return text;
	}

	public String getShortSummary() {
		return shortSummary;
	}

	public Optional<String> getId() {
		return id;
	}

	public Optional<ReportedError> getError() {
		return error;
	}

	public String getReporterName() {
		return reporterName;
	}

	public ReporterContextUnion getReporterContext() {
		return reporterContext;
	}

	public Environment getEnv() {
		return env;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((name == null) ? 0 : name.hashCode());
		result = prime * result + ((text == null) ? 0 : text.hashCode());
		result = prime * result + ((shortSummary == null) ? 0 : shortSummary.hashCode());
		result = prime * result + ((id == null) ? 0 : id.hashCode());
		result = prime * result + ((error == null) ? 0 : error.hashCode());
		result = prime * result + ((reporterName == null) ? 0 : reporterName.hashCode());
		result = prime * result + ((reporterContext == null) ? 0 : reporterContext.hashCode());
		result = prime * result + ((env == null) ? 0 : env.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		ReportEvent other = (ReportEvent) obj;
		if (name == null) {
			if (other.name != null)
				return false;
		} else if (!name.equals(other.name))
			return false;
		if (text == null) {
			if (other.text != null)
				return false;
		} else if (!text.equals(other.text))
			return false;
		if (shortSummary == null) {
			if (other.shortSummary != null)
				return false;
		} else if (!shortSummary.equals(other.shortSummary))
			return false;
		if (id == null) {
			if (other.id != null)
				return false;
		} else if (!id.equals(other.id))
			return false;
		if (error == null) {
			if (other.error != null)
				return false;
		} else if (!error.equals(other.error))
			return false;
		if (reporterName == null) {
			if (other.reporterName != null)
				return false;
		} else if (!reporterName.equals(other.reporterName))
			return false;
		if (reporterContext == null) {
			if (other.reporterContext != null)
				return false;
		} else if (!reporterContext.equals(other.reporterContext))
			return false;
		if (env == null) {
			if (other.env != null)
				return false;
		} else if (!env.equals(other.env))
			return false;
		return true;
	}

}
