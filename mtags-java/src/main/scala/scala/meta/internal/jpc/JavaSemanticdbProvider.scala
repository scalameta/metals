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
    textDocument(params, None).toByteArray()
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
    val sourceroot = compiler.metalsConfig.workspaceRoot()
    val cu = targetRange match {
      case Some(range) => range.cu
      case None => compile.cu
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
    visitor.uri = compile.cu.getSourceFile().toUri().toString()
    val textDocument = visitor.buildTextDocumentBuilder(cu);
    compile.listener.diagnostics.foreach { d =>
      val line = (d.getLineNumber() - 1).toInt
      val column = (d.getColumnNumber() - 1).toInt
      val range = Semanticdb.Range
        .newBuilder()
        .setStartLine(line)
        .setStartCharacter(column)
        .setEndLine(line.toInt)
        .setEndCharacter(column)
        .build()
      textDocument.addDiagnostics(
        Semanticdb.Diagnostic
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
          .setRange(range)
      )
    }
    textDocument.build()
  }

}
