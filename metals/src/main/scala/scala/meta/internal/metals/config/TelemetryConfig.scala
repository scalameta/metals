package scala.meta.internal.metals.config

object TelemetryConfig {
  def default: TelemetryConfig =
    // NOTE: by default, Metals confusingly does not send telemetry even when
    // it's "enabled". You also have to make sure metals is running with a
    // classpath that registers service providers for FeatureFlagProvider and
    // MonitoringClient interfaces. A more accurate name for "enabled" would
    // be "enabled-if-instrumented" or something like that but it's still
    // confusing.
    new TelemetryConfig(System.getProperty("metals.telemetry", "enabled"))
}

final class TelemetryConfig(val value: String) {
  def isAllEnabled: Boolean =
    value == "enabled"
  def isMetricsEnabled: Boolean =
    isAllEnabled || value.contains("metrics")
  def isFeatureFlagsEnabled: Boolean =
    isAllEnabled || value.contains("feature-flags")
}
