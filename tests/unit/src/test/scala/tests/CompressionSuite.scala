package tests

import scala.meta.internal.metals.ClassfileElementPart
import scala.meta.internal.metals.Compression

import munit.Location

class CompressionSuite extends BaseSuite {
  def checkRoundtrip(a: String)(implicit loc: Location): Unit = {
    val nonempty = a.trim.linesIterator.map(_.trim).filterNot(_.isEmpty).toArray
    val testName = nonempty.headOption.getOrElse("<empty>")
    test(testName) {
      val compressed = Compression.compress(
        nonempty.iterator.filter(_.nonEmpty).map(ClassfileElementPart(_))
      )
      val decompressed = Compression.decompress(compressed).map(_.filename)
      assertNoDiff(decompressed.mkString("\n"), nonempty.mkString("\n"))
    }
  }

  checkRoundtrip(
    """
      |InputStream.class
      |InputFileStream.class
      |InputFileProgressStream.class
      |""".stripMargin
  )

}
