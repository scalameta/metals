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
}
