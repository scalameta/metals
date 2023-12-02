package scala.meta.internal.telemetry;

public class Environment {
	final private JavaInfo java;
	final private SystemInfo system;

	private final static Environment instance;

	public static Environment get() {
		return instance;
	}

	static {
		instance = new Environment(
				new JavaInfo(System.getProperty("java.version", "unknown"),
						System.getProperty("java.vendor", "unknown")),
				new SystemInfo(System.getProperty("os.arch", "unknown"), System.getProperty("os.name", "unknown"),
						System.getProperty("os.version", "unknown")));
	}

	// Generated

	public Environment(JavaInfo java, SystemInfo system) {
		this.java = java;
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