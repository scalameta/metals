package tests.refactor

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import scala.meta.internal.metals.Messages.MissingScalafmtConf
import scala.meta.internal.metals.Messages.MissingScalafmtVersion
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.Messages
import tests.BaseLspSuite

class FormattingLspSuite extends BaseLspSuite("formatting") {

  test("basic") {
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
        server.bufferContents("a/src/main/scala/a/Main.scala"),
        """|object FormatMe {
           |  val x = 1
           |}""".stripMargin
      )
    } yield ()
  }

  test("require-config") {
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
        server.bufferContents("a/src/main/scala/a/Main.scala"),
        """|object FormatMe {
           | val x = 1  }
           |""".stripMargin
      )
    } yield ()
  }

  test("custom-config-path") {
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
        server.bufferContents("a/src/main/scala/a/Main.scala"),
        """|object FormatMe {
           |  val x = 1
           |}""".stripMargin
      )
    } yield ()
  }

  test("version") {
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
        server.bufferContents("a/src/main/scala/a/Main.scala"),
        """|case class User(
           |    name: String,
           |    age: Int
           |)""".stripMargin
      )
    } yield ()
  }

  // Ignored because it's very slow, takes ~15s because of HTTP retries.
  test("download-error".ignore) {
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

  test("config-error") {
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

  test("filters") {
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
        server.bufferContents("Main.scala"),
        "  object   Main"
      )
      _ <- server.didOpen("UserSpec.scala")
      _ <- server.formatting("UserSpec.scala")
      // check UserSpec.scala has been ignored (matches excludeFilters)
      _ = assertNoDiff(
        server.bufferContents("UserSpec.scala"),
        "  object   UserSpec"
      )
      _ <- server.didOpen("ResourceSpec.scala")
      _ <- server.formatting("ResourceSpec.scala")
      // check ResourceSpec.scala has been formatted
      _ = assertNoDiff(
        server.bufferContents("ResourceSpec.scala"),
        "object ResourceSpec"
      )
      _ = assertNoDiff(client.workspaceDiagnostics, "")
    } yield ()
  }

  test("sbt") {
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
        server.bufferContents("project/plugins.sbt"),
        "object Plugins"
      )
    } yield ()
  }

  test("missing-version") {
    cleanWorkspace()
    client.showMessageRequestHandler = { params =>
      if (MissingScalafmtVersion.isMissingScalafmtVersion(params)) {
        params.getActions.asScala
          .find(_ == MissingScalafmtVersion.changeVersion)
      } else {
        None
      }
    }
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
      _ = {
        assertNoDiff(
          client.workspaceMessageRequests,
          MissingScalafmtVersion.messageRequestMessage
        )
        assertNoDiff(
          client.workspaceShowMessages,
          MissingScalafmtVersion.fixedVersion(isAgain = false).getMessage
        )
        assertNoDiff(client.workspaceDiagnostics, "")
        // check file was formatted after version was inserted.
        assertNoDiff(
          server.bufferContents("Main.scala"),
          "object Main\n"
        )
        assertNoDiff(
          server.textContents(".scalafmt.conf"),
          s"""|version = "${V.scalafmtVersion}"
              |maxColumn=40
              |""".stripMargin
        )
      }
    } yield ()
  }

  test("version-not-now") {
    cleanWorkspace()
    client.showMessageRequestHandler = { params =>
      if (MissingScalafmtVersion.isMissingScalafmtVersion(params)) {
        params.getActions.asScala.find(_ == Messages.notNow)
      } else {
        None
      }
    }
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
      _ = {
        assertNoDiff(
          client.workspaceMessageRequests,
          MissingScalafmtVersion.messageRequestMessage
        )
        assertNoDiff(
          client.workspaceShowMessages,
          ""
        )
        // check file was not formatted because version is still missing.
        assertNoDiff(
          server.bufferContents("Main.scala"),
          "object   Main"
        )
        assertNoDiff(
          server.textContents(".scalafmt.conf"),
          "maxColumn=40"
        )
        assertNoDiff(
          client.workspaceDiagnostics,
          s"""|.scalafmt.conf:1:1: error: missing setting 'version'. To fix this problem, add the following line to .scalafmt.conf: 'version=${V.scalafmtVersion}'.
              |maxColumn=40
              |^^^^^^^^^^^^
              |""".stripMargin
        )
      }
    } yield ()
  }

  test("workspace-folder") {
    for {
      _ <- server.initialize(
        """|/a/.scalafmt.conf
           |version="2.0.0-RC4"
           |maxColumn=10
           |/Main.scala
           |object   Main { val x = 2 }
           |""".stripMargin,
        expectError = true,
        workspaceFolders = List("a")
      )
      _ <- server.didOpen("Main.scala")
      _ <- server.formatting("Main.scala")
      _ = assertNoDiff(
        server.bufferContents("Main.scala"),
        """object Main {
          |  val x =
          |    2
          |}
          |""".stripMargin
      )
    } yield ()
  }

}
