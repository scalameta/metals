package scala.meta.internal.telemetry;

import java.util.List;

public class ScalaPresentationCompilerContext implements ReporterContext {
	final private String scalaVersion;
	final private List<String> options;
	final private PresentationCompilerConfig config;

	public ScalaPresentationCompilerContext(String scalaVersion, List<String> options,
			PresentationCompilerConfig config) {
		this.scalaVersion = scalaVersion;
		this.options = options;
		this.config = config;
	}

	public String getScalaVersion() {
		return scalaVersion;
	}

	public List<String> getOptions() {
		return options;
	}

	public PresentationCompilerConfig getConfig() {
		return config;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((scalaVersion == null) ? 0 : scalaVersion.hashCode());
		result = prime * result + ((options == null) ? 0 : options.hashCode());
		result = prime * result + ((config == null) ? 0 : config.hashCode());
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
		ScalaPresentationCompilerContext other = (ScalaPresentationCompilerContext) obj;
		if (scalaVersion == null) {
			if (other.scalaVersion != null)
				return false;
		} else if (!scalaVersion.equals(other.scalaVersion))
			return false;
		if (options == null) {
			if (other.options != null)
				return false;
		} else if (!options.equals(other.options))
			return false;
		if (config == null) {
			if (other.config != null)
				return false;
		} else if (!config.equals(other.config))
			return false;
		return true;
	}
}
