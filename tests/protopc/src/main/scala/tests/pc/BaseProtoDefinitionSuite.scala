package tests.pc

import java.net.URI
import java.nio.file.Paths

import scala.meta.internal.metals.CompilerOffsetParams

import munit.Location
import munit.TestOptions

class BaseProtoDefinitionSuite extends BaseProtoPCSuite {

  def check(
      testOpt: TestOptions,
      original: String,
      expected: String,
      uri: URI = Paths.get("Definition.proto").toUri(),
  )(implicit loc: Location): Unit = {
    test(testOpt) {
      val filename = "Definition.proto"
      val (code, offset) = params(original, filename)

      val pcParams = CompilerOffsetParams(uri, code, offset)
      val result = presentationCompiler.definition(pcParams).get()

      val locations = result.locations()
      val obtained = if (locations.isEmpty()) {
        "No definition found"
      } else {
        val location = locations.get(0)
        val range = location.getRange()
        s"${range.getStart().getLine()}:${range.getStart().getCharacter()}-${range.getEnd().getLine()}:${range.getEnd().getCharacter()}"
      }

      assertEquals(obtained, expected)
    }
  }

  def checkSymbol(
      testOpt: TestOptions,
      original: String,
      expected: String,
      uri: URI = Paths.get("Definition.proto").toUri(),
  )(implicit loc: Location): Unit = {
    test(testOpt) {
      val filename = "Definition.proto"
      val (code, offset) = params(original, filename)

      val pcParams = CompilerOffsetParams(uri, code, offset)
      val result = presentationCompiler.definition(pcParams).get()

      assertEquals(result.symbol(), expected)
    }
  }

  def checkFiles(
      testOpt: TestOptions,
      files: Map[String, String],
      targetFile: String,
      expected: String,
  )(implicit loc: Location): Unit = {
    test(testOpt) {
      // Write all files to the temp directory so imports can resolve
      import java.nio.file.Files
      import scala.jdk.CollectionConverters._

      files.foreach { case (filename, content) =>
        val path = tmp.resolve(filename).toNIO
        Files.createDirectories(path.getParent())
        Files.writeString(path, content)
      }

      // Create a new presentation compiler with the temp directory as import path
      import scala.meta.internal.pc.PresentationCompilerConfigImpl
      import scala.meta.pc.ProtobufLspConfig
      val pc = new scala.meta.internal.protopc.ProtoPresentationCompiler()
        .withConfiguration(
          PresentationCompilerConfigImpl()
            .copy(
              emitDiagnostics = true,
              protobufLspConfig = ProtobufLspConfig.ENABLED,
            )
        )
        .newInstance("test", List(tmp.toNIO).asJava, Nil.asJava)

      // Get the code and offset from the target file
      val (code, offset) = params(files(targetFile), targetFile)
      val uri = tmp.resolve(targetFile).toNIO.toUri()

      val pcParams = CompilerOffsetParams(uri, code, offset)
      val result = pc.definition(pcParams).get()

      assertEquals(result.symbol(), expected)
    }
  }
}
