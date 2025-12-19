package tests.j

import scala.meta.internal.metals.Configs.JavaSymbolLoaderConfig
import scala.meta.internal.metals.Configs.TurbineRecompileDelayConfig
import scala.meta.internal.metals.Configs.WorkspaceSymbolProviderConfig
import scala.meta.internal.metals.UserConfiguration

import munit.Location
import munit.TestOptions
import tests.BaseLspSuite
import tests.BuildInfo

abstract class BaseJavaPCSuite(name: String) extends BaseLspSuite(name) {
  val JavacSourcepath = new munit.Tag("javac-sourcepath")
  val TurbineClasspath = new munit.Tag("turbine-classpath")

  // By default (when None), tests run with both turbine-classpath and javac-sourcepath.
  // Override to Some(...) to only run one of the settings.
  def javaSymbolLoaderMode: Option[JavaSymbolLoaderConfig] = None

  /** Override to customize the turbine recompile delay */
  def turbineRecompileDelayConfig: TurbineRecompileDelayConfig =
    TurbineRecompileDelayConfig.testing

  private var currentTest: munit.Test = null
  override def beforeEach(context: BeforeEach): Unit = {
    currentTest = context.test
    super.beforeEach(context)
  }
  override def test(
      options: TestOptions
  )(body: => Any)(implicit loc: Location): Unit = {
    javaSymbolLoaderMode match {
      case None =>
        // Run all tests with both turbine classpath and javac sourcepath since
        // javac-sourcepath needs to be a reliable fallback until we have enough
        // confidence in going all-in on turbine-classpath.
        super.test(
          options
            .tag(TurbineClasspath)
            .withName(s"${options.name}-turbine-classpath")
        )(body)
        super.test(
          options
            .tag(JavacSourcepath)
            .withName(s"${options.name}-javac-sourcepath")
        )(body)
      case Some(config) =>
        // Run test with the specified javaSymbolLoaderConfig
        val tag =
          if (config.isTurbineClasspath) TurbineClasspath
          else JavacSourcepath
        super.test(options.tag(tag))(body)
    }
  }

  private def resolvedJavaSymbolLoader: JavaSymbolLoaderConfig = {
    javaSymbolLoaderMode match {
      case None =>
        if (currentTest.tags(TurbineClasspath))
          JavaSymbolLoaderConfig.turbineClasspath
        else
          JavaSymbolLoaderConfig.javacSourcepath
      case Some(config) =>
        config
    }
  }

  override def userConfig: UserConfiguration =
    super.userConfig.copy(
      fallbackScalaVersion = Some(BuildInfo.scalaVersion),
      presentationCompilerDiagnostics = true,
      buildOnChange = false,
      buildOnFocus = false,
      workspaceSymbolProvider = WorkspaceSymbolProviderConfig.mbt,
      javaSymbolLoader = resolvedJavaSymbolLoader,
      javaTurbineRecompileDelay = turbineRecompileDelayConfig,
    )

  override def initializeGitRepo: Boolean = true
}
