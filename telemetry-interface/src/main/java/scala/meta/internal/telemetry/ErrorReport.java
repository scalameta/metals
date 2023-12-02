package scala.meta.internal.telemetry;

import java.util.Optional;

public class ErrorReport {
	final private String name;
	final private Optional<String> text;
	final private Optional<String> id;
	final private Optional<ExceptionSummary> error;
	final private String reporterName;
	final private ReporterContextUnion reporterContext;
	final private Environment env;

	public ErrorReport(String name, Optional<String> text, Optional<String> id, Optional<ExceptionSummary> error,
			String reporterName, ReporterContextUnion reporterContext) {
		this(name, text, id, error, reporterName, reporterContext, Environment.get());
	}

	public ErrorReport(String name, Optional<String> text, Optional<String> id, Optional<ExceptionSummary> error,
			String reporterName, ReporterContextUnion reporterContext, Environment env) {
		this.name = name;
		this.text = text;
		this.id = id;
		this.error = error;
		this.reporterName = reporterName;
		this.reporterContext = reporterContext;
		this.env = env;
	}

	public String getName() {
		return name;
	}

	public Optional<String> getText() {
		return text;
	}

	public Optional<String> getId() {
		return id;
	}

	public Optional<ExceptionSummary> getError() {
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
		ErrorReport other = (ErrorReport) obj;
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
