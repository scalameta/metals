package tests

import scala.meta.dialects
import scala.meta.inputs.Input
import scala.meta.internal.io.FileIO
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.io.AbsolutePath

/**
 * Assert the symbols emitted by ScalaToplevelMtags is a subset of ScalaMtags
 */
class ScalaToplevelLibrarySuite extends BaseSuite {
  val scala2TestClasspath: List[AbsolutePath] =
    Library.allScala2.flatMap(_.sources.entries)

  val scala3TestClasspath: List[AbsolutePath] = Library.scala3.sources.entries

  val scala3ExclusionList: Set[String] = Set(
    // [scalameta] erased modifier support isn't implemeneted yet
    "/scala/CanThrow.scala"
  )

  scala2TestClasspath.foreach { entry =>
    test(entry.toNIO.getFileName.toString) {
      forAllFilesInJar(entry) { file =>
        val input = file.toInput
        val scalaMtags = Mtags.toplevels(Mtags.index(input, dialects.Scala213))
        val scalaToplevelMtags = Mtags.toplevels(input)

        assertTopLevels(scalaToplevelMtags, scalaMtags, input)

        // also check that scala3Toplevels parse files identically
        // to scala2 parser
        if (!scala3ExclusionList(file.toString)) {
          val scala3Toplevels =
            Mtags.toplevels(input, dialect = dialects.Scala3)
          assertTopLevels(scala3Toplevels, scalaMtags, input)
        }
      }
    }
  }

  scala3TestClasspath.foreach { entry =>
    test(entry.toNIO.getFileName.toString) {
      forAllFilesInJar(entry) { file =>
        if (!scala3ExclusionList.contains(file.toString)) {
          val input = file.toInput
          val scalaMtags = Mtags.toplevels(Mtags.index(input, dialects.Scala3))
          val scalaToplevelMtags = Mtags.toplevels(input, dialects.Scala3)
          assertTopLevels(scalaToplevelMtags, scalaMtags, input)
        }
      }
    }
  }

  private def assertTopLevels(
      obtained: List[String],
      expected: List[String],
      input: Input.VirtualFile
  ): Unit = {
    assertNoDiff(
      obtained.mkString("\n"),
      expected.mkString("\n"),
      s"${input.path}\n${input.text}"
    )
  }

  private def forAllFilesInJar[A](
      jar: AbsolutePath
  )(f: AbsolutePath => Unit): Unit = {
    FileIO.withJarFileSystem(jar, create = false) { root =>
      FileIO.listAllFilesRecursively(root).foreach { file =>
        if (file.toNIO.getFileName.toString.endsWith(".scala")) {
          f(file)
        }
      }
    }
  }

}
