package tests

import java.nio.file.Files

import scala.concurrent.Future

import scala.meta.internal.metals.InitializationOptions
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MetalsEnrichments._

import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.WorkspaceSymbolParams
import tests.MetalsTestEnrichments._

class WorkspaceSymbolLspSuite extends BaseLspSuite("workspace-symbol") {

  override protected def initializationOptions: Option[InitializationOptions] =
    Some(TestingServer.TestDefault)

  test("basic") {
    cleanWorkspace()
    for {
      _ <- initialize(
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
        "a.b.PazQux.Inner",
      )
      _ = assertNoDiff(
        server.workspaceSymbol("a.b.PazQux"),
        "a.b.PazQux",
      )
      _ <- server.didSave("a/src/main/scala/a/B.scala")(
        _.replace("class B", "  class HaddockBax")
      )
      _ = assertNoDiff(
        server.workspaceSymbol("Had"),
        "a.HaddockBax",
      )
    } yield ()
  }

  test("pre-initialized") {
    var request = Future.successful[List[List[SymbolInformation]]](Nil)
    writeLayout(
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
        |""".stripMargin
    )
    QuickBuild.bloopInstall(workspace)
    for {
      _ <- server.initialize()
      _ <- Future {
        request = Future
          .sequence(1.to(10).map { _ =>
            server.server
              .workspaceSymbol(new WorkspaceSymbolParams("PazQux.I"))
              .asScala
              .map(_.asScala.toList)
          })
          .map(_.toList)
        Thread.sleep(10) // take a moment to delay
      }
      _ <- server.initialized()
      results <- request
    } yield {
      val obtained = results.distinct
      // Assert that all results are the same, make sure we don't return empty/incomplete results
      // before indexing is complete.
      assert(obtained.length == 1)
      assert(obtained.head.head.fullPath == "a.b.PazQux.Inner")
    }
  }

  test("duplicate") {
    for {
      _ <- initialize(
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
          |""".stripMargin,
      )
    } yield ()
  }

  test("dependencies") {
    cleanWorkspace()
    for {
      _ <- initialize(
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
      _ <- server.didOpen("scala/Option.scala")
      references <- server.references("a/src/main/scala/a/A.scala", " None")
      _ = assertNoDiff(
        references,
        """|a/src/main/scala/a/A.scala:4:24: info: reference
           |  val x: Option[Int] = None
           |                       ^^^^
           |""".stripMargin,
      )
      optionReferences <- server.references(
        "scala/Option.scala",
        " None",
      )
      optionSourceAbsolutePath = server
        .toPath("scala/Option.scala")
      optionSourcePath = {
        if (useVirtualDocuments) optionSourceAbsolutePath
        else optionSourceAbsolutePath.toRelative(workspace)
      }.toString
        .replace("\\", "/")
      _ = assertNoDiff(
        optionReferences,
        s"""|a/src/main/scala/a/A.scala:4:24: info: reference
            |  val x: Option[Int] = None
            |                       ^^^^
            |$optionSourcePath:29:50: info: reference
            |  def apply[A](x: A): Option[A] = if (x == null) None else Some(x)
            |                                                 ^^^^
            |$optionSourcePath:34:30: info: reference
            |  def empty[A] : Option[A] = None
            |                             ^^^^
            |$optionSourcePath:41:28: info: reference
            |    if (cond) Some(a) else None
            |                           ^^^^
            |$optionSourcePath:157:40: info: reference
            |  final def isEmpty: Boolean = this eq None
            |                                       ^^^^
            |$optionSourcePath:242:18: info: reference
            |    if (isEmpty) None else Some(f(this.get))
            |                 ^^^^
            |$optionSourcePath:283:18: info: reference
            |    if (isEmpty) None else f(this.get)
            |                 ^^^^
            |$optionSourcePath:304:18: info: reference
            |    if (isEmpty) None else ev(this.get)
            |                 ^^^^
            |$optionSourcePath:319:43: info: reference
            |    if (isEmpty || p(this.get)) this else None
            |                                          ^^^^
            |$optionSourcePath:334:44: info: reference
            |    if (isEmpty || !p(this.get)) this else None
            |                                           ^^^^
            |$optionSourcePath:462:42: info: reference
            |    if (!isEmpty) pf.lift(this.get) else None
            |                                         ^^^^
            |$optionSourcePath:504:34: info: reference
            |    if (isEmpty || that.isEmpty) None else Some((this.get, that.get))
            |                                 ^^^^
            |$optionSourcePath:524:8: info: reference
            |      (None, None)
            |       ^^^^
            |$optionSourcePath:524:14: info: reference
            |      (None, None)
            |             ^^^^
            |$optionSourcePath:550:8: info: reference
            |      (None, None, None)
            |       ^^^^
            |$optionSourcePath:550:14: info: reference
            |      (None, None, None)
            |             ^^^^
            |$optionSourcePath:550:20: info: reference
            |      (None, None, None)
            |                   ^^^^
            |$optionSourcePath:626:13: info: reference
            |case object None extends Option[Nothing] {
            |            ^^^^""".stripMargin,
      )
    } yield ()
  }

  test("workspace-only") {
    cleanWorkspace()
    for {
      _ <- initialize(
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
            |""".stripMargin,
      )
      _ = {
        // Assert "Properties;" searches workspace + libraries
        val file = server.workspaceSymbol("Properties;")
        assert(file.startsWith("a.Properties"))
        assertContains(file, "scala.util.Properties")
        assertNotContains(
          file,
          Messages.WorkspaceSymbolDependencies.title,
        )
        // Assert we automatically fallback to library dependencies on no match.
        assertContains(
          server.workspaceSymbol("Future"),
          "scala.concurrent.Future",
        )
        // Assert we don't suggest to "add ';' to search library dependencies"
        // because "MetalsUniqueName" has no matches in library dependencies.
        assertNoDiff(
          server.workspaceSymbol("MetalsUniqueName"),
          "a.MetalsUniqueName",
        )
      }
    } yield ()
  }

  test("deleted") {
    cleanWorkspace()
    for {
      _ <- initialize(
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
        """a.MyObjectSymbol Before.scala""",
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
        """a.MyObjectSymbol After.scala""",
      )
    } yield ()
  }

  test("excluded") {
    cleanWorkspace()
    for {
      _ <- initialize(
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
        server.workspaceSymbol("Future"),
        if (isJava17) {
          """|scala.concurrent.Future
             |scala.concurrent.Future
             |java.util.concurrent.Future
             |scala.sys.process.ProcessImpl#Future
             |scala.jdk.FutureConverters.FutureOps
             |java.util.concurrent.FutureTask
             |java.util.concurrent.RunnableFuture
             |java.util.concurrent.ExecutorCompletionService#QueueingFuture
             |java.util.concurrent.ScheduledFuture
             |scala.jdk.FutureConverters
             |scala.jdk.javaapi.FutureConverters
             |java.util.concurrent.CompletableFuture
             |scala.concurrent.Future.failedFailureFuture
             |java.util.concurrent.ScheduledThreadPoolExecutor#ScheduledFutureTask
             |scala.concurrent.impl.FutureConvertersImpl
             |""".stripMargin
        } else {
          """|scala.concurrent.Future
             |scala.concurrent.Future
             |java.util.concurrent.Future
             |scala.sys.process.ProcessImpl#Future
             |scala.jdk.FutureConverters.FutureOps
             |java.util.concurrent.FutureTask
             |java.io.ObjectStreamClass#EntryFuture
             |java.util.concurrent.RunnableFuture
             |java.util.concurrent.ExecutorCompletionService#QueueingFuture
             |java.util.concurrent.ScheduledFuture
             |scala.jdk.FutureConverters
             |scala.jdk.javaapi.FutureConverters
             |java.util.concurrent.CompletableFuture
             |scala.concurrent.Future.failedFailureFuture
             |java.util.concurrent.ScheduledThreadPoolExecutor#ScheduledFutureTask""".stripMargin
        },
      )
      _ <- server.didChangeConfiguration(
        """|{
           |  "excluded-packages": [
           |     "java.util"
           |  ]
           |}
           |""".stripMargin
      )
      _ = assertNoDiff(
        server.workspaceSymbol("Future"),
        if (isJava17)
          """|scala.concurrent.Future
             |scala.concurrent.Future
             |scala.sys.process.ProcessImpl#Future
             |scala.jdk.FutureConverters.FutureOps
             |scala.jdk.FutureConverters
             |scala.jdk.javaapi.FutureConverters
             |scala.concurrent.Future.failedFailureFuture
             |scala.concurrent.impl.FutureConvertersImpl
             |""".stripMargin
        else
          """|scala.concurrent.Future
             |scala.concurrent.Future
             |scala.sys.process.ProcessImpl#Future
             |scala.jdk.FutureConverters.FutureOps
             |java.io.ObjectStreamClass#EntryFuture
             |scala.jdk.FutureConverters
             |scala.jdk.javaapi.FutureConverters
             |scala.concurrent.Future.failedFailureFuture
             |scala.concurrent.impl.FutureConvertersImpl
             |""".stripMargin,
      )
    } yield ()
  }

  test("symbolic-from-classpath") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """
          |/metals.json
          |{
          |  "a": {}
          |}
          |""".stripMargin
      )
      _ = assertNoDiff(
        server.workspaceSymbol("<:<"),
        """|scala.<:<
           |scala.<:<
           |""".stripMargin,
      )
    } yield ()
  }
}
