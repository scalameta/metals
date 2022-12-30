package tests

import scala.meta.inputs.Input
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.{semanticdb => s}

import org.eclipse.{lsp4j => l}

class MtagsEnrichmentsSuite extends BaseSuite {

  test("LspRange-single-line") {
    def r(start: l.Position, end: l.Position) = new l.Range(start, end)
    def p(line: Int, character: Int) = new l.Position(line, character)
    assert(r(p(5, 0), p(5, 10)).encloses(p(5, 0)))
    assert(r(p(5, 0), p(5, 10)).encloses(p(5, 5)))
    assert(r(p(5, 0), p(5, 10)).encloses(p(5, 10)))
    assert(r(p(5, 1), p(5, 10)).encloses(p(5, 0)) == false)
    assert(r(p(5, 0), p(5, 10)).encloses(p(5, 11)) == false)
    assert(r(p(5, 0), p(5, 10)).encloses(p(4, 5)) == false)
    assert(r(p(5, 0), p(5, 10)).encloses(p(6, 5)) == false)
  }

  test("LspRange-multi-line") {
    def r(start: l.Position, end: l.Position) = new l.Range(start, end)
    def p(line: Int, character: Int) = new l.Position(line, character)
    assert(r(p(2, 10), p(5, 10)).encloses(p(2, 10)))
    assert(r(p(2, 10), p(5, 10)).encloses(p(3, 0)))
    assert(r(p(2, 10), p(5, 10)).encloses(p(3, 15)))
    assert(r(p(2, 10), p(5, 10)).encloses(p(5, 0)))
    assert(r(p(2, 10), p(5, 10)).encloses(p(5, 10)))
    assert(r(p(2, 10), p(5, 10)).encloses(p(2, 9)) == false)
    assert(r(p(2, 10), p(5, 10)).encloses(p(1, 10)) == false)
    assert(r(p(2, 10), p(5, 10)).encloses(p(5, 11)) == false)
  }

  test("LspRange-overlaps") {
    def r(start: l.Position, end: l.Position) = new l.Range(start, end)
    def p(line: Int, character: Int) = new l.Position(line, character)
    // same line
    assert(r(p(2, 10), p(2, 20)).overlapsWith(r(p(2, 8), p(2, 12))))
    assert(r(p(2, 10), p(2, 20)).overlapsWith(r(p(2, 18), p(2, 22))))
    assert(r(p(2, 10), p(2, 20)).overlapsWith(r(p(2, 6), p(2, 9))) == false)
    assert(r(p(2, 10), p(2, 20)).overlapsWith(r(p(2, 21), p(2, 30))) == false)
    // multi line
    assert(r(p(2, 10), p(5, 10)).overlapsWith(r(p(1, 10), p(4, 10))))
    assert(r(p(2, 10), p(5, 10)).overlapsWith(r(p(3, 10), p(6, 10))))
    assert(r(p(2, 10), p(5, 10)).overlapsWith(r(p(2, 8), p(4, 10))))
    assert(r(p(2, 10), p(5, 10)).overlapsWith(r(p(1, 10), p(2, 9))) == false)
    assert(r(p(2, 10), p(5, 10)).overlapsWith(r(p(5, 11), p(6, 10))) == false)
  }

  test("SemanticdbRange-single-line") {
    def r(start: (Int, Int), end: (Int, Int)) =
      s.Range(start._1, start._2, end._1, end._2)

    assert(r((5, 0), (5, 10)).encloses(r((5, 0), (5, 10))))
    assert(r((5, 0), (5, 10)).encloses(r((5, 0), (5, 9))))
    assert(r((5, 0), (5, 10)).encloses(r((5, 1), (5, 9))))
    assert(r((5, 0), (5, 10)).encloses(r((5, 1), (5, 10))))
    assert(r((5, 0), (5, 10)).encloses(r((5, 5), (5, 11))) == false)
    assert(r((5, 0), (5, 10)).encloses(r((4, 1), (4, 9))) == false)
    assert(r((5, 0), (5, 10)).encloses(r((6, 1), (6, 9))) == false)
  }

  test("SemanticdbRange-multiline") {
    def r(start: (Int, Int), end: (Int, Int)) =
      s.Range(start._1, start._2, end._1, end._2)
    assert(r((2, 0), (5, 10)).encloses(r((2, 0), (5, 10))))
    assert(r((2, 0), (5, 10)).encloses(r((2, 0), (5, 9))))
    assert(r((2, 0), (5, 10)).encloses(r((2, 1), (5, 9))))
    assert(r((2, 0), (5, 10)).encloses(r((3, 1), (4, 9))))
    assert(r((2, 0), (5, 10)).encloses(r((2, 1), (5, 10))))
    assert(r((2, 0), (5, 10)).encloses(r((5, 5), (5, 11))) == false)
    assert(r((2, 0), (5, 10)).encloses(r((6, 1), (6, 9))) == false)
    assert(r((2, 0), (5, 10)).encloses(r((1, 10), (3, 10))) == false)
    assert(r((2, 0), (5, 10)).encloses(r((1, 10), (6, 10))) == false)
    assert(r((2, 0), (5, 10)).encloses(r((4, 10), (5, 11))) == false)
    assert(r((2, 0), (5, 10)).encloses(r((4, 10), (6, 10))) == false)
  }

  test("SemanticdbRange-lsp-multi-line") {
    def r(start: (Int, Int), end: (Int, Int)) =
      s.Range(start._1, start._2, end._1, end._2)
    def p(line: Int, character: Int) = new l.Position(line, character)
    assert(r((2, 10), (5, 10)).encloses(p(2, 10)))
    assert(r((2, 10), (5, 10)).encloses(p(3, 0)))
    assert(r((2, 10), (5, 10)).encloses(p(3, 15)))
    assert(r((2, 10), (5, 10)).encloses(p(5, 0)))
    assert(
      r((2, 10), (5, 10))
        .encloses(p(5, 10), includeLastCharacter = false) == false
    )
    assert(
      r((2, 10), (5, 10))
        .encloses(p(5, 10), includeLastCharacter = true) == true
    )
    assert(r((2, 10), (5, 10)).encloses(p(2, 9)) == false)
    assert(r((2, 10), (5, 10)).encloses(p(1, 10)) == false)
    assert(r((2, 10), (5, 10)).encloses(p(5, 11)) == false)
  }

  test("filename") {

    def assertFilename(path: String, expected: String) = {
      val filename = Input.VirtualFile(path, "").filename
      assertEquals(filename, expected)
    }

    assertFilename("file:///a/v/main.scala", "main.scala")
    assertFilename("/a/v/main.scala", "main.scala")
    assertFilename(
      "jar:file:///C:/Users/A C/AppData/Local/Coursier/cache/v1/https/repo1.maven.org/maven2/org/scala-lang/scala3-library_3/3.2.0/scala3-library_3-3.2.0-sources.jar!/scala/Tuple.scala",
      "Tuple.scala",
    )
  }

}
