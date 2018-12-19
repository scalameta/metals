package tests

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

object FormattingSlowSuite extends BaseSlowSuite("formatting") {

  testAsync("basic") {
    for {
      _ <- server.initialize(
        """|
           |/metals.json
           |{
           |  "a": { }
           |}
           |/.scalafmt.conf
           |maxColumn = 100
           |/a/src/main/scala/a/Main.scala
           |object FormatMe {
           | val x = 1  }
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      textEdits <- server.formatting("a/src/main/scala/a/Main.scala")
      // check that the file has been formatted
      _ = assertNoDiff(
        textEdits.get(0).getNewText,
        """|object FormatMe {
           |  val x = 1
           |}""".stripMargin
      )
    } yield ()
  }

  testAsync("require-config-true") {
    for {
      _ <- server.initialize(
        """|
           |/metals.json
           |{
           |  "a": { }
           |}
           |/a/src/main/scala/a/Main.scala
           |object FormatMe {
           | val x = 1  }
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      textEdits <- server.formatting("a/src/main/scala/a/Main.scala")
      // check that the formatting request has been ignored
      _ = assert(textEdits.isEmpty)
    } yield ()
  }

  testAsync("require-config-false") {
    for {
      _ <- server.initialize(
        """|
           |/metals.json
           |{
           |  "a": { }
           |}
           |/a/src/main/scala/a/Main.scala
           |object FormatMe {
           | val x = 1  }
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ <- {
        val config = new JsonObject
        config.add("scalafmt-require-config-file", new JsonPrimitive(false))
        server.didChangeConfiguration(config.toString)
      }
      textEdits <- server.formatting("a/src/main/scala/a/Main.scala")
      // check that the file has been formatted
      _ = assertNoDiff(
        textEdits.get(0).getNewText,
        """|object FormatMe {
           |  val x = 1
           |}""".stripMargin
      )
    } yield ()
  }

  testAsync("custom-config-path") {
    for {
      _ <- server.initialize(
        """|
           |/metals.json
           |{
           |  "a": { }
           |}
           |/customconfig.conf
           |maxColumn=100
           |/a/src/main/scala/a/Main.scala
           |object FormatMe {
           | val x = 1  }
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ <- {
        val config = new JsonObject
        config.add(
          "scalafmt-config-path",
          new JsonPrimitive("customconfig.conf")
        )
        server.didChangeConfiguration(config.toString)
      }
      textEdits <- server.formatting("a/src/main/scala/a/Main.scala")
      // check that the file has been formatted
      _ = assertNoDiff(
        textEdits.get(0).getNewText,
        """|object FormatMe {
           |  val x = 1
           |}""".stripMargin
      )
    } yield ()
  }

}
