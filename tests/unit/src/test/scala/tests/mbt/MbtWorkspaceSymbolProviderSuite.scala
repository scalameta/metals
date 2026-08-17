package tests.mbt

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

import scala.meta.internal.metals.Configs
import scala.meta.internal.metals.EmptyWorkDoneProgress
import scala.meta.internal.metals.LoggerReportContext
import scala.meta.internal.metals.mbt.IndexingStats
import scala.meta.internal.metals.mbt.MbtWorkspaceSymbolProvider
import scala.meta.internal.metals.mbt.TurbineClasspathFileManager
import scala.meta.internal.metals.mbt.TurbineCompileResult
import scala.meta.internal.metals.mbt.TurbineCompiler
import scala.meta.io.AbsolutePath

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

  private def compile(source: String): TurbineCompileResult =
    TurbineCompiler.compileClassfiles(
      ParArray(source),
      (text: String) => Seq(new SourceFile("Dependency.java", text)),
      Nil,
      EmptyWorkDoneProgress,
    )

  test("target-classpath-before-workspace-headers") {
    val workspaceResult = compile(
      "package example; public class Dependency { public static void workspace() {} }"
    )
    val projectResult = compile(
      "package example; public class Dependency { public static void project() {} }"
    )
    val jar = Files.createTempFile("metals-project-classpath", ".jar")
    val output = new ZipOutputStream(Files.newOutputStream(jar))
    try {
      for ((name, bytes) <- projectResult.lowered.bytes().asScala) {
        output.putNextEntry(new ZipEntry(s"$name.class"))
        output.write(bytes)
        output.closeEntry()
      }
    } finally output.close()

    val standardFileManager = ToolProvider
      .getSystemJavaCompiler()
      .getStandardFileManager(null, null, null)
    standardFileManager.setLocationFromPaths(
      StandardLocation.CLASS_PATH,
      List(jar).asJava,
    )
    val fileManager = new TurbineClasspathFileManager(
      standardFileManager,
      () => workspaceResult,
      _ => java.util.Collections.emptyList(),
      _ => false,
    )
    val classfiles = fileManager
      .list(
        StandardLocation.CLASS_PATH,
        "example",
        EnumSet.of(JavaFileObject.Kind.CLASS),
        false,
      )
      .asScala
      .toList
    assertEquals(classfiles.size, 1)
    assertEquals(
      classfiles.head.openInputStream().readAllBytes().toSeq,
      projectResult.lowered.bytes().get("example/Dependency").toSeq,
    )
  }
}
