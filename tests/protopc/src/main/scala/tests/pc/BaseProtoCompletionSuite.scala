package tests.pc

import java.net.URI
import java.nio.file.Paths

import scala.jdk.CollectionConverters._

import scala.meta.internal.metals.CompilerOffsetParams

import munit.Location
import munit.TestOptions

class BaseProtoCompletionSuite extends BaseProtoPCSuite {

  def check(
      testOpt: TestOptions,
      original: String,
      expected: String,
      uri: URI = Paths.get("Completion.proto").toUri(),
  )(implicit loc: Location): Unit = {
    test(testOpt) {
      val filename = "Completion.proto"
      val (code, offset) = params(original, filename)

      val pcParams = CompilerOffsetParams(uri, code, offset)
      val result = presentationCompiler.complete(pcParams).get()

      val items = result.getItems().asScala.map(_.getLabel()).toList
      val obtained = items.sorted.mkString("\n")
      val expectedItems = expected.trim.split("\n").toList.sorted.mkString("\n")

      assertNoDiff(obtained, expectedItems)
    }
  }

  def checkContains(
      testOpt: TestOptions,
      original: String,
      expected: List[String],
      uri: URI = Paths.get("Completion.proto").toUri(),
  )(implicit loc: Location): Unit = {
    test(testOpt) {
      val filename = "Completion.proto"
      val (code, offset) = params(original, filename)

      val pcParams = CompilerOffsetParams(uri, code, offset)
      val result = presentationCompiler.complete(pcParams).get()

      val items = result.getItems().asScala.map(_.getLabel()).toSet

      for (item <- expected) {
        assert(
          items.contains(item),
          s"Expected completion '$item' not found. Available: ${items.mkString(", ")}",
        )
      }
    }
  }
}
