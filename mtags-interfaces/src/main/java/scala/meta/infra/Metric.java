package scala.meta.infra;

import java.util.Map;
import java.util.HashMap;
import java.time.Duration;

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

	public final String name;
	public final String description;
	public MetricType metricType;
	public float value;
	public UnitType unit;
	public RetentionType retention;
	public String buckets = "";
	public String quantiles = "";
	public Map<String, String> labels = new HashMap<>();
	public String user = System.getProperty("user.name");
	public String timestamp = String.valueOf(System.currentTimeMillis());
	public final int version = 5;

	public Metric(String name) {
		this(name, "");
	}

	public Metric(String name, String description) {
		this.name = name;
		this.description = description;
	}

	public static Metric count(String name) {
		return new Metric(name).setValue(1, UnitType.COUNT, MetricType.COUNTER);
	}

	public Metric setMetricType(MetricType metricType) {
		this.metricType = metricType;
		return this;
	}

	public Metric setValue(float value) {
		this.value = value;
		return this;
	}

	public Metric setValue(float value, UnitType unit, MetricType metricType) {
		this.value = value;
		this.unit = unit;
		this.metricType = metricType;
		return this;
	}

	public Metric setUnit(UnitType unit) {
		this.unit = unit;
		return this;
	}

	public Metric setRetention(RetentionType retention) {
		this.retention = retention;
		return this;
	}

	public Metric setBuckets(String buckets) {
		this.buckets = buckets;
		return this;
	}

	public Metric setQuantiles(String quantiles) {
		this.quantiles = quantiles;
		return this;
	}

	public Metric addLabel(String key, String value) {
		this.labels.put(key, value);
		return this;
	}

	public Metric setLabels(Map<String, String> labels) {
		this.labels = labels;
		return this;
	}

	public String toString() {
		return "Metric(name=" + name + ", metricType=" + metricType + ", value=" + value + ", unit=" + unit
				+ ", retention=" + retention + ", buckets=" + buckets + ", quantiles=" + quantiles + ", labels="
				+ labels + ", description=" + description + ", user=" + user + ", timestamp=" + timestamp + ", version="
				+ version + ")";
	}
}