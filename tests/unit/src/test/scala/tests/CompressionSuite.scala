package tests

import scala.meta.internal.metals.Compression

object CompressionSuite extends BaseSuite {
  def checkRoundtrip(a: String): Unit = {
    val nonempty = a.trim.lines.map(_.trim).filterNot(_.isEmpty).toArray
    test(nonempty.headOption.getOrElse("<empty>")) {
      val compressed = Compression.compress(nonempty)
      val decompressed = Compression.decompress(compressed)
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
