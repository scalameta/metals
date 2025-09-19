package scala.meta.internal.infra

import java.util.Optional

import scala.meta.infra.FeatureFlag
import scala.meta.infra.FeatureFlagProvider

object NoopFeatureFlagProvider extends FeatureFlagProvider {
  override def readBoolean(flag: FeatureFlag): Optional[java.lang.Boolean] =
    Optional.empty()
}
