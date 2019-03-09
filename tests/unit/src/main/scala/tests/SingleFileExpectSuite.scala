package tests

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.meta.internal.io.FileIO
import scala.meta.io.AbsolutePath

/**
 * An expect suite with a single output file.
 *
 * @param filename The name of the single expect file.
 */
abstract class SingleFileExpectSuite(filename: String)
    extends BaseExpectSuite(filename) {
  def obtained(): String

  final val path: AbsolutePath =
    SingleFileExpectSuite.expectRoot.resolve(filename)

  final override def saveExpect(): Unit = {
    println(s"write: $path")
    Files.write(
      path.toNIO,
      obtained().getBytes(StandardCharsets.UTF_8)
    )
  }

  final def expected(): String = {
    if (!path.isFile) {
      Files.createDirectories(path.toNIO.getParent)
      Files.createFile(path.toNIO)
    }
    FileIO.slurp(path, StandardCharsets.UTF_8)
  }

  test(filename) {
    val obtainedString = obtained()
    val expectedString = expected()
    DiffAssertions.expectNoDiff(
      obtainedString,
      expectedString
    )
  }
}

object SingleFileExpectSuite {
  def expectRoot: AbsolutePath =
    AbsolutePath(BuildInfo.testResourceDirectory).resolve("expect")
}
