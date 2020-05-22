package tests

import scala.meta.internal.metals.Fuzzy
import scala.meta.internal.metals.WorkspaceSymbolQuery

import munit.Location

class FuzzySuite extends BaseSuite {
  def checkOK(query: String, symbol: String)(implicit loc: Location): Unit = {
    test(query) {
      val obtained = WorkspaceSymbolQuery.fromTextQuery(query).matches(symbol)
      Predef.assert(
        obtained,
        s"query '$query' is not substring of symbol '$symbol'"
      )
    }
  }

  def checkNO(query: String, symbol: String)(implicit loc: Location): Unit = {
    test(query) {
      val obtained = WorkspaceSymbolQuery.fromTextQuery(query).matches(symbol)
      Predef.assert(
        !obtained,
        s"query '$query' was a substring of symbol '$symbol'"
      )
    }
  }

  checkOK("scala.concurrent.package", "scala/concurrent/package.class")
  checkOK("scala.concurrent", "scala/concurrent/package.class")

  checkOK("::", "scala/collection/immutable/`::`#")
  checkOK("IO", "scala/IO#")
  checkOK("IS", "scala/InputOutputStream#")
  checkNO("Mon", "ModuleKindJS")
  checkNO("Min", "MavenPluginIntegration")
  checkOK("DoSymPro", "DocumentSymbolProvider")
  checkNO("DoymPro", "DocumentSymbolProvider")
  checkOK("Maven", "ch/epfl/MavenPluginIntegration.")
  checkOK("imm.List", "scala/collection/immutable/List#")
  checkNO("mm.List", "scala/collection/List#")
  checkOK("s.i.Li", "scala/collection/immutable/List#")
  checkOK("s.c.i.Li", "scala/collection/immutable/List#")
  checkOK("Week.Mon", "scala/Weekday.Monday")
  checkNO("Week.Mon", "scala/Monday")
  checkNO("nner", "a/Inner#")
  checkNO("FoxBar", "a/FooxBar#")
  checkOK("FooxBar", "a/FooxBar#")
  checkNO("FooxBr", "a/FooxBar#")
  checkNO("Files", "a/FileStream#")
  checkOK("coll.TrieMap", "scala/collection/concurrent/TrieMap.")
  checkOK("m.Pos.", "scala/meta/Position.Range#")
  checkNO("m.Posi.", "scala/meta/Position.")

  def checkWords(in: String, expected: String): Unit = {
    val name = in.replaceAll("[^a-zA-Z0-9]", " ").trim
    val start = name.lastIndexOf(' ') + 1
    test(name.substring(start)) {
      val obtained = Fuzzy
        .bloomFilterQueryStrings(in, includeTrigrams = false)
        .toSeq
        .map(_.toString)
        .sorted
      val isPrefix = Fuzzy.bloomFilterSymbolStrings(Seq(in))
      assertNoDiff(obtained.mkString("\n"), expected)
      val allWords = Fuzzy.bloomFilterQueryStrings(in).map(_.toString)
      val isNotPrefix = allWords.filterNot(word => isPrefix.mightContain(word))
      assert(isNotPrefix.isEmpty)
    }
  }

  checkWords(
    "jdocs.persistence.PersistenceSchemaEvolutionDocTest.SimplestCustomSerializer",
    """|Custom
       |Doc
       |Evolution
       |Persistence
       |Schema
       |Serializer
       |Simplest
       |Test
       |jdocs
       |persistence
       |""".stripMargin
  )

  checkWords(
    "FSMStateFunctionBuilder",
    """|Builder
       |F
       |Function
       |M
       |S
       |State
    """.stripMargin
  )
  checkWords(
    "FSM",
    """|F
       |M
       |S
       |""".stripMargin
  )
  checkWords(
    "FSM",
    """|F
       |M
       |S
       |""".stripMargin
  )
  checkWords(
    "lowercase",
    "lowercase"
  )
  checkOK("Stop", "SaStop")
  checkOK("StopBu", "SaStopBuilder")

  checkWords(
    "akka.persistence.serialization.MessageFormats#PersistentFSMSnapshot",
    """|F
       |Formats
       |M
       |Message
       |Persistent
       |S
       |Snapshot
       |akka
       |persistence
       |serialization
       |""".stripMargin
  )

  test("estimatedSize") {
    // All uppercase inputs are most adversarial because we index all trigram
    // uppercase combinations.
    val alphabet = 'A'.to('Z').map(_.toChar).mkString
    val bloom = Fuzzy.bloomFilterSymbolStrings(List(alphabet))
    // Assert that the expected false positive ratio remains
    // reasonable despite pathological input.
    assert(bloom.bloom.expectedFpp() < 0.02)
  }

  test("shortClassName") {
    assert(!Fuzzy.isExactMatch("AA", "A.class"))
  }
}
