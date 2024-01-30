package scala.meta.internal.telemetry;

public class BuildServerConnection {
	final private String name;
	final private String version;
	final private boolean isMain;

	public BuildServerConnection(String name, String version, boolean isMain) {
		this.name = name;
		this.version = version;
		this.isMain = isMain;
	}

	public String getName() {
		return name;
	}

	public String getVersion() {
		return version;
	}

	public boolean isMain() {
		return isMain;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((name == null) ? 0 : name.hashCode());
		result = prime * result + ((version == null) ? 0 : version.hashCode());
		result = prime * result + (isMain ? 1231 : 1237);
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
		BuildServerConnection other = (BuildServerConnection) obj;
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
		if (isMain != other.isMain)
			return false;
		return true;
	}

}