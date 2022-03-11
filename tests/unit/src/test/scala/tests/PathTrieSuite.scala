package tests

import java.nio.file.Path
import java.nio.file.Paths

import scala.meta.internal.metals.watcher.PathTrie

class PathTrieSuite extends BaseSuite {
  private val root = Paths.get(".").toAbsolutePath().getRoot()
  private val `/foo` = root.resolve("foo")
  private val `/bar` = root.resolve("bar")
  private val `/foo/bar` = `/foo`.resolve("bar")
  private val `/foo/bar/src1.scala` = `/foo/bar`.resolve("src1.scala")
  private val `/foo/bar/src2.scala` = `/foo/bar`.resolve("src2.scala")
  private val `/foo/fizz/buzz.scala` =
    `/foo`.resolve("fizz").resolve("buzz.scala")

  test("longestPrefixes stops at terminal nodes") {
    assertEquals(
      rootsOf(
        Set(
          `/foo/bar`,
          `/foo/bar/src1.scala`,
          `/foo/bar/src2.scala`
        )
      ),
      Set(`/foo/bar`)
    )
  }

  test("longestPrefixes respects max roots") {
    assertEquals(
      rootsOf(
        Set(
          `/foo/bar/src1.scala`,
          `/foo/bar/src2.scala`,
          `/foo/fizz/buzz.scala`
        ),
        maxRoots = 2
      ),
      Set(`/foo/bar`, `/foo/fizz/buzz.scala`)
    )
  }

  test("no common prefix") {
    assertEquals(
      rootsOf(
        Set(
          `/foo/bar/src1.scala`,
          `/foo/bar/src2.scala`,
          `/bar`
        ),
        maxRoots = 1
      ),
      Set(root)
    )
  }

  private def rootsOf(paths: Set[Path], maxRoots: Int = 32): Set[Path] = {
    PathTrie(paths).longestPrefixes(root, maxRoots).toSet
  }
}
