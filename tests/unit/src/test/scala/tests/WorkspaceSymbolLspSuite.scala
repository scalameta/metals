package tests

import java.nio.file.Files
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.WorkspaceSymbolParams
import scala.concurrent.Future
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.Messages
import MetalsTestEnrichments._

class WorkspaceSymbolLspSuite extends BaseLspSuite("workspace-symbol") {

  test("basic") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        """
          |/metals.json
          |{
          |  "a": {}
          |}
          |/a/src/main/scala/a/b/A.scala
          |package a
          |package b
          |
          |object PazQux { // Intentionally obscure name to not conflict with dependency names.
          |
          |  class Inner {
          |    def bar = {
          |      val x = 1
          |    }
          |  }
          |}
          |/a/src/main/scala/a/B.scala
          |package a
          |class B
          |""".stripMargin
      )
      _ = assertNoDiff(
        server.workspaceSymbol("PazQux.Inner"),
        "a.b.PazQux.Inner"
      )
      _ = assertNoDiff(
        server.workspaceSymbol("a.b.PazQux"),
        "a.b.PazQux"
      )
      _ <- server.didSave("a/src/main/scala/a/B.scala")(
        _.replaceAllLiterally("class B", "  class HaddockBax")
      )
      _ = assertNoDiff(
        server.workspaceSymbol("Had"),
        "a.HaddockBax"
      )
    } yield ()
  }

  test("pre-initialized") {
    var request = Future.successful[List[List[SymbolInformation]]](Nil)
    for {
      _ <- server.initialize(
        """
          |/metals.json
          |{
          |  "a": {}
          |}
          |/a/src/main/scala/a/b/A.scala
          |package a
          |package b
          |
          |object PazQux {
          |  class Inner
          |}
          |""".stripMargin,
        preInitialized = { () =>
          request = Future
            .sequence(1.to(10).map { _ =>
              server.server
                .workspaceSymbol(new WorkspaceSymbolParams("PazQux.I"))
                .asScala
                .map(_.asScala.toList)
            })
            .map(_.toList)
          Thread.sleep(10) // take a moment to delay
          Future.successful(())
        }
      )
      results <- request
      _ = {
        val obtained = results.distinct
        // Assert that all results are the same, makesure we don't return empty/incomplete results
        // before indexing is complete.
        assert(obtained.length == 1)
        assert(obtained.head.head.fullPath == "a.b.PazQux.Inner")
      }
    } yield ()
  }

  test("duplicate") {
    for {
      _ <- server.initialize(
        """
          |/metals.json
          |{
          |  "a": {},
          |  "b": {"dependsOn":["a"]}
          |}
          |/a/src/main/scala/a/A.scala
          |package a
          |
          |case class UserBaxx(name: String, age: Int)
          |object UserBaxx
          |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ = assertNoDiff(
        server.workspaceSymbol("UserBax", includeKind = true),
        """a.UserBaxx Class
          |a.UserBaxx Object
          |""".stripMargin
      )
    } yield ()
  }

  test("dependencies") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        """
          |/metals.json
          |{
          |  "a": {}
          |}
          |/a/src/main/scala/a/A.scala
          |package a
          |
          |object User {
          |  val x: Option[Int] = None
          |}
          |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ = server.workspaceSymbol("scala.None")
      option = ".metals/readonly/scala/Option.scala"
      _ <- server.didOpen(option)
      references <- server.references(option, "object None")
      _ = assertNoDiff(
        references,
        """|a/src/main/scala/a/A.scala:4:24: info: reference
           |  val x: Option[Int] = None
           |                       ^^^^
           |""".stripMargin
      )
    } yield ()
  }

  test("workspace-only") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        """
          |/metals.json
          |{"a": {}}
          |/a/src/main/scala/a/A.scala
          |package a
          |
          |object Properties
          |object MetalsUniqueName
          |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      // Assert "Properties" searches only workspace, not libraries.
      _ = assertNoDiff(
        server.workspaceSymbol("Properties"),
        s"""|a.Properties
            |${Messages.WorkspaceSymbolDependencies.title}
            |""".stripMargin
      )
      _ = {
        // Assert "Properties;" searches workspace + libraries
        val file = server.workspaceSymbol("Properties;")
        assert(file.startsWith("a.Properties"))
        assertContains(file, "scala.util.Properties")
        assertNotContains(
          file,
          Messages.WorkspaceSymbolDependencies.title
        )
        // Assert we automatically fallback to library dependencies on no match.
        assertContains(
          server.workspaceSymbol("Future"),
          "scala.concurrent.Future"
        )
        // Assert we don't suggest to "add ';' to search library dependencies"
        // because "MetalsUniqueName" has no matches in library dependencies.
        assertNoDiff(
          server.workspaceSymbol("MetalsUniqueName"),
          "a.MetalsUniqueName"
        )
      }
    } yield ()
  }

  test("deleted") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        """
          |/metals.json
          |{"a": {}}
          |/a/src/main/scala/a/Before.scala
          |package a
          |object MyObjectSymbol
          |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Before.scala")
      _ = assertNoDiff(
        server.workspaceSymbol("MyObjectSymbol", includeFilename = true),
        """a.MyObjectSymbol Before.scala"""
      )
      _ = {
        val before = server.toPath("a/src/main/scala/a/Before.scala").toNIO
        val after = before.resolveSibling("After.scala")
        Files.move(before, after)
      }
      _ <- server.didOpen("a/src/main/scala/a/After.scala")
      _ <- server.didSave("a/src/main/scala/a/After.scala")(identity)
      _ = assertNoDiagnostics()
      _ = assertNoDiff(
        server.workspaceSymbol("MyObjectSymbol", includeFilename = true),
        // Assert "Before.scala" is removed from the results
        """a.MyObjectSymbol After.scala"""
      )
    } yield ()
  }
}
