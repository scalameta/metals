package scala.meta.internal.jpc

import java.{util => ju}

import scala.jdk.CollectionConverters._

import scala.meta.internal.jsemanticdb.Semanticdb
import scala.meta.internal.mtags.SemanticdbRanges._
import scala.meta.internal.mtags.SemanticdbSymtab
import scala.meta.internal.pc.SemanticTokens
import scala.meta.internal.pc.TokenNode
import scala.meta.internal.semanticdb.javac.GlobalSymbolsCache
import scala.meta.internal.semanticdb.javac.SemanticdbJavacOptions
import scala.meta.internal.semanticdb.javac.SemanticdbVisitor
import scala.meta.pc.Node
import scala.meta.pc.VirtualFileParams

import org.eclipse.lsp4j.SemanticTokenTypes

class JavaSemanticTokensProvider(
    compiler: JavaMetalsCompiler,
    params: VirtualFileParams
) {

  def semanticTokens(): ju.List[Node] = {
    val compile = compiler
      .compilationTask(params)
      .withAnalyzePhase()
    val cu = compile.cu
    val options = new SemanticdbJavacOptions()
    val globals = new GlobalSymbolsCache(options)
    val visitor = new SemanticdbVisitor(compile.task, globals, options, cu)
    val doc = visitor.buildTextDocument(cu)
    var lastOcc: Semanticdb.SymbolOccurrence = null
    val symtab = SemanticdbSymtab.fromDoc(doc)
    val occurrences = doc.getOccurrencesList().asScala.sortBy(_.getRange())
    val result = for {
      occ <- occurrences.iterator
      tpe = tokenType(occ, symtab)
      if tpe != ""
      if lastOcc == null || !lastOcc.getRange().overlapsRange(occ.getRange())
    } yield {
      lastOcc = occ
      val startLineOffset = params.lineToOffset(occ.getRange().getStartLine())
      val startOffset = startLineOffset + occ.getRange().getStartCharacter()
      val endOffset = startLineOffset + occ.getRange().getEndCharacter()

      TokenNode(
        startOffset,
        endOffset,
        SemanticTokens.getTypeId(tpe),
        0
      ): Node
    }
    result.toBuffer.asJava
  }

  private def tokenType(
      occ: Semanticdb.SymbolOccurrence,
      symtab: SemanticdbSymtab
  ): String = {
    val sym = occ.getSymbol()
    if (sym.endsWith(").")) {
      SemanticTokenTypes.Method
    } else if (sym.endsWith(")")) {
      // Never used by java since Java parameters are locals
      SemanticTokenTypes.Parameter
    } else if (sym.endsWith(".")) {
      return SemanticTokenTypes.Property
    } else if (sym.startsWith("local")) {
      symtab.info(sym).getKind() match {
        // Anonymous classes are local symbols but we may want to highlight them
        // with the appropriate token type.
        case Semanticdb.SymbolInformation.Kind.METHOD =>
          SemanticTokenTypes.Method
        case Semanticdb.SymbolInformation.Kind.FIELD =>
          SemanticTokenTypes.Property
        case _ =>
          // HACK: this is white by default just like for namespace. It's
          // technically incorrect but it gives better coloring with the default
          // themes.
          SemanticTokenTypes.Parameter
      }
    } else if (sym.endsWith("#")) {
      SemanticTokenTypes.Class
    } else if (sym.endsWith("/")) {
      SemanticTokenTypes.Namespace
    } else {
      // TODO: do something
      ""
    }
  }
}
