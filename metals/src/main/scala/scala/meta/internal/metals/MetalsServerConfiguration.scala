package scala.meta.internal.metals

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

import scala.meta.internal.bsp.BspServers
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.ClasspathSearch
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.MtagsResolver
import scala.meta.internal.metals.ProgressTicks
import scala.meta.internal.metals.Time
import scala.meta.internal.metals.UserConfiguration
import scala.meta.io.AbsolutePath

/**
 * Container class for metals language server and service configuration.
 *   The configuration is mostly used to allow for custom behaviour in tests.
 *   All the defaults are the instances used on the production.
 */
abstract class MetalsServerConfiguration {
  val charset: Charset = StandardCharsets.UTF_8

  /** In-memory text contents for unsaved files. */
  val buffers: Buffers = Buffers()

  /** If system.out should be redirected into a file. */
  val redirectSystemOut: Boolean = true

  /** A "clock" instance for getting the current time. */
  val time: Time = Time.system

  /** Configuration parameters for the Metals language server. */
  val initialServerConfig: MetalsServerConfig = MetalsServerConfig.default

  /** Configuration which can be overriden by the user (via workspace/didChangeConfiguration) */
  val initialUserConfig: UserConfiguration = UserConfiguration.default

  /** Tick marks kind for progress bars. */
  val progressTicks: ProgressTicks = ProgressTicks.braille

  /**
   * Directories for user and system installed BSP connection
   * details according to BSP spec:
   * https://build-server-protocol.github.io/docs/server-discovery.html#default-locations-for-bsp-connection-files
   */
  val bspGlobalDirectories: List[AbsolutePath] =
    BspServers.globalInstallDirectories

  /**
   * A special flag set to false only when running tests on non-Linux computers
   *  to avoid flaky-test failure delayed file watching notifications
   */
  val isReliableFileWatcher: Boolean = true

  /** Mtags provider. */
  val mtagsResolver: MtagsResolver = MtagsResolver.default()

  /** A function executed on comilation start. Used in tests to e.g. count the number of executed compilations. */
  val onStartCompilation: () => Unit = () => ()

  /** Indexer for classpath elements for workspace symbol search. */
  val classpathSearchIndexer: ClasspathSearch.Indexer =
    ClasspathSearch.Indexer.default
}

object MetalsServerConfiguration {
  def productionConfiguration: MetalsServerConfiguration =
    new MetalsServerConfiguration {}
}
