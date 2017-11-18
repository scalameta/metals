package tests

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigRenderOptions
import org.scalameta.logger

object SnapshotPrinterTest extends MegaSuite {
  // does a roundtrip via SnapshotPrinter and asserts that the
  // printed output parses back into the original Config.
  def checkBijective(config: String): Unit = {
    test(logger.revealWhitespace(config)) {
      val parsed = ConfigFactory.parseString(config.replace('\'', '\"'))
      val rendered = SnapshotPrinter.render(parsed)
      val options =
        ConfigRenderOptions.defaults().setOriginComments(false).setJson(false)
      val obtained = ConfigFactory.parseString(rendered).root().render(options)
      val expected = parsed.root().render(options)
      assertNoDiff(obtained, expected)
    }
  }
  checkBijective(
    """
      |a = a
      |b = "b b"
      |c = '''
      |multiline
      |string
      |'''
      |d = "1"
      |e = "true"
      |f = "null"
    """.stripMargin
  )
}
