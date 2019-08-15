package tests
import java.nio.file.Files
import java.util.UUID
import scala.meta.io.AbsolutePath

object SymlinkedProjectSuite extends BaseSlowSuite("symlinked-project") {
  testAsync("definitions-from-other-file") {
    for {
      _ <- server.initialize(
        """|/project/build.properties
           |sbt.version=1.2.6
           |
           |/build.sbt
           |scalaVersion := "2.12.9"
           |
           |/src/main/scala/Foo.scala
           |class Foo
           |
           |/src/main/scala/Bar.scala
           |object Bar{
           |  val foo = new Foo
           |}
        """.stripMargin
      )
      _ <- server.didOpen("src/main/scala/Bar.scala")
    } yield {
      assertNoDiagnostics()
      assertNoDiff(
        server.workspaceDefinitions,
        """|/../original/src/main/scala/Bar.scala
           |object Bar/*L0*/{
           |  val foo/*L1*/ = new Foo/*Foo.scala:0*/
           |}
           |
           |""".stripMargin
      )
    }
  }

  override protected def createWorkspace(name: String): AbsolutePath = {
    val directory =
      super.createWorkspace(name).resolve(UUID.randomUUID().toString)

    val target = Files.createDirectories(directory.resolve("original").toNIO)
    val link = Files.createSymbolicLink(directory.resolve("link").toNIO, target)
    AbsolutePath(link)
  }
}
