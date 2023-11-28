package scala.meta.internal.telemetry;

import java.util.Optional;

public class JavaInfo {
	final private String version;
	final private Optional<String> distribution;

	public JavaInfo(String version, Optional<String> distribution) {
		this.version = version;
		this.distribution = distribution;
	}

	public String getVersion() {
		return version;
	}

	public Optional<String> getDistribution() {
		return distribution;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((version == null) ? 0 : version.hashCode());
		result = prime * result + ((distribution == null) ? 0 : distribution.hashCode());
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
		JavaInfo other = (JavaInfo) obj;
		if (version == null) {
			if (other.version != null)
				return false;
		} else if (!version.equals(other.version))
			return false;
		if (distribution == null) {
			if (other.distribution != null)
				return false;
		} else if (!distribution.equals(other.distribution))
			return false;
		return true;
	}

}