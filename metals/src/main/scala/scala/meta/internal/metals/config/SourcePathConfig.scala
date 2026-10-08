package scala.meta.internal.metals.config

import scala.meta.infra.FeatureFlag
import scala.meta.infra.FeatureFlagProvider
import scala.meta.pc.SourcePathMode

object SourcePathConfig {

  def fromConfigOrFeatureFlag(
      value: Option[String],
      featureFlags: FeatureFlagProvider,
      default: SourcePathMode = SourcePathMode.DISABLED,
  ): Either[String, SourcePathMode] = {
    value.map(_.toLowerCase) match {
      case Some("full") => Right(SourcePathMode.FULL)
      case Some("disabled") => Right(SourcePathMode.DISABLED)
      case Some("pruned") => Right(SourcePathMode.PRUNED)
      case Some("mbt") => Right(SourcePathMode.MBT)
      case Some(invalid) =>
        Left(
          s"invalid config value '$invalid' for source path. Valid values are \"full\", \"disabled\", and \"pruned\""
        )
      case None =>
        val isPrunedEnabled = featureFlags
          .readBoolean(FeatureFlag.SCALA_SOURCEPATH_PRUNED)
          .orElse(false)
        if (isPrunedEnabled) {
          scribe.debug(
            s"Overriding source path mode via Feature Flag to: PRUNED"
          )
          Right(SourcePathMode.PRUNED)
        } else {
          scribe.debug(s"Leaving default source path mode: $default")
          Right(default)
        }
    }
  }
}
