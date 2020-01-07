package tests
import scala.concurrent.duration.Duration
import scala.concurrent.Future
import funsuite.TestOptions

object BuildTargetsLspSuite
    extends BaseLspSuite("build-targets")
    with TestHovers {

  override def testAsync(
      options: TestOptions,
      maxDuration: Duration = Duration("3min")
  )(run: => Future[Unit]): Unit = {
    super.testAsync(options, maxDuration) {
      assume(!isWindows, "Tests are not working on Windows CI")
      run
    }
  }

  testAsync("scala-priority") {
    for {
      _ <- server.initialize(
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
      )
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
}
