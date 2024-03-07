package tests

import scala.meta.internal.metals.UserConfiguration

import coursierapi.JvmManager

class Java8Suite extends BaseCompletionLspSuite("completion-java-8") {
  val pathToJava8: String = JvmManager.create().get("8").toString()
  override def userConfig: UserConfiguration =
    UserConfiguration(javaHome = Some(pathToJava8))

  test("java-8-completions") {
    cleanWorkspace()
    for {
      _ <- initialize(s"""|/metals.json
                          |{
                          |  "a": { "platformJavaHome": "$pathToJava8" }
                          |}
                          |/.metals/bsp.trace.json
                          |
                          |/a/src/main/scala/a/A.scala
                          |import java.nio.file.Path
                          |object A {
                          |  // @@
                          |}
                          |""".stripMargin)
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ = assertNoDiagnostics()
      _ <- assertCompletion(
        "val p = Path.o@@",
        """|""".stripMargin,
      )
    } yield ()
  }

  test("java-8-workspace-completions") {
    cleanWorkspace()
    for {
      _ <- initialize(s"""|/metals.json
                          |{
                          |  "a": { "platformJavaHome": "$pathToJava8" }
                          |}
                          |/a/src/main/scala/a/A.scala
                          |object A {
                          |  // @@
                          |}
                          |""".stripMargin)
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ = assertNoDiagnostics()
      _ <- assertCompletion(
        "HttpClient@@",
        "",
      )
    } yield ()
  }

}
