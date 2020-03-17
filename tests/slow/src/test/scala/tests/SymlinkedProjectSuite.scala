package tests
import java.nio.file.Files
import java.util.UUID
import scala.meta.io.AbsolutePath
import scala.meta.internal.metals.{BuildInfo => V}

class SymlinkedProjectSuite extends BaseLspSuite("symlinked-project") {
  test("definitions-from-other-file") {
    for {
      _ <- server.initialize(
        s"""|/project/build.properties
            |sbt.version=1.2.6
            |
            |/build.sbt
            |scalaVersion := "${V.scala212}"
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
