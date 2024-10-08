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
class ToplevelLibrarySuite extends BaseSuite {
  val scala2TestClasspath: List[AbsolutePath] =
    Library.allScala2.flatMap(_.sources.entries)

  val scala3TestClasspath: List[AbsolutePath] = Library.scala3.sources.entries

  val scala3ExclusionList: Set[String] = Set(
    "/scala/Singleton.scala"
  )

  val scala2ExclusionList: Set[String] = Set(
    "/scala/Singleton.scala"
  )
  val javaTestClasspath: List[AbsolutePath] = Library.damlrxjavaSources

  scala2TestClasspath.foreach { entry =>
    test(entry.toNIO.getFileName.toString) {
      forAllScalaFilesInJar(entry) { file =>
        if (!scala2ExclusionList.contains(file.toString)) {
          val input = file.toInput
          val scalaMtags = Mtags.toplevels(Mtags.index(file, dialects.Scala213))
          val scalaToplevelMtags = Mtags.topLevelSymbols(file)

          assertTopLevels(scalaToplevelMtags, scalaMtags, input)

          // also check that scala3Toplevels parse files identically
          // to scala2 parser
          if (!scala3ExclusionList(file.toString)) {
            val scala3Toplevels =
              Mtags.topLevelSymbols(file, dialect = dialects.Scala3)
            assertTopLevels(scala3Toplevels, scalaMtags, input)
          }
        }
      }
    }
  }

  scala3TestClasspath.foreach { entry =>
    test(entry.toNIO.getFileName.toString) {
      forAllScalaFilesInJar(entry) { file =>
        if (!scala3ExclusionList.contains(file.toString)) {
          val input = file.toInput
          val scalaMtags = Mtags.toplevels(Mtags.index(file, dialects.Scala3))
          val scalaToplevelMtags = Mtags.topLevelSymbols(file, dialects.Scala3)
          assertTopLevels(scalaToplevelMtags, scalaMtags, input)
        }
      }
    }
  }

  javaTestClasspath.foreach { entry =>
    test(entry.toNIO.getFileName.toString) {
      forAllJavaFilesInJar(entry) { file =>
        val input = file.toInput
        val javaMtags = Mtags.toplevels(Mtags.index(file, dialects.Scala3))
        val javaToplevelMtags = Mtags.topLevelSymbols(file, dialects.Scala3)
        assertTopLevels(javaToplevelMtags, javaMtags, input)
      }
    }
  }

  private def assertTopLevels(
      obtained: List[String],
      expected: List[String],
      input: Input.VirtualFile,
  ): Unit = {
    assertNoDiff(
      obtained.mkString("\n"),
      expected.mkString("\n"),
      s"${input.path}",
    )
  }

  private def forAllScalaFilesInJar[A](jar: AbsolutePath)(
      f: AbsolutePath => Unit
  ): Unit =
    forAllFilesInJar(jar, ".scala")(f)

  private def forAllJavaFilesInJar[A](jar: AbsolutePath)(
      f: AbsolutePath => Unit
  ): Unit =
    forAllFilesInJar(jar, ".java")(f)

  private def forAllFilesInJar[A](
      jar: AbsolutePath,
      ext: String,
  )(f: AbsolutePath => Unit): Unit = {
    FileIO.withJarFileSystem(jar, create = false) { root =>
      FileIO.listAllFilesRecursively(root).foreach { file =>
        val filename = file.toNIO.getFileName.toString
        if (filename.endsWith(ext)) {
          f(file)
        }
      }
    }
  }

}
