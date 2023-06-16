package tests

import java.nio.file.Files
import java.nio.file.Paths

import scala.meta.internal.metals.RecursivelyDelete

import munit.TestOptions

class AddPackageLspSuite extends BaseLspSuite("add-package") {

  check("single-level")(
    "a/src/main/scala/a/Main.scala",
    """|package a
       |
       |""".stripMargin,
  )

  check("script")(
    "a/src/main/scala/a/Main.sc",
    "",
  )

  check("worksheet")(
    "a/src/main/scala/a/Main.worksheet.sc",
    "",
  )

  check("package-file")(
    "a/src/main/scala/a/package.scala",
    """|package object a {
       |  
       |}
       |""".stripMargin,
  )

  check("package-file-multi")(
    "a/src/main/scala/a/b/c/package.scala",
    """|package a.b
       |
       |package object c {
       |  
       |}
       |""".stripMargin,
  )

  check("multilevel")(
    "a/src/main/scala/a/b/c/Main.scala",
    """|package a.b.c
       |
       |""".stripMargin,
  )

  check("no-package")(
    "a/src/main/scala/Main.scala",
    "",
  )

  check("java-file")(
    "a/src/main/scala/Main.java",
    "",
  )

  check("escaped-name")(
    "a/src/main/scala/type/a/this/Main.scala",
    """|package `type`.a.`this`
       |
       |""".stripMargin,
  )

  check("escaped-name-object")(
    "a/src/main/scala/type/a/this/package.scala",
    """|package `type`.a
       |
       |package object `this` {
       |  
       |}
       |""".stripMargin,
  )

  def check(name: TestOptions)(
      fileToCreate: String,
      expectedContent: String,
  ): Unit = {
    test(name) {
      val parent = Paths.get(fileToCreate).getParent()
      cleanCompileCache("a")
      RecursivelyDelete(workspace.resolve("a"))
      Files.createDirectories(workspace.toNIO.resolve(parent))
      for {
        _ <- initialize(
          """|/metals.json
             |{
             |  "a": { }
             |}
        """.stripMargin
        )
        _ =
          workspace
            .resolve(fileToCreate)
            .toFile
            .createNewFile()
        _ <- server.didOpen(fileToCreate)
        _ = assertNoDiff(
          client.buffers.get(workspace.resolve(fileToCreate)).getOrElse(""),
          expectedContent,
        )
      } yield ()
    }
  }
}
