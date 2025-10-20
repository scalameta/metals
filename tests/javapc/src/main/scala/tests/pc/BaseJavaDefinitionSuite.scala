package tests.pc

import java.nio.file.Paths

import scala.jdk.CollectionConverters._

import scala.meta.inputs.Input
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.metals.PositionSyntax._
import scala.meta.pc.DefinitionResult

import tests.pc.BaseJavaPCSuite

abstract class BaseJavaDefinitionSuite extends BaseJavaPCSuite {

  // Open for overriding, for example to do typeDefinition instead of definition
  def doDefinitionRequest(params: CompilerOffsetParams): DefinitionResult = {
    presentationCompiler.definition(params).get()
  }

  def check(
      options: munit.TestOptions,
      original: String,
      expected: String,
  )(implicit loc: munit.Location): Unit = {
    test(options) {
      val filename = "Definition.java"
      val pkg = packageName(options.name)
      val codeOriginal = s"package $pkg;\n${original}"
      val (code, so) = params(codeOriginal, filename)
      val uri = Paths.get(filename).toUri()
      val definition = doDefinitionRequest(CompilerOffsetParams(uri, code, so))
      val obtained = definition
        .locations()
        .asScala
        .map { loc =>
          if (loc.getUri() == uri.toString()) {
            val input = Input.VirtualFile(filename, code)
            loc.getRange().formatMessage("info", "definition", input)
          } else {
            loc.getUri().toString()
          }
        }
        .mkString("\n")
      assertNoDiff(obtained, expected)
    }

  }
}
