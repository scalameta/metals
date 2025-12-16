package tests.j

import scala.meta.internal.metals.Configs.JavaSymbolLoaderConfig
import scala.meta.internal.metals.Configs.WorkspaceSymbolProviderConfig
import scala.meta.internal.metals.UserConfiguration

import munit.Location
import munit.TestOptions
import tests.BaseLspSuite
import tests.BuildInfo

abstract class BaseJavaPCSuite(name: String) extends BaseLspSuite(name) {
  val JavacSourcepath = new munit.Tag("javac-sourcepath")
  val TurbineClasspath = new munit.Tag("turbine-classpath")

  private var currentTest: munit.Test = null
  override def beforeEach(context: BeforeEach): Unit = {
    currentTest = context.test
    super.beforeEach(context)
  }
  override def test(
      options: TestOptions
  )(body: => Any)(implicit loc: Location): Unit = {
    // Run all tests with both turbine classpath and javac sourcepath since
    // javac-sourcepath needs to be a reliable fallback until we have enough
    // confidence in going all-in on turbine-classpath.
    super.test(
      options
        .tag(TurbineClasspath)
        .withName(s"${options.name}-turbine-classpath")
        .ignore
        .pending("turbine-classpath is not implemented yet")
    )(body)
    super.test(
      options
        .tag(JavacSourcepath)
        .withName(s"${options.name}-javac-sourcepath")
    )(body)
  }
  override def userConfig: UserConfiguration =
    super.userConfig.copy(
      fallbackScalaVersion = Some(BuildInfo.scalaVersion),
      presentationCompilerDiagnostics = true,
      buildOnChange = false,
      buildOnFocus = false,
      workspaceSymbolProvider = WorkspaceSymbolProviderConfig.mbt2,
      javaSymbolLoader =
        if (currentTest.tags(TurbineClasspath))
          JavaSymbolLoaderConfig.turbineClasspath
        else
          JavaSymbolLoaderConfig.javacSourcepath,
    )

  override def initializeGitRepo: Boolean = true
}
