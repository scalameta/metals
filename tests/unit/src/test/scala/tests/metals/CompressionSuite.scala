package tests.metals

import scala.meta.internal.metals.Compression
import scala.meta.internal.metals.ClassfileElementPart

object CompressionSuite extends BaseSuite {
  def checkRoundtrip(a: String): Unit = {
    val nonempty = a.trim.linesIterator.map(_.trim).filterNot(_.isEmpty).toArray
    test(nonempty.headOption.getOrElse("<empty>")) {
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
