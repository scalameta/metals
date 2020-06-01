package tests
import java.nio.file.Files
import java.util.UUID

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.io.AbsolutePath

class SymlinkedProjectSuite extends BaseLspSuite("symlinked-project") {
  test("definitions-from-other-file") {
    for {
      _ <- server.initialize(
        s"""|/project/build.properties
            |sbt.version=${V.sbtVersion}
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
      server.assertReferenceDefinitionBijection()
    }
  }

  test("symlinked-source-directories") {
    createSymlinked(
      "a",
      "Bar.scala",
      """|class Bar {}
         |""".stripMargin
    )
    createSymlinked(
      "b",
      "Foo.scala",
      """|class Foo {
         |  val bar = new Bar()
         |}
         |""".stripMargin
    )
    for {
      _ <- server.initialize(
        s"""|/project/build.properties
            |sbt.version=${V.sbtVersion}
            |
            |/build.sbt
            |scalaVersion := "${V.scala212}"
            |
            |lazy val a = (project in file("a"))
            |lazy val b = (project in file("b")).dependsOn(a)
            |
        """.stripMargin
      )
      _ <- server.didOpen("b/src/main/scala/Foo.scala")
    } yield {
      assertNoDiagnostics()
      server.assertReferenceDefinitionBijection()
    }
  }

  def createSymlinked(
      dirname: String,
      filename: String,
      content: String
  ): String = {
    val projectDir = workspace.resolve(dirname)
    projectDir.createDirectories()
    val randomName = UUID.randomUUID().toString()
    val directory = workspace.resolve(randomName)
    directory.toNIO.toFile().deleteOnExit()

    val sourceRoot = directory.resolve("main/scala")
    sourceRoot.createDirectories()
    Files.createSymbolicLink(projectDir.resolve("src").toNIO, directory.toNIO)
    val target = sourceRoot.resolve(filename)
    target.writeText(content)
    randomName
  }

  override protected def createWorkspace(name: String): AbsolutePath = {
    val directory =
      super.createWorkspace(name).resolve(UUID.randomUUID().toString)

    val target = Files.createDirectories(directory.resolve("original").toNIO)
    val link = Files.createSymbolicLink(directory.resolve("link").toNIO, target)
    AbsolutePath(link)
  }
}
