package scala.meta.internal.telemetry;

public class Environment {
	private JavaInfo java;
	private SystemInfo system;

	public Environment(JavaInfo java, SystemInfo system) {
		this.java = java;
		this.system = system;
	}

	public void setJava(JavaInfo java) {
		this.java = java;
	}

	public void setSystem(SystemInfo system) {
		this.system = system;
	}

	public JavaInfo getJava() {
		return java;
	}

	public SystemInfo getSystem() {
		return system;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((java == null) ? 0 : java.hashCode());
		result = prime * result + ((system == null) ? 0 : system.hashCode());
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
		Environment other = (Environment) obj;
		if (java == null) {
			if (other.java != null)
				return false;
		} else if (!java.equals(other.java))
			return false;
		if (system == null) {
			if (other.system != null)
				return false;
		} else if (!system.equals(other.system))
			return false;
		return true;
	}
}