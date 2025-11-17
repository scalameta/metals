package tests.mbt

import java.nio.file.Files

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.Configs.WorkspaceSymbolProviderConfig
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.mbt.IndexedDocument
import scala.meta.internal.mtags.Mtags

import org.eclipse.{lsp4j => l}
import tests.BuildInfo
import tests.FileLayout

class MbtV2LspSuite extends tests.BaseLspSuite("mbt-v2") {
  override def userConfig: UserConfiguration = super.userConfig.copy(
    fallbackScalaVersion = Some(BuildInfo.scalaVersion),
    presentationCompilerDiagnostics = true,
    buildOnChange = false,
    buildOnFocus = false,
    workspaceSymbolProvider = WorkspaceSymbolProviderConfig.mbt2,
  )
  override def initializeGitRepo: Boolean = true

  test("basic") {
    cleanWorkspace()
    val example = "a/src/main/scala/a/Example.scala"
    val foosball = "whatever/FoosBall.scala"
    FileLayout.fromString(
      s"""|/$foosball
          |package whatever
          |
          |object FoosBall {
          |  def bar(name: String): String = s"Hello, $$name!"
          |}
          |""".stripMargin,
      root = server.workspace,
    )
    for {
      _ <- initialize(
        s"""|/metals.json
            |{
            |  "a": {}
            |}
            |/$example
            |package a
            |
            |object Example {
            |  val message = "Hello, World!"
            |  def greet(name: String): String = s"Hello, $$name!"
            |}
            |""".stripMargin
      )
      _ <- server.didOpen(example)
      _ = assertNoDiff(
        server.workspaceSymbol("greet"),
        "a.Example.greet",
      )
      // ==========
      // git status
      // ==========
      _ = assertNoDiff(
        server.workspaceSymbol("FoosBall"),
        "whatever.FoosBall",
        "dirty state was not picked up from `git status`",
      )

      // =======
      // didSave
      // =======
      _ <- server.didOpen(foosball)
      _ <- server.didChange(foosball)(
        _.replace(
          "object FoosBall {",
          "object FoosBall {\n  def newMethod(): Int = 42",
        )
      )
      _ <- server.didSave(foosball)
      _ = assertNoDiff(
        server.workspaceSymbol("newMethod"),
        "whatever.FoosBall.newMethod",
        "a newly added method was not picked from didChange",
      )
    } yield ()
  }

  test("file-watcher".flaky) {
    cleanWorkspace()
    val cafe = "a/src/main/scala/a/Cafe.scala"
    val chocolate = "a/src/main/scala/a/Chocolate.scala"
    val banana = "a/src/main/scala/a/Banana.scala"
    for {
      _ <- initialize(
        s"""|/metals.json
            |{
            |  "a": {}
            |}
            |/$chocolate
            |package a
            |
            |object Chocolate {
            |  def swissMilk(): String = "Swiss Milk"
            |}
            |/$banana
            |package a
            |
            |object Banana {
            |  def ripeAndReady(): String = "Banana"
            |}
            |""".stripMargin
      )

      // ======
      // Create
      // ======
      _ = FileLayout.fromString(
        s"""|/$cafe
            |package a
            |
            |object Cafe {
            |  def coffee(): String = "Coffee"
            |}
            |""".stripMargin,
        root = server.workspace,
      )
      // We already have file watchers that trigger without this. But sending it
      // manually allows us to await until the processing has completed.
      _ <- server.didChangeWatchedFiles(cafe, l.FileChangeType.Created)
      _ = assertNoDiff(
        server.workspaceSymbol("coffee"),
        "a.Cafe.coffee",
        "a newly created file was not picked from didChangeWatchedFiles",
      )

      // ====
      // Edit
      // ====
      _ = FileLayout.fromString(
        s"""|/$cafe
            |package a
            |
            |object Cafe {
            |  def coffee(): String = "Coffee"
            |  def teaBlackAndSweet(): String = "Tea"
            |}
            |""".stripMargin,
        root = server.workspace,
      )
      _ <- server.didChangeWatchedFiles(cafe, l.FileChangeType.Changed)
      _ = assertNoDiff(
        server.workspaceSymbol("teaBlackAnd"),
        "a.Cafe.teaBlackAndSweet",
        "an edited symbol was not picked from didChangeWatchedFiles",
      )

      // ======
      // Delete
      // ======
      _ = assertNoDiff(
        server.workspaceSymbol("swissMil"),
        "a.Chocolate.swissMilk",
      )
      chocolateFile = server.workspace.resolve(chocolate)
      _ = chocolateFile.delete()
      _ <- server.didChangeWatchedFiles(
        chocolateFile.toURI.toString(),
        l.FileChangeType.Deleted,
      )
      _ = assertNoDiff(
        server.workspaceSymbol("swissMil"),
        "",
        "a deleted file was not picked from didChangeWatchedFiles",
      )

      // ======
      // Rename
      // ======
      _ = assertNoDiff(
        server.workspaceSymbol("ripeAnd", includeFilename = true),
        "a.Banana.ripeAndReady Banana.scala",
        "a renamed file was not picked from didChangeWatchedFiles",
      )
      bananaFile = server.workspace.resolve(banana)
      banana2File = bananaFile.resolveSibling(_ => "Banana2.scala")
      _ = Files.move(bananaFile.toNIO, banana2File.toNIO)
      _ <- server.didChangeWatchedFiles(
        bananaFile.toURI.toString(),
        l.FileChangeType.Deleted,
      )
      _ <- server.didChangeWatchedFiles(
        banana2File.toURI.toString(),
        l.FileChangeType.Created,
      )
      _ = assertNoDiff(
        server.workspaceSymbol("ripeAnd", includeFilename = true),
        "a.Banana.ripeAndReady Banana2.scala",
        "a renamed file was not picked from didChangeWatchedFiles",
      )
    } yield ()
  }

  test("existing-index") {
    cleanWorkspace()
    val example = "a/src/main/java/a/b/Example.java"
    val example2 = "a/src/main/java/a/b/Example2.java"
    val greeting = "wherever/BlazingFastGreeting.java"

    FileLayout.fromString(
      s"""|/$greeting
          |package wherever;
          |public class BlazingFastGreeting {
          |  public static String greet(String name) {
          |    return "Hello, " + name + "!";
          |  }
          |}
          |""".stripMargin,
      root = server.workspace,
    )

    // When you restart Metals, it reads the index from from disk, and we want
    // to assert everything behaves correctly when restarting Metals.
    def simulateOldIndexFile(): Unit = {
      val buffers = Buffers()
      val dialect = scala.meta.dialects.Scala213
      val mdoc = IndexedDocument.fromFile(
        server.workspace.resolve(greeting),
        Mtags.testingSingleton,
        buffers,
        dialect,
      )
      val indexFile = server.workspace
        .resolve(".metals")
        .createDirectories()
        .resolve("index.mbt")
      indexFile.writeBytes(mdoc.toIndexProto().toByteArray)
    }
    simulateOldIndexFile()

    for {
      _ <- initialize(
        s"""|/metals.json
            |{
            |  "a": {}
            |}
            |/$example
            |package a.b;
            |
            |public class Example {
            |  public static String greeting = wherever.BlazingFastGreeting.greet("World");
            |  public static String greeting2 = Example2.greeting;
            |}
            |/$example2
            |package a.b;
            |
            |public class Example2 {
            |  public static String greeting = "example2";
            |}
            |""".stripMargin
      )
      _ <- server.didOpenAndFocus(example)
      _ = assertNoDiagnostics()
      greetingFile = server.workspace.resolve(greeting)
      _ = greetingFile.deleteIfExists()
      _ <- server.didChangeWatchedFiles(
        greetingFile.toURI.toString(),
        l.FileChangeType.Deleted,
      )
      // NOTE: use didChange to invalidate the javac compile cache. We
      // intentionally don't invalidate the javac compile cache with didSave
      // since that would be really slow down the editing experience.
      _ <- server.didChange(example)(code => code + "\n// comment")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/java/a/b/Example.java:4:43: error: package wherever does not exist
           |  public static String greeting = wherever.BlazingFastGreeting.greet("World");
           |                                          ^^^^^^^^^^^^^^^^^^^^
           |""".stripMargin,
        "a deleted file did not get removed from the mbt-v2 package index",
      )
    } yield ()
  }
}
