package scala.meta.internal.metals

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.Import
import scala.meta.Pkg
import scala.meta.Source
import scala.meta.Stat
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.codeactions.MissingSymbolDiagnostic
import scala.meta.internal.parsing.Trees
import scala.meta.internal.semanticdb.Scala._
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken

import org.eclipse.lsp4j
import org.eclipse.lsp4j.TextDocumentPositionParams
import org.eclipse.lsp4j.TextEdit

class MetalsPasteProvider(
    compilers: Compilers,
    buildTargets: BuildTargets,
    definitions: DefinitionProvider,
    trees: Trees,
)(implicit ec: ExecutionContext) {

  def didPaste(
      params: MetalsPasteParams,
      cancelToken: CancelToken,
  ): Future[Option[TextEdit]] = {
    val path = params.textDocument.getUri.toAbsolutePath
    val orginalPath = params.originDocument.getUri().toAbsolutePath
    val isScala3 =
      buildTargets.scalaVersion(path).exists(ScalaVersions.isScala3Version)
    val MissingSymbol = new MissingSymbolDiagnostic(isScala3)

    compilers.diagnostics(path, cancelToken).flatMap { diagnostics =>
      val imports = diagnostics.collect {
        case d @ MissingSymbol(name, _)
            if params.range.overlapsWith(d.getRange()) =>

          val offset =
            if (isScala3) d.getRange().getEnd()
            else d.getRange().getStart()

          val symbolsPositionParams =
            new TextDocumentPositionParams(
              params.originDocument,
              adjustPositionToOrigin(params, offset),
            )
          for {
            defnResult <- definitions.definition(
              orginalPath,
              symbolsPositionParams,
              cancelToken,
            )
          } yield {
            if (defnResult.isEmpty) None
            else {
              val symbolDesc = defnResult.symbol.desc
              val symbolName = symbolDesc.name.value
              lazy val owner = defnResult.symbol.owner
              lazy val ownerName = owner.desc.name.value
              pprint.log(defnResult.symbol)
              val importText =
                if (name != symbolName) {
                  if (
                    symbolDesc.isMethod &&
                    (symbolName == "apply" || symbolName == "unapply" || symbolName == "<init>")
                  )
                    if (ownerName == name) s"import ${owner.fqcn}"
                    else s"import ${owner.owner.fqcn}.{${ownerName} => $name}"
                  else s"import ${owner.fqcn}.{$symbolName => $name}"
                } else s"import ${defnResult.symbol.fqcn}"

              Some(importText)
            }
          }
      }
      Future
        .sequence(imports)
        .map(_.flatten.distinct)
        .map { imports =>
          Option.when(imports.nonEmpty) {
            val (prefix, suffix, pos) =
              autoImportPosition(path, params.range.getStart())
            new lsp4j.TextEdit(
              new lsp4j.Range(pos, pos),
              imports.mkString(prefix, "\n", suffix),
            )
          }
        }
    }
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

  private def autoImportPosition(
      path: AbsolutePath,
      pos: lsp4j.Position,
  ): (String, String, lsp4j.Position) = {
    lazy val fallback = ("", "\n\n", new lsp4j.Position(0, 0))
    def afterImportsPositon(stats: List[Stat]) =
      stats
        .takeWhile {
          case _: Import => true
          case _ => false
        }
        .lastOption
        .map { imp =>
          ("\n", "\n", imp.pos.toLsp.getEnd())
        }
    trees.findLastEnclosingAt[Pkg](path, pos, _ => true) match {
      case Some(pkg @ Pkg(_, stats)) =>
        afterImportsPositon(stats).getOrElse(
          (
            "",
            "\n\n",
            stats.headOption
              .map(_.pos.toLsp.getStart())
              .getOrElse(pkg.pos.toLsp.getEnd()),
          )
        )
      case _ =>
        trees.get(path) match {
          case Some(Source(stats)) =>
            afterImportsPositon(stats).getOrElse(fallback)
          case _ => fallback
        }
    }
  }

}
