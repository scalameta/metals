package tests

import java.nio.file.Files

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.RecursivelyDelete
import munit.TestOptions
import java.nio.file.Paths

class AddPackageLspSuite extends BaseLspSuite("add-package") {

  check("single-level")(
    "a/src/main/scala/a/Main.scala",
    """|
       |package a
       |""".stripMargin
  )

  check("package-file")(
    "a/src/main/scala/a/package.scala",
    """|
       |package object a {
       |  
       |}
       |
       |""".stripMargin
  )

  check("package-file-multi")(
    "a/src/main/scala/a/b/c/package.scala",
    """|package a.b
       |
       |package object c {
       |  
       |}
       |
       |""".stripMargin
  )

  check("multilevel")(
    "a/src/main/scala/a/b/c/Main.scala",
    """
      |package a.b.c
        """.stripMargin
  )

  check("no-package")(
    "a/src/main/scala/Main.scala",
    ""
  )

  check("java-file")(
    "a/src/main/scala/Main.java",
    ""
  )

  def check(name: TestOptions)(
      fileToCreate: String,
      expectedContent: String
  ) = {
    test(name) {
      val parent = Paths.get(fileToCreate).getParent()
      cleanCompileCache("a")
      RecursivelyDelete(workspace.resolve("a"))
      Files.createDirectories(workspace.toNIO.resolve(parent))
      for {
        _ <- server.initialize(
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
          workspace.resolve(fileToCreate).readText,
          expectedContent
        )
      } yield ()
    }
  }
}
