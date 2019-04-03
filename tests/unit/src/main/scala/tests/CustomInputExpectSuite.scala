package tests

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import tests.BuildInfo.testResourceDirectory

import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.RecursivelyDelete
import scala.meta.io.AbsolutePath

/**
 * Suite for expect-tests with custom input files. Expects the following structure:
 * <p>- resources/[[directoryName]]/input</p>
 * <p>- resources/[[directoryName]]/expect</p>
 */
abstract class CustomInputExpectSuite(directoryName: String)
    extends BaseExpectSuite(directoryName) {

  private val root = AbsolutePath(testResourceDirectory).resolve(directoryName)
  private val inputDirectory = root.resolve("input")
  private val expectDirectory = root.resolve("expect")

  protected def obtainFrom(file: InputFile): String

  final def testCases: Seq[InputFile] =
    for {
      file <- FileIO.listAllFilesRecursively(inputDirectory)
    } yield InputFile(file, inputDirectory, file.toRelative(sourceroot))

  final def test(input: InputFile): Unit = {
    test(testName(input)) {
      val obtained = obtainFrom(input)
      val expected = expectFor(input)

      DiffAssertions.expectNoDiff(obtained, expected)
    }
  }

  override final def saveExpect(): Unit = {
    RecursivelyDelete.apply(expectDirectory)
    for { testCase <- testCases } {
      val obtained = obtainFrom(testCase)
      val file = expectedOutputFileFor(testCase).toNIO

      Files.createDirectories(file.getParent)
      println(s"write: $file")
      Files.write(file, obtained.getBytes(StandardCharsets.UTF_8))
    }
  }

  final def expectFor(input: InputFile): String = {
    val expectedOutput = expectedOutputFileFor(input)

    if (expectedOutput.isFile) {
      FileIO.slurp(expectedOutput, StandardCharsets.UTF_8)
    } else {
      fail(
        s"Nothing is expected for ${testName(input)}. Run save-expect to generate expected output"
      )
    }
  }

  private def testName(input: InputFile): String =
    input.file.toNIO.getFileName.toString.stripSuffix(".scala")

  private def expectedOutputFileFor(input: InputFile): AbsolutePath = {
    val relativePath = input.file.toRelative(inputDirectory)
    expectDirectory.resolve(relativePath)
  }

  testCases.foreach(test)
}
