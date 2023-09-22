package tests

import java.io.File

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.Messages.MissingScalafmtConf
import scala.meta.internal.metals.Messages.MissingScalafmtVersion
import scala.meta.internal.metals.{BuildInfo => V}

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

class FormattingLspSuite extends BaseLspSuite("formatting") {
  test("basic") {
    for {
      _ <- initialize(
        s"""|/.scalafmt.conf
            |maxColumn = 100
            |version=${V.scalafmtVersion}
            |runner.dialect = scala213
            |/metals.json
            |{"a":{"scalaVersion" : ${V.scala213}}}
            |/a/src/main/scala/a/Main.scala
            |object FormatMe {
            | val x = 1  }
            |""".stripMargin,
        expectError = true,
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ <- server.formatting("a/src/main/scala/a/Main.scala")
      // check that the file has been formatted
      _ = assertNoDiff(
        server.bufferContents("a/src/main/scala/a/Main.scala"),
        """|object FormatMe {
           |  val x = 1
           |}""".stripMargin,
      )
    } yield ()
  }

  test("require-config") {
    for {
      _ <- initialize(
        s"""|/metals.json
            |{"a":{"scalaVersion" : ${V.scala213}}}
            |/a/src/main/scala/a/Main.scala
            |object FormatMe {
            | val x = 1  }
            |""".stripMargin,
        expectError = true,
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ <- server.formatting("a/src/main/scala/a/Main.scala")
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        MissingScalafmtConf.createScalafmtConfMessage,
      )
      // check that the formatting request has been ignored
      _ = assertNoDiff(
        server.bufferContents("a/src/main/scala/a/Main.scala"),
        """|object FormatMe {
           | val x = 1  }
           |""".stripMargin,
      )
    } yield ()
  }

  test("initial-config-scala2") {
    cleanWorkspace()
    client.showMessageRequestHandler = { params =>
      val expected = MissingScalafmtConf.createScalafmtConfMessage
      if (params.getMessage() == expected) {
        params.getActions().asScala.find(_ == MissingScalafmtConf.createFile)
      } else None
    }
    for {
      _ <- initialize(
        s"""|/metals.json
            |{"a":{"scalaVersion" : ${V.scala213}}}
            |/a/src/main/scala/a/Main.scala
            |object FormatMe {
            | val x = 1  }
            |""".stripMargin,
        expectError = true,
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ <- server.formatting("a/src/main/scala/a/Main.scala")
      _ = assertNoDiff(
        server.textContents(".scalafmt.conf"),
        s"""|version = "${V.scalafmtVersion}"
            |runner.dialect = scala213
            |""".stripMargin,
      )
    } yield ()
  }

  test("custom-config-path") {
    for {
      _ <- initialize(
        s"""|/metals.json
            |{"a":{"scalaVersion" : ${V.scala213}}}
            |/project/.scalafmt.conf
            |maxColumn=100
            |version=${V.scalafmtVersion}
            |runner.dialect = scala213
            |/a/src/main/scala/a/Main.scala
            |object FormatMe {
            | val x = 1  }
            |""".stripMargin,
        expectError = true,
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ <- {
        val config = new JsonObject
        config.add(
          "scalafmt-config-path",
          new JsonPrimitive(
            workspace.resolve("./project/.scalafmt.conf").toString()
          ),
        )
        server.didChangeConfiguration(config.toString)
      }
      _ <- server.formatting("a/src/main/scala/a/Main.scala")
      _ = assertNoDiff(
        server.bufferContents("a/src/main/scala/a/Main.scala"),
        """|object FormatMe {
           |  val x = 1
           |}""".stripMargin,
      )
    } yield ()
  }

  test("version") {
    for {
      _ <- initialize(
        s"""|.scalafmt.conf
            |version=${V.scalafmtVersion}
            |runner.dialect = scala213
            |maxColumn=30
            |trailingCommas=never
            |/metals.json
            |{"a":{"scalaVersion" : ${V.scala213}}}
            |/a/src/main/scala/a/Main.scala
            |case class User(
            |  name: String,
            |  age: Int,
            |)""".stripMargin,
        expectError = true,
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ <- server.formatting("a/src/main/scala/a/Main.scala")
      // check that the file has been formatted respecting the trailing comma config (new in 1.6.0)
      _ = assertNoDiff(
        server.bufferContents("a/src/main/scala/a/Main.scala"),
        """|case class User(
           |    name: String,
           |    age: Int
           |)""".stripMargin,
      )
    } yield ()
  }

  // Ignored because it's very slow, takes ~15s because of HTTP retries.
  test("download-error".ignore) {
    for {
      _ <- initialize(
        """|.scalafmt.conf
           |version="does-not-exist"
           |runner.dialect = scala213
           |/metals.json
           |{"a":{"scalaVersion" : ${V.scala213}}}
           |/Main.scala
           |object  Main
           |""".stripMargin,
        expectError = true,
      )
      _ <- server.formatting("Main.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """
          |.scalafmt.conf:1:1: error: failed to resolve Scalafmt version 'does-not-exist'
          |version="does-not-exist"
          |^^^^^^^^^^^^^^^^^^^^^^^^
        """.stripMargin,
      )
    } yield ()
  }

  test("config-error") {
    for {
      _ <- initialize(
        s"""|.scalafmt.conf
            |version=${V.scalafmtVersion}
            |runner.dialect = scala213
            |align=does-not-exist
            |/metals.json
            |{"a":{"scalaVersion" : ${V.scala213}}}
            |/Main.scala
            |object  Main
            |""".stripMargin,
        expectError = true,
      )
      _ <- server.didOpen(".scalafmt.conf")
      _ <- server.formatting("Main.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        s"""|.scalafmt.conf:1:1: error: Invalid config: $workspace${File.separator}.scalafmt.conf:3:0 error: Type mismatch;
            |  found    : String (value: "does-not-exist")
            |  expected : Object
            |align=does-not-exis
            |^
            |
            |> version=${V.scalafmtVersion}
            |> runner.dialect = scala213
            |> align=does-not-exist
        """.stripMargin,
      )
    } yield ()
  }

  test("filters") {
    for {
      _ <- initialize(
        s"""|/.scalafmt.conf
            |version=${V.scalafmtVersion}
            |runner.dialect = scala213
            |project.includeFilters = [
            |  ".*Spec.scala$$"
            |]
            |project.excludeFilters = [
            |  "UserSpec.scala$$"
            |]
            |/metals.json
            |{"a":{"scalaVersion" : ${V.scala213}}}
            |/Main.scala
            |  object   Main
            |/UserSpec.scala
            |  object   UserSpec
            |/ResourceSpec.scala
            |object ResourceSpec
            |""".stripMargin,
        expectError = true,
      )
      _ <- server.didOpen("Main.scala")
      _ <- server.formatting("Main.scala")
      // check Main.scala has been ignored (doesn't match includeFilters)
      _ = assertNoDiff(
        server.bufferContents("Main.scala"),
        "  object   Main",
      )
      _ <- server.didOpen("UserSpec.scala")
      _ <- server.formatting("UserSpec.scala")
      // check UserSpec.scala has been ignored (matches excludeFilters)
      _ = assertNoDiff(
        server.bufferContents("UserSpec.scala"),
        "  object   UserSpec",
      )
      _ <- server.didOpen("ResourceSpec.scala")
      _ <- server.formatting("ResourceSpec.scala")
      // check ResourceSpec.scala has been formatted
      _ = assertNoDiff(
        server.bufferContents("ResourceSpec.scala"),
        "object ResourceSpec",
      )
      _ = assertNoDiff(client.workspaceDiagnostics, "")
    } yield ()
  }

  test("sbt") {
    for {
      _ <- initialize(
        s"""|/.scalafmt.conf
            |version=${V.scalafmtVersion}
            |runner.dialect = scala213
            |/metals.json
            |{"a":{"scalaVersion" : ${V.scala213}}}
            |/project/plugins.sbt
            |  object   Plugins
            |""".stripMargin,
        expectError = true,
      )
      _ <- server.didOpen("project/plugins.sbt")
      _ <- server.formatting("project/plugins.sbt")
      // check plugins.sbt has been formatted
      _ = assertNoDiff(
        server.bufferContents("project/plugins.sbt"),
        "object Plugins",
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
      _ <- initialize(
        s"""|/.scalafmt.conf
            |maxColumn=40
            |/metals.json
            |{"a":{"scalaVersion" : ${V.scala213}}}
            |/Main.scala
            |object   Main
            |""".stripMargin,
        expectError = true,
      )
      _ <- server.didOpen("Main.scala")
      _ <- server.formatting("Main.scala")
      _ = {
        assertNoDiff(
          client.workspaceMessageRequests,
          s"""|${MissingScalafmtVersion.messageRequestMessage}
              |Loading Scalafmt""".stripMargin,
        )
        assertNoDiff(
          client.workspaceShowMessages,
          MissingScalafmtVersion.fixedVersion(isAgain = false).getMessage,
        )
        assertNoDiff(client.workspaceDiagnostics, "")
        // check file was formatted after version was inserted.
        assertNoDiff(
          server.bufferContents("Main.scala"),
          "object Main\n",
        )
        assertNoDiff(
          server.textContents(".scalafmt.conf"),
          s"""|version = "${V.scalafmtVersion}"
              |maxColumn=40
              |runner.dialect = scala213
              |""".stripMargin,
        )
      }
    } yield ()
  }

  test("rewrite-dialect-global") {
    cleanWorkspace()
    client.showMessageRequestHandler = { params =>
      params.getActions.asScala
        .find(_ == Messages.UpdateScalafmtConf.letUpdate)
    }
    for {
      _ <- initialize(
        """|/metals.json
           |{
           |  "a": {
           |     "scalaVersion": "3.0.0"
           |  }
           |}
           |/.scalafmt.conf
           |version = "2.7.5"
           |/a/src/main/scala/A.scala
           |object A
           |""".stripMargin
      )
      _ = assertNoDiff(
        server.textContents(".scalafmt.conf"),
        s"""|version = "${V.scalafmtVersion}"
            |runner.dialect = scala3
            |""".stripMargin,
      )

    } yield ()
  }

  test("rewrite-dialect-global-xsource3") {
    cleanWorkspace()
    client.showMessageRequestHandler = { params =>
      params.getActions.asScala
        .find(_ == Messages.UpdateScalafmtConf.letUpdate)
    }
    for {
      _ <- initialize(
        s"""|/metals.json
            |{
            |  "a": {
            |     "scalaVersion": "${V.scala213}",
            |     "scalacOptions": ["-Xsource:3"]
            |  }
            |}
            |/.scalafmt.conf
            |version = "2.7.5"
            |/a/src/main/scala/A.scala
            |object A
            |""".stripMargin
      )
      _ = assertNoDiff(
        server.textContents(".scalafmt.conf"),
        s"""|version = "${V.scalafmtVersion}"
            |runner.dialect = scala213source3
            |""".stripMargin,
      )

    } yield ()
  }

  test("rewrite-dialect-ignore-sbt") {
    cleanWorkspace()
    client.showMessageRequestHandler = { params =>
      params.getActions.asScala
        .find(_ == Messages.UpdateScalafmtConf.letUpdate)
    }
    for {
      _ <- initialize(
        s"""|/metals.json
            |{
            |  "a": {
            |     "scalaVersion": "${V.scala212}"
            |  },
            |  "b": {
            |     "scalaVersion": "${V.scala212}",
            |     "scalacOptions": ["-Xsource:3"],
            |     "sbtVersion": "1.6.0-RC2"
            |  }
            |}
            |/.scalafmt.conf
            |version = "${V.scalafmtVersion}"
            |runner.dialect = scala211
            |/a/src/main/scala/A.scala
            |object A
            |/b/src/main/scala/B.scala
            |object B
            |""".stripMargin
      )
      _ = assertNoDiff(
        server.textContents(".scalafmt.conf"),
        s"""|version = "${V.scalafmtVersion}"
            |runner.dialect = scala212
            |""".stripMargin,
      )

    } yield ()
  }

  test("rewrite-dialect-file-override") {
    cleanWorkspace()
    client.showMessageRequestHandler = { params =>
      params.getActions.asScala
        .find(_ == Messages.UpdateScalafmtConf.letUpdate)
    }
    for {
      _ <- initialize(
        s"""|/metals.json
            |{
            |  "a": {
            |     "scalaVersion": "3.0.0"
            |  },
            |  "b" : {
            |     "scalaVersion": "${V.scala213}",
            |     "scalacOptions": ["-Xsource:3"]
            |  }
            |}
            |/.scalafmt.conf
            |version = "2.7.5"
            |/a/src/main/scala/A.scala
            |object A
            |/b/src/main/scala/B.scala
            |object B
            |""".stripMargin
      )
      _ = assertNoDiff(
        server.textContents(".scalafmt.conf"),
        s"""|version = "${V.scalafmtVersion}"
            |runner.dialect = scala213source3
            |fileOverride {
            |  "glob:**/a/src/main/scala/**" {
            |     runner.dialect = scala3
            |  }
            |}
            |""".stripMargin,
      )

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
      _ <- initialize(
        s"""|/.scalafmt.conf
            |maxColumn=40
            |/metals.json
            |{"a":{"scalaVersion" : ${V.scala213}}}
            |/Main.scala
            |object   Main
            |""".stripMargin,
        expectError = true,
      )
      _ <- server.didOpen("Main.scala")
      _ <- server.formatting("Main.scala")
      _ = {
        assertNoDiff(
          client.workspaceMessageRequests,
          MissingScalafmtVersion.messageRequestMessage,
        )
        assertNoDiff(
          client.workspaceShowMessages,
          "",
        )
        // check file was not formatted because version is still missing.
        assertNoDiff(
          server.bufferContents("Main.scala"),
          "object   Main",
        )
        assertNoDiff(
          server.textContents(".scalafmt.conf"),
          "maxColumn=40",
        )
        assertNoDiff(
          client.workspaceDiagnostics,
          s"""|.scalafmt.conf:1:1: error: missing setting 'version'. To fix this problem, add the following line to .scalafmt.conf: 'version=${V.scalafmtVersion}'.
              |maxColumn=40
              |^^^^^^^^^^^^
              |""".stripMargin,
        )
      }
    } yield ()
  }
}
