package scala.meta.internal.metals

import scala.concurrent.{Future, ExecutionContext}
import scala.meta._
import scala.meta.pc.CancelToken
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags._
import scala.meta.internal.semanticdb.TextDocument
import org.eclipse.{lsp4j => l}

trait Refactoring {
  def contribute(
      params: l.CodeActionParams,
      trees: Trees,
      buffers: Buffers,
      semanticdbs: Semanticdbs,
      symbolSearch: MetalsSymbolSearch,
      definitionProvider: DefinitionProvider,
      token: CancelToken
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]]
}

object Refactoring {

  object UseNamedArguments extends Refactoring {

    val title = "Use named arguments"

    override def contribute(
        params: l.CodeActionParams,
        trees: Trees,
        buffers: Buffers,
        semanticdbs: Semanticdbs,
        symbolSearch: MetalsSymbolSearch,
        definitionProvider: DefinitionProvider,
        token: CancelToken
    )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = {
      scribe.info("Running contribute for UseNamedArguments")

      def findMethodApplyOrCtorTreeUnderCursor(
          root: Tree,
          range: Position
      ): Option[Tree] =
        root
          .collect {
            case t @ Term.Apply(_, _)
                if t.pos.start <= range.start && t.pos.end >= range.end =>
              t
            case t @ Init(_, _, _)
                if t.pos.start <= range.start && t.pos.end >= range.end =>
              t
          }
          .sortBy(_.pos.start)
          .lastOption

      def findSymbolTree(tree: Tree): Name = tree match {
        case x @ Term.Name(_) => x
        case x @ Type.Name(_) => x
        case Term.Select(_, x) => x
        case Term.Apply(x, _) => findSymbolTree(x)
        case Init(x, _, _) => findSymbolTree(x)
      }

      def resolveSymbol(
          textDocumentId: l.TextDocumentIdentifier,
          path: AbsolutePath,
          textDocument: TextDocument,
          symbolTreePos: Position
      ): ResolvedSymbolOccurrence = {
        val tdpp = new l.TextDocumentPositionParams(
          textDocumentId,
          symbolTreePos.toLSP.getStart
        )
        definitionProvider.positionOccurrence(path, tdpp, textDocument)
      }

      def buildEdits(
          tree: Tree,
          paramNames: List[String],
          editDistance: TokenEditDistance
      ): List[l.TextEdit] = {
        // TODO write tests to confirm whether we need to offset by edit distance or not
        val args = tree match {
          case Term.Apply(_, xs) => xs
          case Init(_, _, xss) => xss.flatten
          case _ => Nil
        }
        args.zip(paramNames).flatMap {
          case (Term.Assign(_, _), _) =>
            // already a named argument, no edit needed
            None
          case (term, paramName) =>
            val position =
              new l.Position(term.pos.startLine, term.pos.startColumn)
            val text = s"$paramName = "
            val edit = new l.TextEdit(new l.Range(position, position), text)
            Some(edit)
        }
      }

      val path = params.getTextDocument().getUri().toAbsolutePath

      Future {
        (for {
          bufferContent <- buffers.get(path)
          rootTree <- trees.get(path)
          metaRange = params
            .getRange()
            .toMeta(Input.VirtualFile(path.toString, bufferContent))
          methodApplyTree <- findMethodApplyOrCtorTreeUnderCursor(
            rootTree,
            metaRange
          )
          _ = scribe.info(s"Tree under cursor: ${methodApplyTree.structure}")
          textDocument <- semanticdbs.textDocument(path).documentIncludingStale
          symbolTree = findSymbolTree(methodApplyTree)
          _ = scribe.info(
            s"Symbol tree: $symbolTree, position: ${symbolTree.pos}"
          )
          resolvedSymbol = resolveSymbol(
            params.getTextDocument,
            path,
            textDocument,
            symbolTree.pos
          )
          symbolOccurrence <- resolvedSymbol.occurrence
          _ = scribe.info(s"Symbol occurrence: $symbolOccurrence")
          symbolDocumentation <- symbolSearch
            .documentation(symbolOccurrence.symbol)
            .asScala
          _ = scribe.info(s"Symbol documentation: $symbolDocumentation")
        } yield {
          val parameterNames =
            symbolDocumentation.parameters().asScala.map(_.displayName()).toList
          scribe.info(s"Parameter names: $parameterNames")

          val edit = new l.WorkspaceEdit()
          val uri = params.getTextDocument().getUri()
          val changes =
            Map(
              uri -> buildEdits(
                methodApplyTree,
                parameterNames,
                resolvedSymbol.distance
              ).asJava
            )

          val codeAction = new l.CodeAction()
          codeAction.setTitle(title)
          codeAction.setKind(l.CodeActionKind.Refactor)

          edit.setChanges(changes.asJava)
          codeAction.setEdit(edit)
          codeAction
        }).toSeq
      }

    }

  }

}
