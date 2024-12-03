package tests

import scala.meta.internal.metals.BuildInfo

class ClassloaderModulesSupport
    extends BaseLspSuite("classloader-modules-")
    with TestHovers {

  test("basic") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": { "scalaVersion": "${BuildInfo.scala3}" },
           |  "b": { "dependsOn": ["a"], "scalaVersion": "${BuildInfo.scala3}" }
           |}
           |
           |/a/src/main/scala/a/A.scala
           |package a
           |
           |import scala.quoted._
           |
           |object MacroImpl:
           |  transparent inline def make = $${ makeImpl }
           |
           |  private def makeImpl(using Quotes): Expr[Unit] =
           |    Class.forName("java.sql.Driver")
           |    '{()}
           |
           |/b/src/main/scala/b/B.scala
           |package b
           |
           |object B:
           |  a.MacroImpl.make
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ <- server.didOpen("b/src/main/scala/b/B.scala")
      _ = assertNoDiagnostics()
      _ <- server.assertHover(
        "b/src/main/scala/b/B.scala",
        """
          |/b/src/main/scala/b/B.scala
          |package b
          |
          |object B:
          |  a.MacroImpl.ma@@ke
          |""".stripMargin,
        """|
           |inline transparent def make: Unit
           |""".stripMargin.hover,
      )
    } yield ()
  }
}
