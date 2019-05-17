package tests.debug

import java.net.URI

object DebugSuite extends BaseDebugSuite {
  testDebug("launch")(
    layout = s"""/metals.json
                |{ "a": {} }
                |/a/src/main/scala/PrintHelloWorld.scala
                |object PrintHelloWorld {
                |  def main(args: Array[String]): Unit = {
                |    println("Hello, World!")
                |  }
                |}
                |""".stripMargin,
    act = server => {
      server.launch("a/src/main/scala/PrintHelloWorld.scala")
    },
    assert = (_, client) => {
      assertEquals(client.output.stderr, "")
      assertEquals(client.output.stdout, "Hello, World!\n")
    }
  )

  testDebug("working-dir")(
    layout = s"""|/metals.json
                 |{ "a": {} }
                 |
                 |/a/src/main/scala/CreateFileInWorkingDir.scala
                 |import java.nio.file.Paths
                 |
                 |object CreateFileInWorkingDir {
                 |  def main(args: Array[String]): Unit = {
                 |    val workspace = Paths.get("").toAbsolutePath
                 |    println(workspace.toUri)
                 |  }
                 |}
                 |""".stripMargin,
    act = server => {
      server.launch("a/src/main/scala/CreateFileInWorkingDir.scala")
    },
    assert = (_, client) => {
      val obtained = URI.create(client.output.stdout.trim)
      assertEquals(obtained, workspace.toURI)
    }
  )

  testDebug("disconnect")(
    layout = s"""|/metals.json
                 |{ "a": {} }
                 |
                 |/a/src/main/scala/DisconnectSession.scala
                 |object DisconnectSession {
                 |  def main(args: Array[String]): Unit = {
                 |    while(true){ Thread.sleep(200) }
                 |  }
                 |}
                 |""".stripMargin,
    act = server =>
      for {
        _ <- server.launch("a/src/main/scala/DisconnectSession.scala")
        _ <- server.disconnect()
      } yield (),
    assert = (exitCode, _) => {
      assert(exitCode != 0)
    }
  )
}
