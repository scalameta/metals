package scala.meta.internal.telemetry;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.function.Function;

public class ReportedError {
	final private List<String> exceptions;
	final private String stacktrace;

	public ReportedError(List<String> exceptions, String stacktrace) {
		this.exceptions = exceptions;
		this.stacktrace = stacktrace;
	}

	public List<String> getExceptions() {
		return exceptions;
	}

	public String getStacktrace() {
		return stacktrace;
	}

	
	public static ReportedError fromThrowable(Throwable exception, Function<String, String> sanitizer) {
		List<String> exceptions = new java.util.LinkedList<>();
		for (Throwable current = exception; current != null; current = current.getCause()) {
			exceptions.add(current.getClass().getName());
		}
		StringWriter stringWriter = new StringWriter();
		try (PrintWriter pw = new PrintWriter(stringWriter)) {
			exception.printStackTrace(pw);
		}
		String stacktrace = sanitizer.apply(stringWriter.toString());
		return new ReportedError(exceptions, stacktrace);
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((exceptions == null) ? 0 : exceptions.hashCode());
		result = prime * result + ((stacktrace == null) ? 0 : stacktrace.hashCode());
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
		ReportedError other = (ReportedError) obj;
		if (exceptions == null) {
			if (other.exceptions != null)
				return false;
		} else if (!exceptions.equals(other.exceptions))
			return false;
		if (stacktrace == null) {
			if (other.stacktrace != null)
				return false;
		} else if (!stacktrace.equals(other.stacktrace))
			return false;
		return true;
	}

}