package tests.mbt

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import scala.meta.internal.metals.Configs
import scala.meta.internal.metals.mbt.IndexingStats
import scala.meta.internal.metals.mbt.MbtWorkspaceSymbolProvider
import scala.meta.internal.metals.mbt.TurbineSymbolInfo
import scala.meta.internal.pc.PcSymbolInformation
import scala.meta.pc.PcSymbolKind

import munit.AnyFixture
import tests.FileLayout
import tests.TemporaryDirectoryFixture

class TurbineClassInfoSuite extends munit.FunSuite {
  val workspace = new TemporaryDirectoryFixture()
  override def munitFixtures: Seq[AnyFixture[_]] = List(workspace)
  override def munitExecutionContext: ExecutionContext = ExecutionContext.global

  def newProvider(): MbtWorkspaceSymbolProvider =
    new MbtWorkspaceSymbolProvider(
      workspace(),
      config = () => Configs.WorkspaceSymbolProviderConfig.mbt,
    )(munitExecutionContext)

  def reindex(provider: MbtWorkspaceSymbolProvider): IndexingStats = {
    workspace.executeCommand("git init -b main")
    workspace.gitCommitAllChanges()
    provider.onReindex().awaitBackgroundJobs(30.seconds)
  }

  def infoFor(
      provider: MbtWorkspaceSymbolProvider,
      relativePath: String,
      symbol: String,
  ): PcSymbolInformation = {
    val infos = provider.classInfo(workspace().resolve(relativePath))
    infos.find(_.symbol == symbol).getOrElse {
      fail(
        s"no classInfo for $symbol in $relativePath, found: ${infos.map(_.symbol).mkString(", ")}"
      )
    }
  }

  test("class-symbol-to-semanticdb") {
    assertEquals(
      TurbineSymbolInfo.classSymbolToSemanticdb("com/foo/Bar"),
      "com/foo/Bar#",
    )
    assertEquals(
      TurbineSymbolInfo.classSymbolToSemanticdb("com/foo/Outer$Inner"),
      "com/foo/Outer#Inner#",
    )
    assertEquals(
      TurbineSymbolInfo.classSymbolToSemanticdb("org/junit/Test"),
      "org/junit/Test#",
    )
  }

  test("java-class-parents-and-kind") {
    FileLayout.fromString(
      """|/com/Hello.java
         |package com;
         |public class Hello {}
         |""".stripMargin,
      root = workspace(),
    )
    val provider = newProvider()
    reindex(provider)
    val info = infoFor(provider, "com/Hello.java", "com/Hello#")
    assertEquals(info.kind, PcSymbolKind.CLASS)
    assertEquals(info.dealiasedSymbol, "com/Hello#")
    assert(info.parents.contains("java/lang/Object#"))
    assertEquals(info.recursiveParents, Nil)
    assertEquals(info.annotations, Nil)
    assertEquals(info.memberDefsAnnotations, Nil)
  }

  test("java-interface-is-abstract") {
    FileLayout.fromString(
      """|
         |/com/Named.java
         |package com;
         |public interface Named {
         |  String name();
         |}
         |""".stripMargin,
      root = workspace(),
    )
    val provider = newProvider()
    reindex(provider)
    val info = infoFor(provider, "com/Named.java", "com/Named#")
    assertEquals(info.kind, PcSymbolKind.INTERFACE)
    assertNoDiff(
      info.properties.mkString("\n"),
      """|
         |ABSTRACT
         |""".stripMargin,
    )
  }

  test("nested-class-and-type-parameters") {
    FileLayout.fromString(
      """|
         |/com/Box.java
         |package com;
         |public class Box<T> {
         |  public static class Inner {}
         |}
         |""".stripMargin,
      root = workspace(),
    )
    val provider = newProvider()
    reindex(provider)
    val box = infoFor(provider, "com/Box.java", "com/Box#")
    assertNoDiff(
      box.typeParameters.mkString("\n"),
      """|
         |com/Box#[T]
         |""".stripMargin,
    )
    val inner = infoFor(provider, "com/Box.java", "com/Box#Inner#")
    assertNoDiff(
      inner.classOwner.getOrElse(""),
      "com/Box#",
    )
  }

  test("extends-workspace-class") {
    FileLayout.fromString(
      """|
         |/com/Base.java
         |package com;
         |public class Base {}
         |/com/Child.java
         |package com;
         |public class Child extends Base {}
         |""".stripMargin,
      root = workspace(),
    )
    val provider = newProvider()
    reindex(provider)
    val info = infoFor(provider, "com/Child.java", "com/Child#")
    assertNoDiff(
      info.parents.mkString("\n"),
      """|
         |com/Base#
         |""".stripMargin,
    )
    assertNoDiff(
      info.recursiveParents.mkString("\n"),
      "",
    )
  }

  test("junit-test-method-annotation") {
    FileLayout.fromString(
      """|
         |/org/junit/Test.java
         |package org.junit;
         |import java.lang.annotation.ElementType;
         |import java.lang.annotation.Retention;
         |import java.lang.annotation.RetentionPolicy;
         |import java.lang.annotation.Target;
         |@Retention(RetentionPolicy.RUNTIME)
         |@Target(ElementType.METHOD)
         |public @interface Test {}
         |/com/MyTest.java
         |package com;
         |import org.junit.Test;
         |@Deprecated
         |public class MyTest {
         |  @Test
         |  public void runs() {}
         |}""".stripMargin,
      root = workspace(),
    )
    val provider = newProvider()
    reindex(provider)
    val info = infoFor(provider, "com/MyTest.java", "com/MyTest#")
    assertNoDiff(
      info.annotations.mkString("\n"),
      """|
         |java/lang/Deprecated#
         |""".stripMargin,
    )
    assertNoDiff(
      info.memberDefsAnnotations.mkString("\n"),
      """|
         |org/junit/Test#
         |""".stripMargin,
    )
  }

  test("scala-file-returns-empty") {
    FileLayout.fromString(
      """|
         |/com/Hello.scala
         |package com
         |class Hello
         |""".stripMargin,
      root = workspace(),
    )
    val provider = newProvider()
    reindex(provider)
    assertEquals(
      provider.classInfo(workspace().resolve("com/Hello.scala")),
      Nil,
    )
  }
}
