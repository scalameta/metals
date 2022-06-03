package scala.meta.internal.metals

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

import scala.meta.internal.bsp.BspServers
import scala.meta.io.AbsolutePath

/**
 * Container class for metals language server and service configuration.
 *   The configuration is mostly used to allow for custom behaviour in tests.
 *   All the defaults are the instances used on the production.
 * @param buffers
 *  In-memory text contents for unsaved files.
 * @param time
 *  A "clock" instance for getting the current time.
 * @param initialServerConfig
 *  Configuration parameters for the Metals language server.
 * @param initialUserConfig
 *  Configuration which can be overriden by the user (via workspace/didChangeConfiguration)
 * @param bspGlobalDirectories
 *  Directories for user and system installed BSP connection
 *    details according to BSP spec:
 *    https://build-server-protocol.github.io/docs/server-discovery.html#default-locations-for-bsp-connection-files
 * @param mtagsResolver
 *  Mtags provider.
 * @param onStartCompilation
 *  A function executed on comilation start.
 *  Used in tests to e.g. count the number of executed compilations.
 * @param redirectSystemOut
 *  If system.out should be redirected into a file.
 * @param progressTicks
 *  Tick marks kind for progress bars.
 * @param isReliableFileWatcher
 *  A special flag set to false only when running tests on non-Linux computers
 *  to avoid flaky-test failure delayed file watching notifications
 * @param classpathSearchIndexer
 *  Indexer for classpath elements for workspace symbol search.
 * @param charset
 *  The mapping between sequences of sixteen-bit Unicode codes and sequences of bytes
 *  that should be used for interpreting the files.
 */
final case class MetalsServerInputs(
    buffers: Buffers,
    time: Time,
    initialServerConfig: MetalsServerConfig,
    initialUserConfig: UserConfiguration,
    bspGlobalDirectories: List[AbsolutePath],
    mtagsResolver: MtagsResolver,
    onStartCompilation: () => Unit,
    redirectSystemOut: Boolean,
    progressTicks: ProgressTicks,
    isReliableFileWatcher: Boolean,
    classpathSearchIndexer: ClasspathSearch.Indexer,
    charset: Charset,
)

object MetalsServerInputs {
  def productionConfiguration: MetalsServerInputs =
    MetalsServerInputs(
      buffers = Buffers(),
      time = Time.system,
      initialServerConfig = MetalsServerConfig.default,
      initialUserConfig = UserConfiguration.default,
      bspGlobalDirectories = BspServers.globalInstallDirectories,
      mtagsResolver = MtagsResolver.default(),
      onStartCompilation = () => (),
      redirectSystemOut = true,
      progressTicks = ProgressTicks.braille,
      isReliableFileWatcher = true,
      classpathSearchIndexer = ClasspathSearch.Indexer.default,
      charset = StandardCharsets.UTF_8,
    )
}
