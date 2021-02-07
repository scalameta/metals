package tests

import scala.meta.internal.metals.MetalsEnrichments._

class BuildTargetsLspSuite
    extends BaseLspSuite("build-targets")
    with TestHovers {

  override def munitIgnore: Boolean = isWindows

  val workspaceBuildTargets: String =
    s"""/metals.json
       |{
       |  "a": {
       |    "scalaVersion": "2.10.6",
       |    "libraryDependencies": ["com.lihaoyi::sourcecode:0.1.7"],
       |    "additionalSources": [ "shared/Main.scala" ]
       |  },
       |  "b": {
       |    "scalaVersion": "${BuildInfo.scalaVersion}",
       |    "libraryDependencies": ["com.lihaoyi::sourcecode:0.1.7"],
       |    "additionalSources": [ "shared/Main.scala" ]
       |  }
       |}
        """.stripMargin

  test("scala-priority") {
    for {
      _ <- server.initialize(workspaceBuildTargets)
      // Assert that a supported Scala version target is picked over 2.10.
      _ <- server.assertHover(
        "shared/Main.scala",
        """
          |object Main {
          |  sourcecode.Line(1).val@@ue
          |}""".stripMargin,
        """val value: Int""".hover
      )
    } yield ()
  }
  test("metals/listBuildTargets") {
    for {
      _ <- server.initialize(workspaceBuildTargets)
      buildTargets <- server.server.listBuildTargets().asScala
      _ = {
        assert(buildTargets.size == 2)
        buildTargets.forEach {
          case a if a.displayName == "a" =>
            assertNoDiff(a.scalaVersion, "2.10.6")
            assert(a.id.getUri().endsWith("a?id=a"))
          case b if b.displayName == "b" =>
            assert(b.scalaVersion == BuildInfo.scalaVersion)
            assert(b.id.getUri().endsWith("b?id=b"))
          case _ => fail("This should only detect build targets 'a' and 'b'")
        }
      }
    } yield ()
  }
}
