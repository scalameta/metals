package scala.meta.internal.telemetry;

import java.util.Optional;

public class MetalsClientInfo {
	final private Optional<String> name;
	final private Optional<String> version;

	public MetalsClientInfo(Optional<String> name, Optional<String> version) {
		this.name = name;
		this.version = version;
	}

	public Optional<String> getName() {
		return name;
	}

	public Optional<String> getVersion() {
		return version;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((name == null) ? 0 : name.hashCode());
		result = prime * result + ((version == null) ? 0 : version.hashCode());
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
		MetalsClientInfo other = (MetalsClientInfo) obj;
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
		return true;
	}

}