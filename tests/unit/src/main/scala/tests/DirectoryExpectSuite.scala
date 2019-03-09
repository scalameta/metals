package tests

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.meta.internal.metals.RecursivelyDelete
import scala.meta.io.AbsolutePath

/**
 * Expect test with multiple output files.
 *
 * @param directoryName root directory name: metals/src/test/resources/$name
 */
abstract class DirectoryExpectSuite(directoryName: String)
    extends BaseExpectSuite(directoryName) {

  def testCases(): List[ExpectTestCase]

  final lazy val expectRoot: AbsolutePath =
    AbsolutePath(BuildInfo.testResourceDirectory).resolve(directoryName)
  final def test(unitTest: ExpectTestCase): Unit = {
    val testName =
      unitTest.input.file.toNIO.getFileName.toString.stripSuffix(".scala")
    test(testName) {
      val obtained = unitTest.obtained()
      unitTest.input.slurpExpected(directoryName) match {
        case Some(expected) =>
          DiffAssertions.expectNoDiff(obtained, expected)
        case None =>
          val expect = unitTest.input.expectPath(directoryName).toNIO
          fail(s"does not exist: $expect (run save-expect to fix this problem)")
      }
    }
  }

  final def saveExpect(): Unit = {
    RecursivelyDelete.apply(expectRoot)
    testCases().foreach { testCase =>
      val obtained = testCase.obtained()
      val file = testCase.input.expectPath(directoryName).toNIO
      Files.createDirectories(file.getParent)
      println(s"write: $file")
      Files.write(file, obtained.getBytes(StandardCharsets.UTF_8))
    }
  }

  testCases().foreach(test)
}
