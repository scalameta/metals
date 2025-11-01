package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta._
import scala.meta.internal.metals.Compilers
import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.codeactions.CodeAction
import scala.meta.internal.metals.codeactions.CodeActionBuilder
import scala.meta.internal.parsing.Trees
import scala.meta.pc.CancelToken

import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.{lsp4j => l}

class FilterMapToCollectCodeAction(trees: Trees, compilers: Compilers)
    extends CodeAction {
  override def kind: String = l.CodeActionKind.RefactorRewrite

  private case class FilterMapCollectParams(
      param: l.TextDocumentIdentifier,
      pos: l.Position,
  ) extends CodeActionResolveData

  override def resolveCodeAction(codeAction: l.CodeAction, token: CancelToken)(
      implicit ec: ExecutionContext
  ): Option[Future[l.CodeAction]] = {
    val result = for {
      data <- parseData[FilterMapCollectParams](codeAction)
      params = data.param
      uri = params.getUri()
      path = uri.toAbsolutePath
      filterMap <- trees
        .findLastEnclosingAt[Term.Apply](path, data.pos)
        .flatMap(findFilterMapChain)
      mapPos = filterMap.mapPos.toLsp.getStart()
    } yield {
      // Create position params for the map method call
      val positionParams = new l.TextDocumentPositionParams(params, mapPos)

      // Get the definition of the map method and verify collect method exists
      compilers.definition(positionParams, token).flatMap { defResult =>
        if (
          defResult.symbol.nonEmpty && !defResult.symbol.startsWith("local")
        ) {
          val newSymbol = defResult.symbol.replaceAll("map\\(", "collect(")

          // Try each potential collect symbol
          compilers
            .info(path, newSymbol)
            .map { result =>
              if (result.isDefined) {
                // At least one collect method exists, proceed with the code action
                val edit = toTextEdit(filterMap)
                val workspaceEdit = new l.WorkspaceEdit(
                  Map(uri -> List(edit).asJava).asJava
                )
                codeAction.setEdit(workspaceEdit)
                Some(codeAction)
              } else {
                // No collect method found, don't provide the code action
                None
              }
            }
        } else {
          Future.successful {
            None
          }
        }
      }
    }

    result match {
      case None => None
      case Some(future) => Some(future.map(_.getOrElse(codeAction)))
    }
  }

  override def contribute(params: CodeActionParams, token: CancelToken)(implicit
      ec: ExecutionContext
  ): Future[Seq[l.CodeAction]] = Future {
    val uri = params.getTextDocument().getUri()

    val path = uri.toAbsolutePath
    val start = params.getRange.getStart

    trees
      .findLastEnclosingAt[Term.Apply](path, start)
      .flatMap(findFilterMapChain)
      .map(_ => {
        val data =
          FilterMapCollectParams(
            params.getTextDocument(),
            start,
          )
        CodeActionBuilder.build(
          title = FilterMapToCollectCodeAction.title,
          kind = this.kind,
          data = Some(data.toJsonObject),
        )
      })
      .toSeq
  }

  private def toTextEdit(chain: FilterMapChain) = {
    val param = chain.filterFn.params.head
    val paramName = Term.Name(param.name.value)
    val paramPatWithType = param.decltpe match {
      case Some(tpe) => Pat.Typed(Pat.Var(paramName), tpe)
      case None => Pat.Var(paramName)
    }

    val collectCall = Term.Apply(
      fun = Term.Select(chain.qual, Term.Name("collect")),
      argClause = Term.ArgClause(
        values = List(
          Term.PartialFunction(
            cases = List(
              Case(
                pat = paramPatWithType,
                cond = Some(chain.filterFn.renameParam(paramName)),
                body = chain.mapFn.renameParam(paramName),
              )
            )
          )
        )
      ),
    )
    val indented = collectCall.syntax.linesIterator.zipWithIndex
      .map {
        case (line, 0) => line
        case (line, _) => "  " + line
      }
      .mkString("\n")

    new l.TextEdit(chain.pos.toLsp, indented)
  }

  private implicit class FunctionOps(fn: Term.Function) {
    def renameParam(to: Term.Name): Term = {
      val fnParamName = fn.params.head.name.value
      fn.body
        .transform { case Term.Name(name) if name == fnParamName => to }
        .asInstanceOf[Term]
    }
  }

  private def findFilterMapChain(tree: Term.Apply): Option[FilterMapChain] = {
    val x = Term.Name("x")
    def extractFunction(arg: Tree): Option[Term.Function] = arg match {
      case fn: Term.Function => Some(fn)
      case Term.Block(List(fn: Term.Function)) => extractFunction(fn)
      case ref: Term.Name => {
        Some(
          Term.Function(
            UnaryParameterList(x),
            Term.Apply(ref, Term.ArgClause(List(x))),
          )
        )
      }
      case _ => None
    }

    def findChain(tree: Term.Apply): Option[FilterMapChain] =
      tree match {
        case MapFunctionApply(
              FilterFunctionApply(base, filterArg, _),
              mapArg,
              mapPos,
            ) =>
          for {
            filterFn <- extractFunction(filterArg)
            mapFn <- extractFunction(mapArg)
          } yield FilterMapChain(tree.pos, base, filterFn, mapFn, mapPos)
        case _ => None
      }

    findChain(tree).orElse {
      // If we're inside the chain, look at our parent
      tree.parent.flatMap {
        // We're in a method call or function, look at parent apply
        case Term.Select(_, Term.Name("map" | "filter")) | Term.Function(_) =>
          tree.parent
            .flatMap(_.parent)
            .collectFirst { case parent: Term.Apply => parent }
            .flatMap(findChain)
        case _ => None
      }
    }
  }

  private object UnaryParameterList {
    def unapply(tree: Tree): Option[Name] = tree match {
      case Term.Param(_, name, _, _) => Some(name)
      case _ => None
    }
    def apply(name: Name): List[Term.Param] = List(
      Term.Param(Nil, name, None, None)
    )
  }

  private case class FunctionApply(val name: String) {
    def unapply(tree: Tree): Option[(Term, Term, Position)] = tree match {
      case Term.Apply(sel @ Term.Select(base, Term.Name(`name`)), List(args)) =>
        Some((base, args, sel.name.pos))
      case _ => None
    }
  }
  private val FilterFunctionApply = new FunctionApply("filter")
  private val MapFunctionApply = new FunctionApply("map")

  private case class FilterMapChain(
      pos: Position,
      qual: Term,
      filterFn: Term.Function,
      mapFn: Term.Function,
      mapPos: Position,
  )
}

object FilterMapToCollectCodeAction {
  val title = "Convert to collect"
}
