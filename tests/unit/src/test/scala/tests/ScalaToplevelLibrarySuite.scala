package tests

import scala.meta.dialects
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

  val hasXmlPrefixes: List[String] = List(
    "/scala/xml/", "/org/apache/spark/ui", "/org/apache/spark/sql/execution/ui",
    "/scala/tools/nsc/doc/html/", "/org/apache/spark/deploy/history/",
    "/org/apache/spark/deploy/worker/ui/", "/org/apache/spark/deploy/master/ui/"
  )
  def hasXML(f: AbsolutePath): Boolean = {
    val str = f.toString
    hasXmlPrefixes.exists(str.startsWith)
  }

  val scala3ExclusionList: Set[String] = Set(
    // [scalameta] erased modifier - for now used internally, will be available in 3.1
    "/scala/compiletime/package.scala"
  )

  scala2TestClasspath.foreach { entry =>
    test(entry.toNIO.getFileName.toString) {
      forAllFilesInJar(entry) { file =>
        val input = file.toInput
        val scalaMtags = Mtags.toplevels(Mtags.index(input, dialects.Scala213))
        val scalaToplevelMtags = Mtags.toplevels(input)

        val obtained = scalaToplevelMtags.mkString("\n")
        val expected = scalaMtags.mkString("\n")
        assertNoDiff(obtained, expected, input.text)

        // also check that scala3Toplevels parse files identically
        // to scala2 parser
        if (!hasXML(file)) {
          val scala3Toplevels =
            Mtags.toplevels(input, dialect = dialects.Scala3)
          assertNoDiff(
            scala3Toplevels.mkString("\n"),
            obtained,
            file.toString
          )
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
          val obtained = scalaToplevelMtags.mkString("\n")
          val expected = scalaMtags.mkString("\n")
          assertNoDiff(obtained, expected, input.text)
        }
      }
    }
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
