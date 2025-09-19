package tests.mbt

import java.nio.file.Files

import scala.meta.internal.metals.Configs
import scala.meta.internal.metals.StatisticsConfig
import scala.meta.internal.metals.mbt.IndexingStats
import scala.meta.internal.metals.mbt.MbtWorkspaceSymbolProvider
import scala.meta.io.AbsolutePath

import munit.AnyFixture
import munit.TestOptions
import org.eclipse.{lsp4j => l}
import tests.CustomLoggingFixture
import tests.FileLayout
import tests.TemporaryDirectoryFixture

class MbtWorkspaceSymbolProviderSuite extends munit.FunSuite {
  case class Query(value: String, expected: String)
  val workspace = new TemporaryDirectoryFixture()
  override def munitFixtures: Seq[AnyFixture[_]] =
    List(
      workspace,
      CustomLoggingFixture.showWarnings(),
    )

  def formatSymbols(symbols: List[l.SymbolInformation]): String = {
    symbols
      .sortBy(s => s.getName() + s.getContainerName())
      .map(s => s"${s.getKind()} ${s.getName()} ${s.getContainerName()}")
      .mkString("\n")
  }
  def newProvider(): MbtWorkspaceSymbolProvider =
    new MbtWorkspaceSymbolProvider(
      workspace(),
      new Configs.WorkspaceSymbolProviderConfig("mbt"),
      () => StatisticsConfig.workspaceSymbol,
    )

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
      provider.onReindex(),
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
      provider.onReindex(),
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
      provider.onReindex(),
      IndexingStats(totalFiles = 4, updatedFiles = 0),
    )

    // Remove a file
    Files.delete(workspace().resolve("com/Hello.scala").toNIO)
    workspace.gitCommitAllChanges()
    assertEquals(
      provider.onReindex(),
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

  def manuallyTestWorkspace(
      dir: TestOptions,
      query: String,
      assertResultIncludes: String,
  ): Unit = {
    test(dir) {
      val provider = new MbtWorkspaceSymbolProvider(
        AbsolutePath(dir.name),
        new Configs.WorkspaceSymbolProviderConfig("mbt"),
        () => StatisticsConfig.default,
      )
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
    "/home/REDACTED_USER/universe".ignore,
    query = "TestProjectEnum",
    assertResultIncludes = "Object TestProjectEnum ",
  )

}
