package tests

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import scala.meta.internal.metals.Messages.MissingScalafmtConf
import scala.meta.internal.metals.Messages.MissingScalafmtVersion

object FormattingSlowSuite extends BaseSlowSuite("formatting") {

  testAsync("basic") {
    for {
      _ <- server.initialize(
        """|/.scalafmt.conf
           |maxColumn = 100
           |version=1.5.1
           |/a/src/main/scala/a/Main.scala
           |object FormatMe {
           | val x = 1  }
           |""".stripMargin,
        expectError = true
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ <- server.formatting("a/src/main/scala/a/Main.scala")
      // check that the file has been formatted
      _ = assertNoDiff(
        server.bufferContent("a/src/main/scala/a/Main.scala"),
        """|object FormatMe {
           |  val x = 1
           |}""".stripMargin
      )
    } yield ()
  }

  testAsync("require-config") {
    for {
      _ <- server.initialize(
        """|/a/src/main/scala/a/Main.scala
           |object FormatMe {
           | val x = 1  }
           |""".stripMargin,
        expectError = true
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ <- server.formatting("a/src/main/scala/a/Main.scala")
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        MissingScalafmtConf.createScalafmtConfMessage
      )
      // check that the formatting request has been ignored
      _ = assertNoDiff(
        server.bufferContent("a/src/main/scala/a/Main.scala"),
        """|object FormatMe {
           | val x = 1  }
           |""".stripMargin
      )
    } yield ()
  }

  testAsync("custom-config-path") {
    for {
      _ <- server.initialize(
        """|/project/.scalafmt.conf
           |maxColumn=100
           |version=1.5.1
           |/a/src/main/scala/a/Main.scala
           |object FormatMe {
           | val x = 1  }
           |""".stripMargin,
        expectError = true
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ <- {
        val config = new JsonObject
        config.add(
          "scalafmt-config-path",
          new JsonPrimitive("project/.scalafmt.conf")
        )
        server.didChangeConfiguration(config.toString)
      }
      _ <- server.formatting("a/src/main/scala/a/Main.scala")
      _ = assertNoDiff(
        server.bufferContent("a/src/main/scala/a/Main.scala"),
        """|object FormatMe {
           |  val x = 1
           |}""".stripMargin
      )
    } yield ()
  }

  testAsync("version") {
    for {
      _ <- server.initialize(
        """|.scalafmt.conf
           |version=1.6.0-RC4
           |maxColumn=30
           |trailingCommas=never
           |/a/src/main/scala/a/Main.scala
           |case class User(
           |  name: String,
           |  age: Int,
           |)""".stripMargin,
        expectError = true
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ <- server.formatting("a/src/main/scala/a/Main.scala")
      // check that the file has been formatted respecting the trailing comma config (new in 1.6.0)
      _ = assertNoDiff(
        server.bufferContent("a/src/main/scala/a/Main.scala"),
        """|case class User(
           |    name: String,
           |    age: Int
           |)""".stripMargin
      )
    } yield ()
  }

  testAsync("download-error") {
    for {
      _ <- server.initialize(
        """|.scalafmt.conf
           |version="does-not-exist"
           |/Main.scala
           |object  Main
           |""".stripMargin,
        expectError = true
      )
      _ <- server.formatting("Main.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """
          |.scalafmt.conf:1:1: error: failed to resolve Scalafmt version 'does-not-exist'
          |version="does-not-exist"
          |^^^^^^^^^^^^^^^^^^^^^^^^
        """.stripMargin
      )
    } yield ()
  }

  testAsync("config-error") {
    for {
      _ <- server.initialize(
        """|.scalafmt.conf
           |version=1.5.1
           |align=does-not-exist
           |/Main.scala
           |object  Main
           |""".stripMargin,
        expectError = true
      )
      _ <- server.didOpen(".scalafmt.conf")
      _ <- server.formatting("Main.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|.scalafmt.conf:1:1: error: Type mismatch;
           |  found    : String (value: "does-not-exist")
           |  expected : Align
           |> version=1.5.1
           |> align=does-not-exist
        """.stripMargin
      )
    } yield ()
  }

  testAsync("filters") {
    for {
      _ <- server.initialize(
        """|/.scalafmt.conf
           |version=1.5.1
           |project.includeFilters = [
           |  ".*Spec\\.scala$"
           |]
           |project.excludeFilters = [
           |  "UserSpec\\.scala$"
           |]
           |/Main.scala
           |  object   Main
           |/UserSpec.scala
           |  object   UserSpec
           |/ResourceSpec.scala
           |object ResourceSpec
           |""".stripMargin,
        expectError = true
      )
      _ <- server.didOpen("Main.scala")
      _ <- server.formatting("Main.scala")
      // check Main.scala has been ignored (doesn't match includeFilters)
      _ = assertNoDiff(
        server.bufferContent("Main.scala"),
        "  object   Main"
      )
      _ <- server.didOpen("UserSpec.scala")
      _ <- server.formatting("UserSpec.scala")
      // check UserSpec.scala has been ignored (matches excludeFilters)
      _ = assertNoDiff(
        server.bufferContent("UserSpec.scala"),
        "  object   UserSpec"
      )
      _ <- server.didOpen("ResourceSpec.scala")
      _ <- server.formatting("ResourceSpec.scala")
      // check ResourceSpec.scala has been formatted
      _ = assertNoDiff(
        server.bufferContent("ResourceSpec.scala"),
        "object ResourceSpec"
      )
      _ = assertNoDiff(client.workspaceDiagnostics, "")
    } yield ()
  }

  testAsync("sbt") {
    for {
      _ <- server.initialize(
        """|/.scalafmt.conf
           |version=1.5.1
           |/project/plugins.sbt
           |  object   Plugins
           |""".stripMargin,
        expectError = true
      )
      _ <- server.didOpen("project/plugins.sbt")
      _ <- server.formatting("project/plugins.sbt")
      // check plugins.sbt has been formatted
      _ = assertNoDiff(
        server.bufferContent("project/plugins.sbt"),
        "object Plugins"
      )
    } yield ()
  }

  testAsync("missing-version") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        """|/.scalafmt.conf
           |maxColumn=40
           |/Main.scala
           |object   Main
           |""".stripMargin,
        expectError = true
      )
      _ <- server.didOpen("Main.scala")
      _ <- server.formatting("Main.scala")
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        MissingScalafmtVersion.messageRequestMessage
      )
      _ = assertNoDiff(
        client.workspaceShowMessages,
        MissingScalafmtVersion.fixedVersion.getMessage
      )
      _ = assertNoDiff(client.workspaceDiagnostics, "")
      // check file was not formatted because version was missing.
      _ = assertNoDiff(
        server.bufferContent("Main.scala"),
        "object   Main"
      )
      _ <- server.formatting("Main.scala")
      // check file was formatted now that version has been added.
      _ = assertNoDiff(
        server.bufferContent("Main.scala"),
        "object Main\n"
      )
    } yield ()
  }

}
