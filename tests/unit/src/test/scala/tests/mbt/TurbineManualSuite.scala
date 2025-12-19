package tests.mbt

import java.nio.file.Paths

import scala.meta.internal.metals.Configs
import scala.meta.internal.metals.UserConfiguration

// Comment out to run manual tests
@munit.IgnoreSuite
class TurbineManualSuite extends tests.BaseManualSuite {

  override def defaultUserConfig: UserConfiguration =
    super.defaultUserConfig.copy(
      workspaceSymbolProvider = Configs.WorkspaceSymbolProviderConfig.mbt,
      javaSymbolLoader = Configs.JavaSymbolLoaderConfig.turbineClasspath,
      referenceProvider = Configs.ReferenceProviderConfig.mbt,
    )
  def repo(name: String): String =
    Paths.get(System.getProperty("user.home"), name).toString()

  // The assertions below stress "Find references" on widely used symbols, so
  // they trigger analysis of thousands of files, which often trips up javac. If
  // all of them pass, we have decent confidence that JavaPruneCompiler isn't
  // crashing too hard with the turbine-created classfiles.

  inDirectory(repo("netbeans"), removeCache = false).test("netbeans") {
    case (server, _) =>
      val main =
        "ide/projectapi/src/org/netbeans/api/project/Project.java"
      for {
        _ <- server.didOpenAndFocus(main)
        locations <- server.referencesSubquery(
          main,
          "public interface Proje@@ct extends Lookup.Provider {",
        )
        _ = assert(clue(locations.length) > 10)
      } yield ()
  }

  inDirectory(repo("intellij-community"), removeCache = false).test(
    "intellij"
  ) { case (server, _) =>
    val main =
      "platform/core-api/src/com/intellij/openapi/project/Project.java"
    for {
      _ <- server.didOpenAndFocus(main)
      locations <- server.referencesSubquery(
        main,
        "interface Proje@@ct extends ",
      )
      _ = assert(clue(locations.length) > 10)
    } yield ()
  }
  inDirectory(repo("platform_frameworks_base"), removeCache = false).test(
    "android"
  ) { case (server, _) =>
    val main =
      "core/java/android/content/ComponentName.java"
    for {
      _ <- server.didOpenAndFocus(main)
      locations <- server.referencesSubquery(
        main,
        "class Compo@@nentName implements ",
      )
      _ = assert(clue(locations.length) > 10)
    } yield ()

  }

  inDirectory(repo("eclipse-jdt-core-incubator"), removeCache = false).test(
    "jdt"
  ) { case (server, _) =>
    val main =
      "org.eclipse.jdt.core/model/org/eclipse/jdt/core/IJavaProject.java"
    for {
      _ <- server.didOpenAndFocus(main)
      locations <- server.referencesSubquery(
        main,
        "interface IJavaP@@roject extends ",
      )
      _ = assert(clue(locations.length) > 10)
    } yield ()

  }

  inDirectory(repo("universe"), removeCache = false).test(
    "universe"
  ) { case (server, _) =>
    val main =
      "example/Example.java"
    for {
      _ <- server.didOpenAndFocus(main)
      locations <- server.referencesSubquery(
        main,
        "public class GatewayPrope@@rties implements",
      )
      // Th
      _ = assert(clue(locations.length) > 330)
    } yield ()

  }
}
