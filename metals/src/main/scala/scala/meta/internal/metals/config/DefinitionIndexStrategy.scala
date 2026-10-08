package scala.meta.internal.metals.config

import scala.meta.infra.FeatureFlag
import scala.meta.infra.FeatureFlagProvider

final case class DefinitionIndexStrategy(val value: String) {
  require(List("classpath", "sources").contains(value), value)
  def isClasspath: Boolean =
    value == "classpath"
  def isSources: Boolean =
    value == "sources"
}

object DefinitionIndexStrategy {
  def classpath: DefinitionIndexStrategy =
    DefinitionIndexStrategy("classpath")
  def sources: DefinitionIndexStrategy =
    DefinitionIndexStrategy("sources")
  def default: DefinitionIndexStrategy = classpath
  def fromConfigOrFeatureFlag(
      value: Option[String],
      featureFlags: FeatureFlagProvider,
  ): Either[String, DefinitionIndexStrategy] = {
    value match {
      case Some(ok @ ("classpath" | "sources")) =>
        Right(DefinitionIndexStrategy(ok))
      case Some(invalid) =>
        Left(
          s"invalid config value '$invalid' for definitionIndexStrategy. Valid values are \"classpath\" and \"sources\""
        )
      case None =>
        val isClasspathEnabled = featureFlags
          .readBoolean(FeatureFlag.CLASSPATH_DEFINITION_INDEX)
          .orElse(false)
        if (isClasspathEnabled) {
          Right(DefinitionIndexStrategy.classpath)
        } else {
          Right(DefinitionIndexStrategy.default)
        }
    }
  }
}
