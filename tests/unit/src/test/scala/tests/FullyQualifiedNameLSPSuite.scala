package tests

import scala.meta.internal.metals.{BuildInfo => V}

import munit.Location
import munit.TestOptions

final class FullyQualifiedNameLSPSuite extends BaseLspSuite("fqn-") {

  check(
    "class-1",
    """|package a
       |
       |class B@@ob
       |""".stripMargin,
    "a.Bob",
  )

  check(
    "class-2",
    """|package a.b
       |
       |class B@@ob
       |""".stripMargin,
    "a.b.Bob",
  )

  check(
    "class-3",
    """|package a
       |package b
       |
       |class B@@ob
       |""".stripMargin,
    "a.b.Bob",
  )

  check(
    "nested-class",
    """|package a
       |
       |class Bob {
       |  class Al@@ice
       |}
       |""".stripMargin,
    "a.Bob#Alice",
  )

  check(
    "method-1",
    """|package a
       |
       |class Bob {
       |  def do@@Things: Int = 1
       |}
       |""".stripMargin,
    "a.Bob#doThings",
  )

  check(
    "method-2",
    """|package a
       |
       |class Bob {
       |  class Alice {
       |    def do@@Things: Int = 1
       |  }
       |}
       |""".stripMargin,
    "a.Bob#Alice#doThings",
  )

  check(
    "object",
    """|package a
       |
       |object B@@ob {
       |  def doThings: Int = 1
       |}
       |""".stripMargin,
    "a.Bob$",
  )

  check(
    "method-in-object",
    """|package a
       |
       |object Bob {
       |  def do@@Things: Int = 1
       |}
       |""".stripMargin,
    "a.Bob.doThings",
  )

  check(
    "package-object",
    """|package a
       |
       |package object B@@ob {
       |  def doThings: Int = 1
       |}
       |""".stripMargin,
    "a.Bob$",
  )

  check(
    "package-object",
    """|package a
       |
       |package object Bob {
       |  def do@@Things: Int = 1
       |}
       |""".stripMargin,
    "a.Bob.doThings",
  )

  def check(
      name: TestOptions,
      code: String,
      expectedFQN: String,
  )(implicit loc: Location): Unit = {
    val fileName = "Main.scala"
    test(name) {
      for {
        _ <- initialize(
          s"""/metals.json
             |{"a":{
             |  "scalaVersion": "${V.scala213}"
             |}}
             |/a/src/main/scala/a/$fileName
             |$code
            """.stripMargin
        )
        _ <- server.didOpen(s"a/src/main/scala/a/$fileName")
        (_, params) <- server.offsetParams(fileName, code, workspace)
        fqn <- server.headServer.copyFQNOfSymbol(params)
      } yield assertEquals(fqn, Some(expectedFQN))
    }
  }

}
