package scala.meta.internal.metals.codeactions

import scala.concurrent.{Future, ExecutionContext}
import scala.meta._
import scala.meta.pc.CancelToken
import scala.meta.internal.metals._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags._
import scala.meta.internal.semanticdb.TextDocument
import org.eclipse.{lsp4j => l}

class UseNamedArguments(
    trees: Trees,
    semanticdbs: Semanticdbs,
    symbolSearch: MetalsSymbolSearch,
    definitionProvider: DefinitionProvider
) extends CodeAction {

  override def kind: String = l.CodeActionKind.Refactor

  override def contribute(
      params: l.CodeActionParams,
      token: CancelToken
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = {

    def findMethodApplyOrCtorTreeUnderCursor(
        root: Tree,
        cursorPos: l.Range
    ): Option[Tree] =
      root
        .collect {
          case t @ Term.Apply(_, _) if t.pos.toLSP.encloses(cursorPos) => t
          case t @ Init(_, _, _) if t.pos.toLSP.encloses(cursorPos) => t
        }
        .sortBy(_.pos.start)
        .lastOption

    def findSymbolTree(tree: Tree): Option[Name] = tree match {
      case x @ Term.Name(_) => Some(x)
      case x @ Type.Name(_) => Some(x)
      case Term.Select(_, x) => Some(x)
      case Term.Apply(x, _) => findSymbolTree(x)
      case Term.ApplyType(x, _) => findSymbolTree(x)
      case Type.Apply(x, _) => findSymbolTree(x)
      case Init(x, _, _) => findSymbolTree(x)
      case _ => None
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

    val methodSymbolPattern = """.*\((\+\d+)?\)\.$"""

    def tweakSymbol(symbol: String): String = {
      if (symbol.endsWith(".") && !symbol.matches(methodSymbolPattern)) {
        /*
         * We've probably been given a companion object symbol
         * e.g. in the case (@@ = cursor)
         *
         * case class Foo(a: Int, b: Int)
         * val x = Fo@@o(1, 2)
         *
         * the definition provider gives us the symbol of the companion
         * object. This is no good to us, so we try to replace it with
         * the case class's symbol.
         * In other words we turn "example/Foo." into "example/Foo#"
         */
        symbol.stripSuffix(".") ++ "#"
      } else {
        symbol
      }
    }

    def buildEdits(
        tree: Tree,
        paramNames: List[String]
    ): List[l.TextEdit] = {
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
          val position = term.pos.toLSP.getStart
          val text = s"$paramName = "
          val edit = new l.TextEdit(new l.Range(position, position), text)
          Some(edit)
      }
    }

    val path = params.getTextDocument().getUri().toAbsolutePath

    Future {
      (for {
        rootTree <- trees.get(path)
        methodApplyTree <- findMethodApplyOrCtorTreeUnderCursor(
          rootTree,
          params.getRange
        )
        textDocument <- semanticdbs.textDocument(path).documentIncludingStale
        symbolTree <- findSymbolTree(methodApplyTree)
        resolvedSymbol = resolveSymbol(
          params.getTextDocument,
          path,
          textDocument,
          symbolTree.pos
        )
        symbolOccurrence <- resolvedSymbol.occurrence
        symbol = tweakSymbol(symbolOccurrence.symbol)
        symbolDocumentation <- symbolSearch
          .documentation(symbol)
          .asScala
        parameterNames = symbolDocumentation
          .parameters()
          .asScala
          .map(_.displayName())
          .toList
      } yield {
        val codeEdits = buildEdits(
          methodApplyTree,
          parameterNames
        )

        codeEdits match {
          case Nil => None // refactoring results in no changes to the code
          case edits =>
            val edit = new l.WorkspaceEdit()
            val uri = params.getTextDocument().getUri()
            val changes = Map(uri -> edits.asJava)

            val codeAction = new l.CodeAction()
            codeAction.setTitle(UseNamedArguments.title)
            codeAction.setKind(l.CodeActionKind.Refactor)

            edit.setChanges(changes.asJava)
            codeAction.setEdit(edit)
            Some(codeAction)
        }
      }).flatten.toSeq
    }

  }

}

object UseNamedArguments {

  val title = "Use named arguments"

}
