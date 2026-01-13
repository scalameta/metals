package tests.mbt

import scala.meta.internal.metals.Configs.FallbackSourcepathConfig
import scala.meta.internal.metals.Configs.ReferenceProviderConfig
import scala.meta.internal.metals.Configs.WorkspaceSymbolProviderConfig
import scala.meta.internal.metals.UserConfiguration

abstract class BaseMbtReferenceSuite(name: String)
    extends tests.BaseLspSuite(name) {
  override def userConfig: UserConfiguration = super.userConfig.copy(
    referenceProvider = ReferenceProviderConfig.mbt,
    workspaceSymbolProvider = WorkspaceSymbolProviderConfig.mbt,
    fallbackSourcepath = FallbackSourcepathConfig("all-sources"),
  )

  override def initializeGitRepo: Boolean = true

}
