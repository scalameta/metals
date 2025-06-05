package scala.meta.internal.metals

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.codeactions.MissingSymbolDiagnostic
import scala.meta.internal.semanticdb.Scala.Descriptor.Method
import scala.meta.internal.semanticdb.Scala._
import scala.meta.pc.CancelToken

import org.eclipse.lsp4j
import org.eclipse.lsp4j.TextDocumentPositionParams
import org.eclipse.lsp4j.TextEdit

class MetalsPasteProvider(
    compilers: Compilers,
    buildTargets: BuildTargets,
)(implicit ec: ExecutionContext) {

  def didPaste(
      params: MetalsPasteParams,
      cancelToken: CancelToken,
  ): Future[List[TextEdit]] = {
    val path = params.textDocument.getUri.toAbsolutePath
    val isScala3 =
      buildTargets.scalaVersion(path).exists(ScalaVersions.isScala3Version)
    val MissingSymbol = new MissingSymbolDiagnostic(isScala3)

    compilers.diagnostics(path, cancelToken).flatMap { diagnostics =>
      val edits = diagnostics.collect {
        case d @ MissingSymbol(name, findExtensionMethods)
            if params.range.overlapsWith(d.getRange()) =>

          val offset =
            if (isScala3) d.getRange().getEnd()
            else d.getRange().getStart()

          val importPositionParams =
            new TextDocumentPositionParams(params.textDocument, offset)
          val symbolsPositionParams =
            new TextDocumentPositionParams(
              params.originDocument,
              adjustPositionToOrigin(params, offset),
            )
          for {
            imports <- compilers.autoImports(
              importPositionParams,
              name,
              findExtensionMethods,
              cancelToken,
            )
            symbols <- compilers.symbolsAt(symbolsPositionParams, cancelToken)
          } yield {
            val allSymbols = symbols
              .flatMap(sym =>
                sym.desc match {
                  case Method(value, disambiguator)
                      if value == "apply" || value == "unapply" || value == "<init>" =>
                    List(disambiguator.owner.fqcn, disambiguator.fqcn)
                  case _ => List(sym.owner.fqcn)
                }
              )
              .toSet
            imports.asScala
              .find(autoImport => allSymbols.contains(autoImport.packageName()))
              .map(_.edits().asScala)
          }
      }
      Future
        .sequence(edits)
        .map(edits =>
          joinEdits(edits.flatten.flatten.distinctBy(_.getNewText())).toList
        )
    }
  }

  private def joinEdits(edits: List[TextEdit]) = {
    edits
      .groupBy(_.getRange())
      .values
      .map(_.sortBy(_.getNewText()).reduceLeft { (l, r) =>
        l.setNewText((l.getNewText() + r.getNewText()).replace("\n\n", "\n"))
        l
      })
      .toSeq
  }

  private def adjustPositionToOrigin(
      params: MetalsPasteParams,
      pos: lsp4j.Position,
  ): lsp4j.Position = {
    val lineDiff =
      params.originOffset.getLine() - params.range.getStart().getLine()
    if (pos.getLine() == params.range.getStart().getLine()) {
      val charDiff = params.originOffset
        .getCharacter() - params.range.getStart().getCharacter()
      new lsp4j.Position(
        pos.getLine() + lineDiff,
        pos.getCharacter() + charDiff,
      )
    } else {
      new lsp4j.Position(pos.getLine() + lineDiff, pos.getCharacter())
    }
  }

}
