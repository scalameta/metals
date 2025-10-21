package scala.meta.internal.infra

import java.util.Optional
import java.util.ServiceLoader

import scala.util.control.NonFatal

import scala.meta.infra.FeatureFlag
import scala.meta.infra.FeatureFlagProvider
import scala.meta.internal.jdk.CollectionConverters._

class AggregateFeatureFlagProvider(val underlying: List[FeatureFlagProvider])
    extends FeatureFlagProvider {

  override def readBoolean(flag: FeatureFlag): Optional[java.lang.Boolean] = {
    underlying.foldLeft(Optional.empty[java.lang.Boolean])((acc, provider) => {
      if (acc.isPresent) acc // Return first non-empty result
      else {
        try provider.readBoolean(flag)
        catch {
          case NonFatal(e) =>
            scribe.error(
              s"error reading feature flag $flag from provider ${provider.getClass.getName}",
              e,
            )
            Optional.empty()
        }
      }
    })
  }
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
