package scala.meta.infra;

public class Telemetry {
	public static boolean isEnabled() {
		return "enabled".equals(System.getProperty("metals.telemetry"));
	}
}