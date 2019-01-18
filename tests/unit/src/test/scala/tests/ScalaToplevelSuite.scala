package tests

import scala.meta.internal.io.FileIO
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.mtags.Mtags

/**
 * Assert the symbols emitted by ScalaToplevelMtags is a subset of ScalaMtags
 */
class ScalaToplevelSuite extends BaseSuite {
  val testClasspath = Library.all.flatMap(_.sources.entries)
  testClasspath.foreach { entry =>
    test(entry.toNIO.getFileName.toString) {
      FileIO.withJarFileSystem(entry, create = false) { root =>
        FileIO.listAllFilesRecursively(root).foreach { file =>
          if (file.toNIO.getFileName.toString.endsWith(".scala")) {
            val input = file.toInput
            val scalaMtags = Mtags.toplevels(Mtags.index(input))
            val scalaToplevelMtags = Mtags.toplevels(input)
            val obtained = scalaToplevelMtags.mkString("\n")
            val expected = scalaMtags.mkString("\n")
            assertNoDiff(obtained, expected, input.text)
          }
        }
      }
    }
  }

}
