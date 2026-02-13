package scala.meta.internal.metals

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.codeactions.MissingSymbolDiagnostic
import scala.meta.internal.parsing.Trees
import scala.meta.io.AbsolutePath
import scala.meta.pc.AutoImportsResult
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
  ): Future[List[TextEdit]] = {
    val path = params.textDocument.getUri.toAbsolutePath
    val orginalPath = params.originDocument.getUri().toAbsolutePath
    val isScala3 =
      buildTargets.scalaVersion(path).exists(ScalaVersions.isScala3Version)

    val MissingSymbol = new MissingSymbolDiagnostic(isScala3, path)
    compilers
      .didChangeWithDiagnostics(
        path,
        content = Some(params.text),
      )
      .flatMap { diagnostics =>
        val imports = diagnostics.collect {
          case d @ MissingSymbol(name, _)
              if params.range.overlapsWith(d.getRange()) =>
            scribe.debug(s"Missing diagnostic: ${d}")
            val offset =
              if (isScala3) d.getRange().getEnd()
              else d.getRange().getStart()

            val symbolsPositionParams =
              new TextDocumentPositionParams(
                params.originDocument,
                adjustPositionToOrigin(params, offset),
              )

            val destinationParams =
              new TextDocumentPositionParams(
                params.textDocument,
                d.getRange().getStart(),
              )

            for {
              defnResult <- definitions.definition(
                orginalPath,
                symbolsPositionParams,
                cancelToken,
              )
              defnName = findNameFromSemanticdbSymbol(defnResult.symbol)
              autoImports <- compilers.autoImports(
                destinationParams,
                defnName,
                findExtensionMethods = true,
                cancelToken,
              )
            } yield {
              if (!defnResult.isEmpty) {
                importEdit(
                  name,
                  defnName,
                  autoImports,
                  defnResult,
                  isScala3,
                ).flatten
                // packages are not handled in auto imports, so we need to handle them here
              } else if (defnResult.symbol.endsWith("/") && name != defnName) {
                // this means the package was renamed
                val fullPath = defnResult.symbol.split("/")
                val edits = for {
                  toRename <- fullPath.lastOption
                  if fullPath.length > 1
                  owner = fullPath.dropRight(1).mkString(".")
                  importString = s"import $owner.{$toRename => $name}"
                  edit <- createImportEdit(path, importString)
                } yield edit
                edits.toList
              } else {
                Nil
              }

            }
        }
        Future
          .sequence(imports)
          .map { edits =>
            val flattened = edits.flatten.distinct

            // Fix import positions that are placed before existing imports
            // Only adjust edits at position 0 (start of line) to avoid grouping edits that should be separate
            val fileLines = params.text.linesIterator.toArray
            val adjustedEdits = flattened.map { edit =>
              val pos = edit.getRange().getStart()
              val line = pos.getLine()
              val char = pos.getCharacter()

              // Only adjust if at start of line (char 0) and that line contains an import
              if (
                char == 0 && line < fileLines.length && fileLines(line).trim
                  .startsWith("import ")
              ) {
                // Find the last consecutive import line starting from this position
                var lastImportLine = line
                while (
                  lastImportLine + 1 < fileLines.length &&
                  fileLines(lastImportLine + 1).trim.startsWith("import ")
                ) {
                  lastImportLine += 1
                }

                // Place our edit after the last import line, stripping leading newline
                val newPos = new lsp4j.Position(lastImportLine + 1, 0)
                val adjustedText = edit.getNewText().stripPrefix("\n")
                new TextEdit(new lsp4j.Range(newPos, newPos), adjustedText)
              } else {
                edit
              }
            }

            // Group by adjusted position and merge
            adjustedEdits
              .groupBy(edit =>
                (edit.getRange().getStart(), edit.getRange().getEnd())
              )
              .map { case (_, editsWithSameRange) =>
                if (editsWithSameRange.size > 1) {
                  val merged = new TextEdit(
                    editsWithSameRange.head.getRange(),
                    editsWithSameRange
                      .map(_.getNewText())
                      .sorted
                      .mkString
                      .replace("\n\n", "\n"),
                  )
                  merged
                } else {
                  editsWithSameRange.head
                }
              }
              .toList
          }
      }
  }

  private def importEdit(
      name: String,
      defnName: String,
      autoImports: java.util.List[AutoImportsResult],
      defnResult: DefinitionResult,
      isScala3: Boolean,
  ) = {
    def isSameSymbolOrCompanion(
        importedSymbol: String,
        defnSymbol: String,
    ): Boolean = {
      val imported = stripSymbolSuffix(importedSymbol)
      val defn = stripSymbolSuffix(defnSymbol)
      imported == defn
    }
    for {
      autoImport <- autoImports.asScala.collectFirst {
        case autoImport: AutoImportsResult
            if autoImport
              .symbol()
              .asScala
              .exists(
                isSameSymbolOrCompanion(_, defnResult.symbol)
              ) =>
          autoImport
      }.toList
    } yield {
      if (name != defnName) {
        renameImportEdit(name, defnName, autoImport, isScala3)
      } else
        autoImport.edits().asScala.toList
    }
  }

  private def renameImport(name: String, rename: String, isScala3: Boolean) = {
    if (isScala3) s"$name as $rename"
    else s"{$name => $rename}"
  }

  private def renameImportEdit(
      name: String,
      defnName: String,
      autoImport: AutoImportsResult,
      isScala3: Boolean,
  ) = {
    autoImport
      .edits()
      .asScala
      .collectFirst {
        case edit
            if edit.getNewText().startsWith("import ") && edit
              .getNewText()
              .endsWith(defnName + "\n") =>
          new TextEdit(
            edit.getRange(),
            edit
              .getNewText()
              .replace(
                defnName + "\n",
                renameImport(defnName, name, isScala3) + "\n",
              ),
          )
      }
      .toList
  }

  private val replaceApplyRegex = raw"\.apply\(\+?\d*\)".r
  private val replaceSuffixRegex = raw"\(\+?\d*\)".r

  /**
   * Try to avoid symbol.desc, since that causes additional parsing
   */
  private def findNameFromSemanticdbSymbol(symbol: String): String = {
    val stripped = stripSymbolSuffix(symbol)
    // method
    if (stripped.endsWith(")")) {
      replaceSuffixRegex.replaceAllIn(stripped.split("\\.").last, "")
    } else {
      // everything else
      stripped.split("/|\\.").last
    }
  }

  /**
   *  Strip useless suffix that can't be imported
   */
  private def stripSymbolSuffix(symbol: String): String = {
    replaceApplyRegex.replaceAllIn(
      symbol
        .stripSuffix("#")
        .stripSuffix("."),
      "",
    )

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

  private def createImportEdit(
      path: AbsolutePath,
      importText: String,
  ): Option[TextEdit] = {

    def newTextEdit(pos: lsp4j.Position, text: String): TextEdit = {
      new TextEdit(new lsp4j.Range(pos, pos), text)
    }

    def loop(tree: Tree): Option[TextEdit] = {
      tree match {
        case pkg: Pkg =>
          loop(pkg.body).orElse(
            Some(newTextEdit(pkg.name.pos.toLsp.getEnd(), "\n\n" + importText))
          )
        case pkg: Pkg.Body if pkg.stats.exists(!_.is[Pkg]) =>
          pkg.stats.findLast(_.is[Import]) match {
            case Some(lastImport) =>
              Some(
                newTextEdit(lastImport.pos.toLsp.getEnd(), "\n" + importText)
              )
            case None =>
              pkg.stats.headOption
                .map(firstStat =>
                  newTextEdit(
                    firstStat.pos.toLsp.getStart(),
                    importText + "\n\n",
                  )
                )
          }

        case pkg: Pkg.Body =>
          pkg.stats.iterator.map(loop).collectFirst { case Some(pos) =>
            pos
          }
        case source: Source =>
          source.stats.iterator
            .map(loop)
            .collectFirst { case Some(pos) =>
              pos
            }
            .orElse(
              Some(newTextEdit(source.pos.toLsp.getStart(), "\n" + importText))
            )
        case _ =>
          None
      }
    }
    trees.get(path) match {
      case Some(source) =>
        loop(source)
      case _ => None
    }

  }
}
