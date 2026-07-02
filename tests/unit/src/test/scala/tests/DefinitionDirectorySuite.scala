package tests

import scala.meta.dialects
import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.internal.mtags.Symbol

class DefinitionDirectorySuite extends BaseSuite {
  test("basicScala") {
    val index = OnDemandSymbolIndex.empty()
    def assertDefinition(sym: String): Unit = {
      val definition = index.definition(Symbol(sym))
      if (definition.isEmpty) throw new NoSuchElementException(sym)
    }
    val root = FileLayout.fromString(
      """
        |/com/foo/Foo.scala
        |package com.foo
        |sealed trait Foo
        |case object Bar extends Foo
        |""".stripMargin
    )
    index.addSourceDirectory(root, dialects.Scala213)
    assertDefinition("com/foo/Foo#")
    assertDefinition("com/foo/Bar.")
  }
  test("basicJava") {
    val index = OnDemandSymbolIndex.empty()
    def assertDefinition(sym: String): Unit = {
      val definition = index.definition(Symbol(sym))
      if (definition.isEmpty) throw new NoSuchElementException(sym)
    }
    val root = FileLayout.fromString(
      """
        |/com/foo/Foo.java
        |package com.foo;
        |public class Foo {}
        |""".stripMargin
    )
    index.addSourceDirectory(root, dialects.Scala213)
    assertDefinition("com/foo/Foo.")
  }
  test("javaClassHashSymbol") {
    val index = OnDemandSymbolIndex.empty()
    def assertDefinition(sym: String): Unit = {
      val definition = index.definition(Symbol(sym))
      if (definition.isEmpty) throw new NoSuchElementException(sym)
    }
    val root = FileLayout.fromString(
      """
        |/com/foo/Foo.java
        |package com.foo;
        |public final class Foo {}
        |""".stripMargin
    )
    index.addSourceDirectory(root, dialects.Scala213)
    assertDefinition("com/foo/Foo#")
  }
  test("nestedJava") {
    val index = OnDemandSymbolIndex.empty()
    def assertDefinition(sym: String): Unit = {
      val definition = index.definition(Symbol(sym))
      if (definition.isEmpty) throw new NoSuchElementException(sym)
    }
    val root = FileLayout.fromString(
      """
        |/com/foo/EchoService.java
        |package com.foo;
        |public final class EchoService {
        |  public static final class EchoRequest {}
        |}
        |""".stripMargin
    )
    index.addSourceDirectory(root, dialects.Scala213)
    assertDefinition("com/foo/EchoService#EchoRequest#")
  }
  test("javaSourceFile") {
    val index = OnDemandSymbolIndex.empty()
    def assertDefinition(sym: String): Unit = {
      val definition = index.definition(Symbol(sym))
      if (definition.isEmpty) throw new NoSuchElementException(sym)
    }
    val root = FileLayout.fromString(
      """
        |/com/foo/EchoService.java
        |package com.foo;
        |public final class EchoService {
        |  public static final class EchoRequest {}
        |}
        |""".stripMargin
    )
    index.addSourceFile(
      root.resolve("com/foo/EchoService.java"),
      Some(root),
      dialects.Scala213,
    )
    assertDefinition("com/foo/EchoService#")
    assertDefinition("com/foo/EchoService#EchoRequest#")
  }
  test("flatJava") {
    val index = OnDemandSymbolIndex.empty()
    def assertDefinition(sym: String): Unit = {
      val definition = index.definition(Symbol(sym))
      if (definition.isEmpty) throw new NoSuchElementException(sym)
    }
    val root = FileLayout.fromString(
      """
        |/FlagsConfig.java
        |package com.foo;
        |public interface FlagsConfig {
        |  public boolean isEnabled();
        |}
        |""".stripMargin
    )
    index.addSourceDirectory(root, dialects.Scala213)
    assertDefinition("com/foo/FlagsConfig#")
    assertDefinition("com/foo/FlagsConfig#isEnabled().")
  }
  test("flatJavaSourceFile") {
    val index = OnDemandSymbolIndex.empty()
    def assertDefinition(sym: String): Unit = {
      val definition = index.definition(Symbol(sym))
      if (definition.isEmpty) throw new NoSuchElementException(sym)
    }
    val root = FileLayout.fromString(
      """
        |/FlagsConfig.java
        |package com.foo;
        |public interface FlagsConfig {
        |  public boolean isEnabled();
        |}
        |""".stripMargin
    )
    index.addSourceFile(
      root.resolve("FlagsConfig.java"),
      Some(root),
      dialects.Scala213,
    )
    assertDefinition("com/foo/FlagsConfig#")
    assertDefinition("com/foo/FlagsConfig#isEnabled().")
  }
}
