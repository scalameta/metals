package tests

import java.nio.file.Files
import scala.meta.languageserver.Formatter
import org.langmeta.internal.io.PathIO
import org.langmeta.io.AbsolutePath

object FormatterTest extends MegaSuite {

  test("noop does nothing") {
    assertNoDiff(Formatter.noop.format("blah", ""), "blah")
    assertNoDiff(
      Formatter.noop.format("blah", "", PathIO.workingDirectory),
      "blah"
    )
  }

  lazy val scalafmt: Formatter = Formatter.classloadScalafmt("1.3.0")
  val original = "object     a       { val x   = 2}"

  test("scalafmt with no config") {
    val obtained = scalafmt.format(original, "a.scala")
    val expected = "object a { val x = 2 }"
    assertNoDiff(obtained, expected)
  }

  test("scalafmt with config") {
    val config = "maxColumn = 10"
    val file = Files.createTempFile("scalafmt", ".scalafmt.conf")
    file.toFile.deleteOnExit()
    Files.write(file, config.getBytes)
    val obtained = scalafmt.format(
      original,
      "a.scala",
      AbsolutePath(file)
    )
    val expected =
      """object a {
        |  val x =
        |    2
        |}""".stripMargin
    assertNoDiff(obtained, expected)
  }

}
