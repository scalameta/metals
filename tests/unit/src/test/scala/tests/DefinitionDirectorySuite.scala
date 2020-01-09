package tests

import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.internal.mtags.Symbol

class DefinitionDirectorySuite extends BaseSuite {
  test("basic") {
    val index = OnDemandSymbolIndex()
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
    index.addSourceDirectory(root)
    assertDefinition("com/foo/Foo#")
    assertDefinition("com/foo/Bar.")
  }
}
