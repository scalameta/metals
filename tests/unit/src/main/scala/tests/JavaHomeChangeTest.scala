package tests

import scala.meta.internal.metals.Messages

import coursierapi.JvmManager
import munit.Location
import munit.TestOptions

trait JavaHomeChangeTest { self: BaseLspSuite =>
  val pathToJava11: String = JvmManager.create().get("11").toString()

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
        _ <- server.didSave("a/src/main/scala/a/A.scala")(identity)
        _ = assertNoDiagnostics()
        _ <- server.server.onUserConfigUpdate(
          userConfig.copy(javaHome = Some(pathToJava11))
        )
        _ <- server.didChange("a/src/main/scala/a/A.scala")(_ => java17Code)
        _ <- server.didSave("a/src/main/scala/a/A.scala")(identity)
        _ = assertNoDiff(
          client.workspaceDiagnostics,
          errorMessage,
        )
      } yield ()
    }
  }
}
