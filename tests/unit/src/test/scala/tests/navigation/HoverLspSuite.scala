package tests.navigation

import scala.meta.internal.metals.Directories
import tests.BaseLspSuite
import tests.TestHovers

class HoverLspSuite extends BaseLspSuite("hover") with TestHovers {

  test("basic") {
    for {
      _ <- server.initialize(
        """/metals.json
          |{"a":{}}
        """.stripMargin
      )
      _ <- server.assertHover(
        "a/src/main/scala/a/Main.scala",
        """
          |object Main {
          |  Option(1).he@@ad
          |}""".stripMargin,
        """override def head: Int""".hover
      )
    } yield ()
  }

  test("dependencies") {
    for {
      _ <- server.initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |  println(42)
          |}
        """.stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ = server.workspaceDefinitions // triggers goto definition, creating Predef.scala
      _ <- server.assertHover(
        "scala/Predef.scala",
        """
          |object Main {
          |  Option(1).he@@ad
          |}""".stripMargin,
        """override def head: Int""".hover,
        root = workspace.resolve(Directories.readonly)
      )
    } yield ()
  }

}
