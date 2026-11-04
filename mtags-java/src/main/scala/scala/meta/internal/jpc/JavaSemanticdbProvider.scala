package scala.meta.internal.jpc

import javax.tools.Diagnostic.Kind.ERROR
import javax.tools.Diagnostic.Kind.MANDATORY_WARNING
import javax.tools.Diagnostic.Kind.NOTE
import javax.tools.Diagnostic.Kind.WARNING

import scala.meta.internal.jsemanticdb.Semanticdb
import scala.meta.internal.semanticdb.javac.GlobalSymbolsCache
import scala.meta.internal.semanticdb.javac.SemanticdbJavacOptions
import scala.meta.internal.semanticdb.javac.SemanticdbVisitor
import scala.meta.pc.VirtualFileParams

import com.sun.source.tree.CompilationUnitTree

case class TargetRange(
    cu: CompilationUnitTree,
    startOffset: Long,
    endOffset: Long
)
class JavaSemanticdbProvider(
    compiler: JavaMetalsCompiler
) {

  def textDocumentBytes(params: VirtualFileParams): Array[Byte] = {
    val result = textDocument(params, None).toByteArray()
    result
  }
  def batchTextDocumentBytes(
      params: List[VirtualFileParams]
  ): Array[Byte] = {
    val compile = compiler
      .batchCompilationTask(params)
      .withAnalyzePhase()
    val docs = Semanticdb.TextDocuments.newBuilder()
    val isRequestedURI = params.iterator
      .map(param =>
        SourceJavaFileObject.makeOriginalURI(param.uri().toString())
      )
      .toSet
    for {
      cu <- compile.allCompilationUnits
      if isRequestedURI(cu.getSourceFile().getName())
    } {
      docs.addDocuments(textDocumentFromSource(compile, cu, None))
    }
    docs.build().toByteArray()
  }

  def textDocument(
      params: VirtualFileParams,
      targetRange: Option[TargetRange]
  ): Semanticdb.TextDocument = {
    val compile = compiler
      .compilationTask(params)
      .withAnalyzePhase()
    textDocumentFromSource(compile, targetRange)
  }

  def textDocumentFromSource(
      compile: JavaSourceCompile,
      targetRange: Option[TargetRange]
  ): Semanticdb.TextDocument = {
    textDocumentFromSource(compile, compile.cu, targetRange)
  }

  def textDocumentFromSource(
      compile: JavaSourceCompile,
      cuParam: CompilationUnitTree,
      targetRange: Option[TargetRange]
  ): Semanticdb.TextDocument = {
    val sourceroot = compiler.metalsConfig.workspaceRoot()
    val cu = targetRange match {
      case Some(range) => range.cu
      case None => cuParam
    }
    val options = new SemanticdbJavacOptions()
    options.sourceroot = sourceroot
    options.includeText = true
    val globals = new GlobalSymbolsCache(options)
    val visitor = new SemanticdbVisitor(compile.task, globals, options, cu)
    targetRange.foreach { range =>
      visitor.skipTreesOutsideTargetRange(range.startOffset, range.endOffset)
    }
    // HACK: we set the URI manually here so it matches exactly the requested URI.
    // It's a complicated story how URIs get interpreted in our LSP setup and the
    // virtual file system we have for Java.
    visitor.uri = compiler.guessOriginalUri(cu.getSourceFile())
    val textDocument = visitor.buildTextDocumentBuilder(cu);
    compile.listener.diagnostics.foreach { d =>
      val source = d.getSource()
      if (source == null || source.getName() == cu.getSourceFile().getName()) {
        // Source-less diagnostics describe the compilation as a whole. Keep
        // them visible, but do not attach another source's diagnostic here.
        val diagnostic = Semanticdb.Diagnostic
          .newBuilder()
          .setSeverity(d.getKind() match {
            case ERROR =>
              Semanticdb.Diagnostic.Severity.ERROR
            case WARNING | MANDATORY_WARNING =>
              Semanticdb.Diagnostic.Severity.WARNING
            case NOTE =>
              Semanticdb.Diagnostic.Severity.INFORMATION
            case _ =>
              Semanticdb.Diagnostic.Severity.HINT
          })
          .setCode(d.getCode())
          .setMessage(d.getMessage(null))
        // javac also reports diagnostics without positions. SemanticDB's range
        // is optional; NOPOS must not become a negative document coordinate.
        if (
          source != null && d.getLineNumber() > 0 && d.getColumnNumber() > 0
        ) {
          val line = (d.getLineNumber() - 1).toInt
          val column = (d.getColumnNumber() - 1).toInt
          diagnostic.setRange(
            Semanticdb.Range
              .newBuilder()
              .setStartLine(line)
              .setStartCharacter(column)
              .setEndLine(line)
              .setEndCharacter(column)
          )
        }
        textDocument.addDiagnostics(diagnostic)
      }
    }
    textDocument.build()
  }

}
