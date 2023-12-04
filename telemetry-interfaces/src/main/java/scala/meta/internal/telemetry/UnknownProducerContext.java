package scala.meta.internal.telemetry;

import java.util.Map;

public class UnknownProducerContext implements ReporterContext {
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((name == null) ? 0 : name.hashCode());
		result = prime * result + ((version == null) ? 0 : version.hashCode());
		result = prime * result + ((properties == null) ? 0 : properties.hashCode());
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
		UnknownProducerContext other = (UnknownProducerContext) obj;
		if (name == null) {
			if (other.name != null)
				return false;
		} else if (!name.equals(other.name))
			return false;
		if (version == null) {
			if (other.version != null)
				return false;
		} else if (!version.equals(other.version))
			return false;
		if (properties == null) {
			if (other.properties != null)
				return false;
		} else if (!properties.equals(other.properties))
			return false;
		return true;
	}

	final private String name;
	final private String version;
	final private Map<String, String> properties;

	public UnknownProducerContext(String name, String version, Map<String, String> properties) {
		this.name = name;
		this.version = version;
		this.properties = properties;
	}

	public String getName() {
		return name;
	}

	public String getVersion() {
		return version;
	}

	public Map<String, String> getProperties() {
		return properties;
	}

}