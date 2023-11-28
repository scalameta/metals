package scala.meta.internal.telemetry;

public class SystemInfo {
	final private String architecture;
	final private String name;
	final private String version;

	public SystemInfo(String architecture, String name, String version) {
		this.architecture = architecture;
		this.name = name;
		this.version = version;
	}

	public String getArchitecture() {
		return architecture;
	}

	public String getName() {
		return name;
	}

	public String getVersion() {
		return version;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((architecture == null) ? 0 : architecture.hashCode());
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
		SystemInfo other = (SystemInfo) obj;
		if (architecture == null) {
			if (other.architecture != null)
				return false;
		} else if (!architecture.equals(other.architecture))
			return false;
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