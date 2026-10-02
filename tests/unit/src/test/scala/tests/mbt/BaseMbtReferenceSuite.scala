package tests.mbt

import scala.meta.internal.metals.config.FallbackSourcepathConfig
import scala.meta.internal.metals.config.ReferenceProviderConfig
import scala.meta.internal.metals.config.UserConfiguration
import scala.meta.internal.metals.config.WorkspaceSymbolProviderConfig

abstract class BaseMbtReferenceSuite(name: String)
    extends tests.BaseLspSuite(name) {
  override def userConfig: UserConfiguration = super.userConfig.copy(
    referenceProvider = ReferenceProviderConfig.MBT,
    workspaceSymbolProvider = WorkspaceSymbolProviderConfig.MBT,
    fallbackSourcepath = FallbackSourcepathConfig.AllSources,
  )

  override def initializeGitRepo: Boolean = true

}
