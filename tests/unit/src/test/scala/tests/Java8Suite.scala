package tests

import scala.meta.internal.metals.UserConfiguration

import com.google.gson.JsonPrimitive
import coursierapi.JvmManager

class Java8Suite extends BaseCompletionLspSuite("completion-java-8") {

  // We don't have access to JDK 8 on latest macOS M1
  override def munitIgnore: Boolean = isMacOS

  lazy val pathToJava8: String = JvmManager.create().get("8").toString()
  override def userConfig: UserConfiguration =
    UserConfiguration(javaHome = Some(pathToJava8))

  def escapedPathToJava8: String =
    new JsonPrimitive(pathToJava8).toString()

  test("java-8-completions") {
    cleanWorkspace()
    for {
      _ <- initialize(s"""|/metals.json
                          |{
                          |  "a": { "platformJavaHome": $escapedPathToJava8 }
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
        "",
      )
    } yield ()
  }

  test("java-8-workspace-completions") {
    cleanWorkspace()
    for {
      _ <- initialize(s"""|/metals.json
                          |{
                          |  "a": { "platformJavaHome": $escapedPathToJava8 }
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
