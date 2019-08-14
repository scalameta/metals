package tests

import java.nio.file.Files

import scala.meta.internal.metals.RecursivelyDelete
import scala.meta.internal.metals.MetalsEnrichments._

object AddPackageSlowSuite extends BaseSlowSuite("add-package") {

  testAsync("single-level") {
    cleanCompileCache("a")
    RecursivelyDelete(workspace.resolve("a"))
    Files.createDirectories(
      workspace.resolve("a/src/main/scala/a").toNIO
    )
    for {
      _ <- server.initialize(
        """|/metals.json
           |{
           |  "a": { }
           |}
        """.stripMargin
      )
      _ = workspace
        .resolve("a/src/main/scala/a/Main.scala")
        .toFile
        .createNewFile()
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ = assertNoDiff(
        workspace.resolve("a/src/main/scala/a/Main.scala").readText,
        """
          |package a
        """.stripMargin
      )
    } yield ()
  }

  testAsync("package-file") {
    cleanCompileCache("a")
    RecursivelyDelete(workspace.resolve("a"))
    Files.createDirectories(
      workspace.resolve("a/src/main/scala/a").toNIO
    )
    for {
      _ <- server.initialize(
        """|/metals.json
           |{
           |  "a": { }
           |}
        """.stripMargin
      )
      _ = workspace
        .resolve("a/src/main/scala/a/package.scala")
        .toFile
        .createNewFile()
      _ <- server.didOpen("a/src/main/scala/a/package.scala")
      _ = assertNoDiff(
        workspace.resolve("a/src/main/scala/a/package.scala").readText,
        """|package object a {
           |  
           |}
           |""".stripMargin
      )
    } yield ()
  }

  testAsync("package-file-multi") {
    cleanCompileCache("a")
    RecursivelyDelete(workspace.resolve("a"))
    Files.createDirectories(
      workspace.resolve("a/src/main/scala/a/b/c").toNIO
    )
    for {
      _ <- server.initialize(
        """|/metals.json
           |{
           |  "a": { }
           |}
        """.stripMargin
      )
      _ = workspace
        .resolve("a/src/main/scala/a/b/c/package.scala")
        .toFile
        .createNewFile()
      _ <- server.didOpen("a/src/main/scala/a/b/c/package.scala")
      _ = assertNoDiff(
        workspace.resolve("a/src/main/scala/a/b/c/package.scala").readText,
        """|package a.b
           |
           |package object c {
           |  
           |}
           |""".stripMargin
      )
    } yield ()
  }

  testAsync("multilevel") {
    cleanCompileCache("a")
    RecursivelyDelete(workspace.resolve("a"))
    Files.createDirectories(
      workspace.resolve("a/src/main/scala/a/b/c").toNIO
    )
    for {
      _ <- server.initialize(
        """|/metals.json
           |{
           |  "a": { }
           |}
        """.stripMargin
      )
      _ = workspace
        .resolve("a/src/main/scala/a/b/c/Main.scala")
        .toFile
        .createNewFile()
      _ <- server.didOpen("a/src/main/scala/a/b/c/Main.scala")
      _ = assertNoDiff(
        workspace.resolve("a/src/main/scala/a/b/c/Main.scala").readText,
        """
          |package a.b.c
        """.stripMargin
      )
    } yield ()
  }

  testAsync("no-package") {
    cleanCompileCache("a")
    RecursivelyDelete(workspace.resolve("a"))
    Files.createDirectories(
      workspace.resolve("a/src/main/scala").toNIO
    )
    for {
      _ <- server.initialize(
        """|/metals.json
           |{
           |  "a": { }
           |}
        """.stripMargin
      )
      _ = workspace
        .resolve("a/src/main/scala/Main.scala")
        .toFile
        .createNewFile()
      _ <- server.didOpen("a/src/main/scala/Main.scala")
      _ = assertNoDiff(
        workspace.resolve("a/src/main/scala/Main.scala").readText,
        ""
      )
    } yield ()
  }

  testAsync("java-file") {
    cleanCompileCache("a")
    RecursivelyDelete(workspace.resolve("a"))
    Files.createDirectories(
      workspace.resolve("a/src/main/java/a").toNIO
    )
    for {
      _ <- server.initialize(
        """|/metals.json
           |{
           |  "a": { }
           |}
        """.stripMargin
      )
      _ = workspace
        .resolve("a/src/main/java/a/Main.java")
        .toFile
        .createNewFile()
      _ <- server.didOpen("a/src/main/java/a/Main.java")
      _ = assertNoDiff(
        workspace.resolve("a/src/main/java/a/Main.java").readText,
        ""
      )
    } yield ()
  }
}
