package tests.mbt

import scala.meta.internal.metals.AutoImportBuildKind
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.mbt.MbtBuildServer

import tests.BuildInfo
import tests.MbtJsonBuilder

/**
 * Renaming a public (non-local) symbol requires SemanticDB for the workspace
 * source file. In MBT mode the build server does not emit on-disk SemanticDB,
 * so we rely on interactively computed SemanticDB. See issue #8495.
 */
class MbtRenameSuite extends BaseMbtReferenceSuite("mbt-rename") {

  override def userConfig: UserConfiguration =
    super.userConfig.copy(
      fallbackScalaVersion = Some(BuildInfo.scalaVersion),
      preferredBuildServer = Some(MbtBuildServer.name),
      automaticImportBuild = AutoImportBuildKind.All,
    )

  private val a = "src/a/Upstream.java"
  private val b = "src/a/Downstream.java"

  private val mbtJson = new MbtJsonBuilder(BuildInfo.scalaVersion)
    .addScalaLibrary()
    .addNamespace("a", List("src/**"))
    .build()

  private def initLayout(className: String, field: String): String =
    s"""|/.metals/mbt.json
        |$mbtJson
        |/$a
        |package a;
        |public class $className {
        |  public static String $field = "Hello, World!";
        |}
        |/$b
        |package a;
        |public class Downstream {
        |  public static void main(String[] args) {
        |    System.out.println($className.$field);
        |  }
        |}
        |""".stripMargin

  test("public-java-field") {
    cleanWorkspace()
    for {
      _ <- initialize(initLayout("Upstream", "greeting"))
      _ = assertConnectedToBuildServer("MBT")
      _ <- server.didOpen(a)
      _ <- server.didOpen(b)
      renamed <- server.rename(
        a,
        s"""|package a;
            |public class Upstream {
            |  public static String greeti@@ng = "Hello, World!";
            |}
            |""".stripMargin,
        Set(a, b),
        "salutation",
      )
      _ = assertNoDiff(
        renamed(a),
        """|package a;
           |public class Upstream {
           |  public static String salutation = "Hello, World!";
           |}
           |""".stripMargin,
      )
      _ = assertNoDiff(
        renamed(b),
        """|package a;
           |public class Downstream {
           |  public static void main(String[] args) {
           |    System.out.println(Upstream.salutation);
           |  }
           |}
           |""".stripMargin,
      )
    } yield ()
  }

  test("public-java-class") {
    cleanWorkspace()
    for {
      _ <- initialize(initLayout("Upstream", "greeting"))
      _ = assertConnectedToBuildServer("MBT")
      _ <- server.didOpen(a)
      _ <- server.didOpen(b)
      renamed <- server.rename(
        a,
        s"""|package a;
            |public class Upstr@@eam {
            |  public static String greeting = "Hello, World!";
            |}
            |""".stripMargin,
        Set(a, b),
        "Greeter",
      )
      _ = assertNoDiff(
        renamed(a),
        """|package a;
           |public class Greeter {
           |  public static String greeting = "Hello, World!";
           |}
           |""".stripMargin,
      )
      _ = assertNoDiff(
        renamed(b),
        """|package a;
           |public class Downstream {
           |  public static void main(String[] args) {
           |    System.out.println(Greeter.greeting);
           |  }
           |}
           |""".stripMargin,
      )
    } yield ()
  }
}
