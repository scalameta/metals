package tests

class BuildTargetsLspSuite
    extends BaseLspSuite("build-targets")
    with TestHovers {

  override def munitIgnore: Boolean = isWindows

  test("scala-priority") {
    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "2.10.6",
           |    "libraryDependencies": ["com.lihaoyi::sourcecode:0.1.7"],
           |    "additionalSources": [ "shared/Main.scala" ]
           |  },
           |  "b": {
           |    "scalaVersion": "${BuildInfo.scalaVersion}",
           |    "libraryDependencies": ["com.lihaoyi::sourcecode:0.2.7"],
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
        """val value: Int""".hover,
      )
    } yield ()
  }
}
