package scala.meta.internal.metals

import scala.concurrent.{Future, ExecutionContext}
import scala.meta._
import scala.meta.pc.CancelToken
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags._
import scala.meta.internal.semanticdb.{SymbolOccurrence, TextDocument}
import org.eclipse.{lsp4j => l}

trait Refactoring {
  def contribute(
      params: l.CodeActionParams,
      trees: Trees,
      buffers: Buffers,
      semanticdbs: Semanticdbs,
      symbolSearch: MetalsSymbolSearch,
      token: CancelToken
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]]
}

object Refactoring {

  object UseNamedArguments extends Refactoring {

    override def contribute(
        params: l.CodeActionParams,
        trees: Trees,
        buffers: Buffers,
        semanticdbs: Semanticdbs,
        symbolSearch: MetalsSymbolSearch,
        token: CancelToken
    )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = {
      scribe.info("Running contribute for UseNamedArguments")

      def findMethodApplyTreeUnderCursor(
          root: Tree,
          range: Position
      ): Option[Term.Apply] = {
        root.collect {
          case t @ Term.Apply(_, _)
              if t.pos.start <= range.start && t.pos.end >= range.end =>
            t
        }.headOption
        // TODO make sure we pick the correct tree in case of nested applications e.g. `f(g(x), y)`
      }

      def findSymbolTree(tree: Tree): Term.Name = tree match {
        case x @ Term.Name(_) => x
        case Term.Select(t, x) => x
        case Term.Apply(x, _) => findSymbolTree(x)
      }

      def findSymbolOccurrence(
          textDocument: TextDocument,
          symbolTreePos: Position
      ): Option[SymbolOccurrence] =
        textDocument.occurrences.find(
          so =>
            so.getRange.startLine == symbolTreePos.startLine &&
              so.getRange.startCharacter == symbolTreePos.startColumn
        )

      def buildEdits(
          methodApplyTree: Term.Apply,
          paramNames: List[String]
      ): List[l.TextEdit] = {
        methodApplyTree.args.zip(paramNames).flatMap {
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
          methodApplyTree <- findMethodApplyTreeUnderCursor(rootTree, metaRange)
          _ = scribe.info(s"Tree under cursor: ${methodApplyTree.structure}")
          textDocument <- semanticdbs.textDocument(path).documentIncludingStale
          symbolTree = findSymbolTree(methodApplyTree)
          _ = scribe.info(
            s"Symbol tree: $symbolTree, position: ${symbolTree.pos}"
          )
          symbolOccurrence <- findSymbolOccurrence(textDocument, symbolTree.pos)
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
            Map(uri -> buildEdits(methodApplyTree, parameterNames).asJava)

          val codeAction = new l.CodeAction()
          codeAction.setTitle("Use named arguments")
          codeAction.setKind(l.CodeActionKind.Refactor)

          edit.setChanges(changes.asJava)
          codeAction.setEdit(edit)
          codeAction
        }).toSeq
      }

    }

  }

}
