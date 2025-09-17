package scala.meta.infra;

import java.util.Map;
import java.util.HashMap;

public class Metric {
	public enum RetentionType {
		DELETE_AFTER_PUSH, RETAIN
	}

	public enum UnitType {
		INFO, RATIO, COUNT, SECONDS, MILLISECONDS, BYTES
	}

	/**
	 * A counter is an ever increasing value and each metric recorded increases the
	 * * counter. An example are the number of API calls.
	 *
	 * A gauge is a metric is has a certain value and each metric recorded records a
	 * new value. An example are the number of API rate limited calls left in an
	 * hour.
	 *
	 * Similar to a histogram, a summary samples observations (usually things like
	 * request durations and response sizes). While it also provides a total count
	 * of observations and a sum of all observed values, it calculates configurable
	 * quantiles over a sliding time window.
	 *
	 * A histogram is a more limited version of metric where instead of recording
	 * the exact value, the information is recorded in pre-defined buckets.
	 */
	public enum MetricType {
		COUNTER, GAUGE, SUMMARY, HISTOGRAM
	}

	public String name;
	public MetricType metricType;
	public String service;
	public float value;
	public UnitType unit;
	public RetentionType retention;
	public String buckets = "";
	public String quantiles = "";
	public Map<String, String> labels = new HashMap<>();
	public String description = "A metric originated from Metric Proxy Scala client";
	public String user = System.getProperty("user.name");
	public String timestamp = String.valueOf(System.currentTimeMillis());
	public int version = 5;

	public String toString() {
		return "Metric(name=" + name + ", metricType=" + metricType + ", service=" + service + ", value=" + value
				+ ", unit=" + unit + ", retention=" + retention + ", buckets=" + buckets + ", quantiles=" + quantiles
				+ ", labels=" + labels + ", description=" + description + ", user=" + user + ", timestamp=" + timestamp
				+ ", version=" + version + ")";
	}
}