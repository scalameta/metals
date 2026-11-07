package tests.pc

import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

import scala.meta.internal.metals.CompilerVirtualFileParams
import scala.meta.internal.pc.PresentationCompilerConfigImpl
import scala.meta.internal.semanticdb.Diagnostic
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.pc.SourcePathMode

import tests.BasePCSuite

class SourcePathIdentitySuite extends BasePCSuite {
  protected def sourcePathMode: SourcePathMode = SourcePathMode.PRUNED

  override protected def config: PresentationCompilerConfigImpl =
    PresentationCompilerConfigImpl(sourcePathMode = sourcePathMode)

  override protected def sourceFiles: Seq[Path] =
    List("Declarations with spaces.scala", "Caller.scala").map(
      tmp.resolve(_).toNIO
    )

  private val declarations =
    """package example
      |object Model {
      |  def apply(value: Int): Model = new Model(value.toString)
      |}
      |final case class Model(value: String) {
      |  def duplicate: Model = Model(value)
      |}
      |final case class Generated(value: String)
      |""".stripMargin
  private val caller =
    "package example; object Caller { def make = Model(\"sample\"); def generated = Generated(\"sample\") }"

  private def query(path: Path, text: String): TextDocument =
    TextDocument.parseFrom(
      presentationCompiler
        .semanticdbTextDocument(
          CompilerVirtualFileParams(
            path.toUri,
            text,
            shouldReturnDiagnostics = true
          )
        )
        .get(30, TimeUnit.SECONDS)
    )

  private def errors(document: TextDocument) =
    document.diagnostics.filter(_.severity == Diagnostic.Severity.ERROR)

  private def prepare(): (Path, Path) = {
    val model = tmp.resolve("Declarations with spaces.scala").toNIO
    val calls = tmp.resolve("Caller.scala").toNIO
    Files.write(model, declarations.getBytes(UTF_8))
    Files.write(calls, caller.getBytes(UTF_8))
    presentationCompiler.restart()
    (model, calls)
  }

  test("source-path-promotion-retains-generated-companion-and-body") {
    val (model, calls) = prepare()
    // The first public request loads declarations through the source path.
    assertEquals(errors(query(calls, caller)), Nil)
    // Explicitly requesting that same file must export full compiler facts.
    val document = query(model, declarations)
    assertEquals(errors(document), Nil)
    assert(document.symbols.exists(_.symbol == "example/Generated.apply()."))
    assert(document.symbols.exists(_.symbol == "example/Model.apply()."))
    assert(document.synthetics.exists(_.range.exists(_.startLine == 5)))
    assertEquals(
      URLDecoder.decode(document.uri, UTF_8.name()),
      model.toUri.toString
    )
    assertEquals(document.text, declarations)
    assertEquals(new String(Files.readAllBytes(model), UTF_8), declarations)
  }

  test("editor-removal-does-not-reload-declarations-from-disk") {
    val (model, calls) = prepare()
    assertEquals(errors(query(calls, caller)), Nil)
    val edited =
      "package example; object BufferUse { def make = Generated(\"sample\") }"
    presentationCompiler
      .didChange(
        CompilerVirtualFileParams(
          model.toUri,
          edited,
          shouldReturnDiagnostics = true
        )
      )
      .get(30, TimeUnit.SECONDS)
    val document = query(model, edited)
    assert(
      errors(document).exists(_.message.contains("not found: value Generated"))
    )
    assert(!document.symbols.exists(_.symbol == "example/Generated.apply()."))
    assertEquals(new String(Files.readAllBytes(model), UTF_8), declarations)
  }

  test("source-path-promotion-checks-unsaved-buffer-and-restoration") {
    val (model, calls) = prepare()
    assertEquals(errors(query(calls, caller)), Nil)
    val edited = declarations.replace(
      "def duplicate: Model = Model(value)",
      "def duplicate: Int = value"
    )
    val document = query(model, edited)
    assert(errors(document).nonEmpty)
    assertEquals(document.text, edited)
    assertEquals(
      URLDecoder.decode(document.uri, UTF_8.name()),
      model.toUri.toString
    )
    val restored = query(model, declarations)
    assertEquals(errors(restored), Nil)
    assert(restored.symbols.exists(_.symbol == "example/Generated.apply()."))
    assertEquals(new String(Files.readAllBytes(model), UTF_8), declarations)
  }
}

class SourcePathIdentityFullSuite extends SourcePathIdentitySuite {
  override protected def sourcePathMode: SourcePathMode = SourcePathMode.FULL
}
