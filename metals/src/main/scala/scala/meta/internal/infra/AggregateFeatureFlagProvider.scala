package scala.meta.internal.infra

import java.util.Optional
import java.util.ServiceLoader

import scala.meta.infra.FeatureFlag
import scala.meta.infra.FeatureFlagProvider
import scala.meta.internal.jdk.CollectionConverters._

class AggregateFeatureFlagProvider(val underlying: List[FeatureFlagProvider])
    extends FeatureFlagProvider {

  override def readBoolean(flag: FeatureFlag): Optional[java.lang.Boolean] =
    underlying.foldLeft(Optional.empty[java.lang.Boolean])((acc, provider) =>
      if (acc.isPresent) acc // Return first non-empty result
      else provider.readBoolean(flag)
    )
}

object AggregateFeatureFlagProvider {
  def fromServiceLoader(): AggregateFeatureFlagProvider =
    new AggregateFeatureFlagProvider(
      ServiceLoader
        .load(classOf[FeatureFlagProvider])
        .iterator
        .asScala
        .toList
    )
}
