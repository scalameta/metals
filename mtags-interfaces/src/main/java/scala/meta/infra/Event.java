package scala.meta.infra;

import java.util.Map;
import java.util.HashMap;
import java.time.Duration;

public class Event {
	public final Map<String, String> tags = new HashMap<>();

	public static Event duration(String name, Duration duration) {
		return new Event().withLabel("name", name).withLabel("durationMillis", String.valueOf(duration.toMillis()));
	}

	public Event withLabel(String key, String value) {
		tags.put(key, value);
		return this;
	}
}