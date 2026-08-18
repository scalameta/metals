package tests.mbt

import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths
import java.util.EnumSet
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.tools.JavaFileObject
import javax.tools.StandardLocation
import javax.tools.ToolProvider

import scala.collection.JavaConverters._
import scala.collection.parallel.mutable.ParArray
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Using

import scala.meta.internal.metals.Configs
import scala.meta.internal.metals.EmptyWorkDoneProgress
import scala.meta.internal.metals.LoggerReportContext
import scala.meta.internal.metals.Sleeper
import scala.meta.internal.metals.mbt.IndexingStats
import scala.meta.internal.metals.mbt.MbtWorkspaceSymbolProvider
import scala.meta.internal.metals.mbt.TurbineCompileResult
import scala.meta.internal.metals.mbt.TurbineCompiler
import scala.meta.internal.metals.mbt.VirtualTextDocument
import scala.meta.io.AbsolutePath
import scala.meta.pc

import com.google.turbine.diag.SourceFile
import munit.AnyFixture
import munit.TestOptions
import org.eclipse.{lsp4j => l}
import tests.CustomLoggingFixture
import tests.FileLayout
import tests.TemporaryDirectoryFixture

class MbtWorkspaceSymbolSearchSuite extends munit.FunSuite {
  case class Query(value: String, expected: String)
  val workspace = new TemporaryDirectoryFixture()
  override def munitFixtures: Seq[AnyFixture[_]] =
    List(
      workspace,
      CustomLoggingFixture.showWarnings(),
    )

  override def munitExecutionContext: ExecutionContext = ExecutionContext.global
  def formatSymbols(symbols: List[l.SymbolInformation]): String = {
    symbols
      .sortBy(s => s.getName() + s.getContainerName())
      .map(s => s"${s.getKind()} ${s.getName()} ${s.getContainerName()}")
      .mkString("\n")
  }
  def newProvider(): MbtWorkspaceSymbolProvider =
    new MbtWorkspaceSymbolProvider(
      workspace(),
      config = () => Configs.WorkspaceSymbolProviderConfig.mbt,
    )(munitExecutionContext)

  test("multi-language") {
    FileLayout.fromString(
      """
/com/Hello.scala
package com;
object Hello {
  def main(args: Array[String]): Unit = {
    println(Greeting.greet(User(name = "World", age = 20)))
  }
}
/com/Greeting.java
package com;
public class Greeting {
  enum Day { WORKDAY, WEEKEND }
  public static String greet(User user) {
    return "Hello, " + user.name + "!";
  }
}
/com/User.proto
package com;
message User {
  string name = 1;
  int32 age = 2;
}
/README.md
# Example Project
""",
      root = workspace(),
    )
    val provider = newProvider()
    workspace.executeCommand("git init -b main")
    workspace.gitCommitAllChanges()
    assertEquals(
      provider.onReindex().awaitBackgroundJobs(),
      IndexingStats(totalFiles = 3, updatedFiles = 3),
    )
    assertNoDiff(
      formatSymbols(provider.queryWorkspaceSymbol("Hel")),
      """
        |Object Hello com.
        |""".stripMargin,
    )
    assertNoDiff(
      formatSymbols(provider.queryWorkspaceSymbol("Greet")),
      """
        |Class Greeting com.
        |""".stripMargin,
    )
    val List(workday) = provider.queryWorkspaceSymbol("WORK")
    assert(clue(workday.getLocation().getRange().getStart().getLine()) > 0)
    assertNoDiff(
      formatSymbols(provider.queryWorkspaceSymbol("User")),
      """
        |Class User com.
        |""".stripMargin,
    )
    FileLayout.fromString(
      """
/com/Hello.scala
package com;
object Hello {
  def main(args: Array[String]): Unit = {
    println(Greeting.greet(User(name = "World", age = 20)))
  }
  def main2(): Unit = {
    println(Greeting.greet(User(name = "World", age = 20)))
  }
}
/com/Hello2.scala
package com;
object Hello2 {
  def main2(args: Array[String]): Unit = {
    println(Greeting.greet(User(name = "World", age = 20)))
  }
}
""",
      root = workspace(),
    )
    workspace.gitCommitAllChanges()
    assertEquals(
      provider.onReindex().awaitBackgroundJobs(),
      IndexingStats(totalFiles = 4, updatedFiles = 2),
    )
    assertNoDiff(
      formatSymbols(provider.queryWorkspaceSymbol("main")),
      """
        |Method main2 com.Hello.
        |Method main2 com.Hello2.
        |Method main com.Hello.
        |""".stripMargin,
    )
    assertEquals(
      provider.onReindex().awaitBackgroundJobs(),
      IndexingStats(totalFiles = 4, updatedFiles = 0),
    )

    // Remove a file
    Files.delete(workspace().resolve("com/Hello.scala").toNIO)
    workspace.gitCommitAllChanges()
    assertEquals(
      provider.onReindex().awaitBackgroundJobs(),
      IndexingStats(totalFiles = 3, updatedFiles = 0),
    )
    // Nothing to re-index, we only removed a file
    assertNoDiff(
      formatSymbols(provider.queryWorkspaceSymbol("main")),
      // No stale results from the deleted file
      """
        |Method main2 com.Hello2.
        |""".stripMargin,
    )
  }

  test("exclude-module-info-java") {
    FileLayout.fromString(
      """
/com/Hello.java
package com;
public class Hello {}
/module-info.java
module com.example {
  requires java.base;
  exports com;
}
""",
      root = workspace(),
    )
    val provider = newProvider()
    workspace.executeCommand("git init -b main")
    workspace.gitCommitAllChanges()
    val stats = provider.onReindex().awaitBackgroundJobs()
    assertEquals(stats, IndexingStats(totalFiles = 2, updatedFiles = 1))
    assertEquals(
      provider.allFiles().map(_.toRelative(workspace()).toString()),
      List(Paths.get("com/Hello.java").toString()),
    )
  }

  test("exclude-module-info-java-on-did-change") {
    FileLayout.fromString(
      """
/module-info.java
module com.example {
  requires java.base;
}
""",
      root = workspace(),
    )
    val provider = newProvider()
    Await.result(
      provider.onDidChange(workspace().resolve("module-info.java")),
      5.seconds,
    )
    assertEquals(provider.allFiles(), Nil)
  }

  def manuallyTestWorkspace(
      dir: TestOptions,
      query: String,
      assertResultIncludes: String,
  ): Unit = {
    test(dir) {
      val provider = new MbtWorkspaceSymbolProvider(
        workspace = AbsolutePath(dir.name),
        config = () => Configs.WorkspaceSymbolProviderConfig.mbt,
      )(munitExecutionContext)
      provider.onReindex()
      val result =
        formatSymbols(provider.queryWorkspaceSymbol(query))
      scribe.info(
        result.split("\n").filter(l => l.startsWith("Class ")).mkString("\n")
      )
      assert(
        clue(result).contains(assertResultIncludes)
      )
    }
  }

  // Use this helper to manually test the indexer against a real-world codebase
  manuallyTestWorkspace(
    "/tmp/test-project".ignore,
    query = "TestProjectEnum",
    assertResultIncludes = "Object TestProjectEnum ",
  )

}

class TurbineClasspathFileManagerSuite extends munit.FunSuite {
  implicit val reportContext: LoggerReportContext.type = LoggerReportContext
  implicit val executionContext: ExecutionContext = ExecutionContext.global

  private val workspaceSource =
    "package example; public class Dependency { public static class Builder { public void workspace() {} } }"
  private val projectSource =
    "package example; public class Dependency { public static class Builder { public void project() {} } }"

  private def compile(source: String): TurbineCompileResult =
    TurbineCompiler.compileClassfiles(
      ParArray(source),
      (text: String) => Seq(new SourceFile("Dependency.java", text)),
      Nil,
      EmptyWorkDoneProgress,
    )

  private def checkTargetClasspathPrecedence(isProtobuf: Boolean): Unit = {
    val projectResult = compile(projectSource)
    val jar = Files.createTempFile("metals-project-classpath", ".jar")
    val output = new ZipOutputStream(Files.newOutputStream(jar))
    try {
      for ((name, bytes) <- projectResult.lowered.bytes().asScala) {
        output.putNextEntry(new ZipEntry(s"$name.class"))
        output.write(bytes)
        output.closeEntry()
      }
    } finally output.close()

    var fallbackClasspath = Seq.empty[java.nio.file.Path]
    val protoOutline = VirtualTextDocument(
      URI.create("file:///Dependency.java"),
      pc.Language.JAVA,
      workspaceSource,
      Seq("example"),
      Seq("example/Dependency#"),
    )
    val compiler = new TurbineCompiler[String](
      () => ParArray(workspaceSource),
      text => Seq(new SourceFile("Dependency.java", text)),
      () => fallbackClasspath,
      EmptyWorkDoneProgress,
      () => Configs.TurbineRecompileDelayConfig.testing,
      packageName =>
        if (isProtobuf && packageName == "example/") Iterator(protoOutline)
        else Iterator.empty,
      Sleeper.TestingSleeper,
      () => (),
      _ => (),
    )
    compiler.doCompileNow()
    val workspaceResult = compiler.result
    fallbackClasspath = Seq(jar)

    val standardFileManager = ToolProvider
      .getSystemJavaCompiler()
      .getStandardFileManager(null, null, null)
    standardFileManager.setLocationFromPaths(
      StandardLocation.CLASS_PATH,
      List(jar).asJava,
    )
    val fileManager = compiler.createFileManager(
      standardFileManager,
      List(jar).asJava,
    )
    try {
      val classfiles = fileManager
        .list(
          StandardLocation.CLASS_PATH,
          "example",
          EnumSet.of(JavaFileObject.Kind.CLASS),
          false,
        )
        .asScala
        .toList
      val obtained = classfiles.map { classfile =>
        val binaryName = fileManager
          .inferBinaryName(StandardLocation.CLASS_PATH, classfile)
          .replace('.', '/')
        binaryName -> Using.resource(classfile.openInputStream())(
          _.readAllBytes().toSeq
        )
      }.toMap
      val expectedResult =
        if (isProtobuf) projectResult
        else workspaceResult
      val expected = expectedResult.lowered
        .bytes()
        .asScala
        .map { case (name, bytes) =>
          name -> bytes.toSeq
        }
        .toMap
      assertEquals(obtained, expected)
    } finally {
      fileManager.close()
      Files.deleteIfExists(jar)
    }
  }

  test("target-protobuf-classpath-before-workspace-headers") {
    checkTargetClasspathPrecedence(isProtobuf = true)
  }

  test("workspace-headers-before-non-protobuf-target-classpath") {
    checkTargetClasspathPrecedence(isProtobuf = false)
  }
}
