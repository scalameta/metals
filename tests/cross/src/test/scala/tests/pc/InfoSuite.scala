package tests.pc

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.pc.PcSymbolInformation
import scala.meta.pc.PcSymbolKind
import scala.meta.pc.PcSymbolProperty

import tests.BasePCSuite

class InfoSuite extends BasePCSuite {

  def getInfo(symbol: String): PcSymbolInformation = {
    val result = presentationCompiler.info(symbol).get()
    assert(result.isPresent(), s"no info returned for symbol $symbol")
    assertNoDiff(result.get().symbol(), symbol)
    result.get()
  }

  test("list") {
    val info = getInfo("scala/collection/immutable/List#")
    assert(info.properties().contains(PcSymbolProperty.ABSTRACT))
    assert(info.parents().contains("scala/collection/immutable/LinearSeq#"))
  }

  test("empty-list-constructor") {
    val info = getInfo("scala/collection/immutable/List.empty().")
    assertNoDiff(info.classOwner(), "scala/collection/immutable/List.")
    assertEquals(info.kind(), PcSymbolKind.METHOD)
  }

  test("assert") {
    val info = getInfo("scala/Predef.assert().")
    assertEquals(info.kind(), PcSymbolKind.METHOD)
    assertNoDiff(info.classOwner(), "scala/Predef.")
    assertEquals(
      info.alternativeSymbols().asScala.mkString("\n"),
      "scala/Predef.assert(+1)."
    )
  }

  test("flatMap") {
    val info = getInfo("scala/collection/immutable/List#flatMap().")
    assertEquals(info.kind(), PcSymbolKind.METHOD)
    assertNoDiff(info.classOwner(), "scala/collection/immutable/List#")
    val correctOverridden =
      if (scalaVersion.startsWith("2.12") || scalaVersion.startsWith("2.11")) {
        """|scala/collection/TraversableLike#flatMap().
           |scala/collection/GenTraversableLike#flatMap().
           |scala/collection/generic/FilterMonadic#flatMap().
           |""".stripMargin
      } else {
        """|scala/collection/StrictOptimizedIterableOps#flatMap().
           |scala/collection/IterableOps#flatMap().
           |scala/collection/IterableOnceOps#flatMap().
           |""".stripMargin
      }
    assertNoDiff(
      info.overriddenSymbols().asScala.mkString("\n"),
      correctOverridden
    )
  }
}
