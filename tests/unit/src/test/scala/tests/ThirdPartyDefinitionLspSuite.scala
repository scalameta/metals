package tests

import scala.concurrent.Future

class ThirdPartyDefinitionLspSuite
    extends BaseRangesSuite("third-party-definition") {

  check(
    "definition-from-third-party-library",
    """
      |/a/src/main/scala/a/Main.scala
      |package a
      |import org.kohsuke.args4j.Option
      |object Main {
      |  @Opt@@ion(name="-r",usage="recursively run something")
      |  val option: Boolean = false
      |}
      |""".stripMargin,
    customMetalsJson = Some(
      """
        |{
        |  "a": {
        |    "libraryDependencies": [
        |      "args4j:args4j:2.37"
        |    ],
        |    "skipSources": true
        |  }
        |}
        |""".stripMargin
    ),
  )

  check(
    "call-static-method",
    """
      |/a/src/main/scala/a/Main.scala
      |package a
      |import org.kohsuke.args4j.Sta@@rter
      |object Main {
      |  Starter.ma@@in(Array("--help"))
      |}
      |""".stripMargin,
    customMetalsJson = Some(
      """
        |{
        |  "a": {
        |    "libraryDependencies": [
        |      "args4j:args4j:2.37"
        |    ],
        |    "skipSources": true
        |  }
        |}
        |""".stripMargin
    ),
  )

  override def assertCheck(
      filename: String,
      edit: String,
      expected: Map[String, String],
      base: Map[String, String],
  ): Future[Unit] = {
    for {
      locations <- server.definition(filename, edit)
    } yield {
      assert(locations.nonEmpty, s"Expected definition location but got none")
      val loc = locations(0)
      assert(
        loc.getUri().endsWith(".class"),
        s"Expected definition location to have a .class URI, instead got: ${loc}",
      )
      assert(
        loc.getUri().contains("args4j"),
        s"Expected definition location to contain 'args4j', instead got: ${loc}",
      )
      // it's important the location is not 0,0, which would mean it wasn't found in decompiled code
      assert(
        loc.getRange().getStart().getLine() > 0,
        s"Expected definition location to start on a line greater than 0, instead got: ${loc}",
      )
    }
  }
}
