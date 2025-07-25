package tests

import scala.util.control.NonFatal

import scala.meta.internal.metals.BloopJvmProperties
import scala.meta.internal.metals.Messages

import coursierapi.JvmManager
import munit.Location
import munit.TestOptions

trait JavaHomeChangeTest { self: BaseLspSuite =>

  def resolvePathToJava11(retries: Int = 3): String = try {
    JvmManager.create().get("11").toString()
  } catch {
    case NonFatal(t) =>
      scribe.error("Failed to resolve path to java 11", t)
      if (retries > 0) { resolvePathToJava11(retries - 1) }
      else throw t
  }
  def checkJavaHomeUpdate(
      name: TestOptions,
      makeLayout: String => String,
      errorMessage: String =
        """|a/src/main/scala/a/A.scala:2:8: error: object random is not a member of package java.util
           |did you mean Random?
           |import java.util.random.RandomGenerator
           |       ^^^^^^^^^^^^^^^^
           |a/src/main/scala/a/A.scala:4:13: error: not found: value RandomGenerator
           |  val gen = RandomGenerator.of("name")
           |            ^^^^^^^^^^^^^^^
           |""".stripMargin,
  )(implicit loc: Location): Unit = {
    test(name) {
      val pathToJava11 = resolvePathToJava11()
      val java11Code =
        """|package a
           |object O
           |""".stripMargin
      val java17Code =
        """|package a
           |import java.util.random.RandomGenerator
           |object O {
           |  val gen = RandomGenerator.of("name")
           |}
           |""".stripMargin
      cleanWorkspace()
      client.shouldReloadAfterJavaHomeUpdate =
        Messages.ProjectJavaHomeUpdate.restart
      for {
        _ <- initialize(
          makeLayout(
            s"""|/a/src/main/scala/a/A.scala
                |$java17Code
                |""".stripMargin
          )
        )
        _ <- server.didOpen("a/src/main/scala/a/A.scala")
        _ = assertNoDiagnostics()
        _ <- server.didChange("a/src/main/scala/a/A.scala")(_ => java11Code)
        _ <- server.didSave("a/src/main/scala/a/A.scala")
        _ = assertNoDiagnostics()
        _ <- server.server.onUserConfigUpdate(
          userConfig.copy(
            javaHome = Some(pathToJava11),
            bloopJvmProperties = BloopJvmProperties.Empty,
          )
        )
        _ <- server.didChange("a/src/main/scala/a/A.scala")(_ => java17Code)
        _ <- server.didSave("a/src/main/scala/a/A.scala")
        _ = assertNoDiff(
          client.workspaceDiagnostics,
          errorMessage,
        )
      } yield ()
    }
  }
}
