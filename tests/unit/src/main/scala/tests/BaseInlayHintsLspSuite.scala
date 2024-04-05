package tests

import munit.Location
import munit.TestOptions
import tests.BaseLspSuite
import tests.TestInlayHints

abstract class BaseInlayHintsLspSuite(name: String, scalaVersion: String)
    extends BaseLspSuite(name) {
  def check(
      name: TestOptions,
      expected: String,
      config: Option[String] = None,
      dependencies: List[String] = Nil,
  )(implicit
      loc: Location
  ): Unit = {
    val initConfig = config
      .map(config => s"""|{"inlayHints": {
                         |$config
                         |}}
                         |""".stripMargin)
      .getOrElse("""|{"inlayHints": {
                    |  "inferredTypes": {"enable":true},
                    |  "implicitConversions": {"enable":true},
                    |  "implicitArguments": {"enable":true},
                    |  "typeParameters": {"enable":true},
                    |  "hintsInPatternMatch": {"enable":true}
                    |}}
                    |""".stripMargin)
    val fileName = "Main.scala"
    val code = TestInlayHints.removeInlayHints(expected)
    test(name) {
      for {
        _ <- initialize(
          s"""/metals.json
             |{"a":{
             |  "scalaVersion": "$scalaVersion",
             |  "libraryDependencies": ${toJsonArray(dependencies)}
             |}}
             |/a/src/main/scala/a/$fileName
             |$code
          """.stripMargin
        )
        _ <- server.didOpen(s"a/src/main/scala/a/$fileName")
        _ <- server.didChangeConfiguration(initConfig)
        _ <- server.assertInlayHints(
          s"a/src/main/scala/a/$fileName",
          code,
          expected,
          workspace,
        )
      } yield ()
    }
  }
}
