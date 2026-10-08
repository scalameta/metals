package scala.meta.internal.metals.config

final case class MbtConfig(
    importGeneratedSources: Boolean = false,
    semanticdbCacheEnabled: Boolean = false,
    semanticdbCacheMaxSize: Int = Int.MaxValue,
    referencesTimeoutSeconds: Int = 20,
)

object MbtConfig {
  def default: MbtConfig = MbtConfig()
}
