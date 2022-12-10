package scala.meta.internal.pc.completions

import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.internal.pc.IndexedContext

import dotty.tools.dotc.ast.tpd.Apply
import dotty.tools.dotc.ast.tpd.Block
import dotty.tools.dotc.ast.tpd.Ident
import dotty.tools.dotc.ast.tpd.Literal
import dotty.tools.dotc.ast.tpd.NamedArg
import dotty.tools.dotc.ast.tpd.Select
import dotty.tools.dotc.ast.tpd.Template
import dotty.tools.dotc.ast.tpd.Tree
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.NameKinds.DefaultGetterName
import dotty.tools.dotc.core.Names.Name
import dotty.tools.dotc.core.Symbols.Symbol
import dotty.tools.dotc.core.Types.Type
import dotty.tools.dotc.interactive.Completion
import dotty.tools.dotc.util.SourcePosition

object NamedArgCompletions:

  def contribute(
      pos: SourcePosition,
      path: List[Tree],
      indexedContext: IndexedContext,
      clientSupportsSnippets: Boolean,
  )(using ctx: Context): List[CompletionValue] =
    path match
      case (ident: Ident) :: (app: Apply) :: _ => // fun(arg@@)
        contribute(
          Some(ident),
          app,
          pos,
          indexedContext,
          clientSupportsSnippets,
        )
      case (Literal(Constant(null))) :: (app: Apply) :: _ => // fun(a, @@)
        contribute(None, app, pos, indexedContext, clientSupportsSnippets)
      case (app: Apply) :: _ => // fun(@@)
        contribute(None, app, pos, indexedContext, clientSupportsSnippets)
      case _ =>
        Nil

  private def contribute(
      ident: Option[Ident],
      apply: Apply,
      pos: SourcePosition,
      indexedContext: IndexedContext,
      clientSupportsSnippets: Boolean,
  )(using Context): List[CompletionValue] =
    def isUselessLiteral(arg: Tree): Boolean =
      arg match
        case Literal(Constant(())) => true // unitLiteral
        case Literal(Constant(null)) => true // nullLiteral
        case _ => false

    def collectArgss(a: Apply): List[List[Tree]] =
      a.fun match
        case app: Apply => collectArgss(app) :+ a.args
        case _ => List(a.args)

    val method = apply.fun
    val methodSym = method.symbol

    // paramSymss contains both type params and value params
    val vparamss =
      methodSym.paramSymss.filter(params => params.forall(p => p.isTerm))
    val argss = collectArgss(apply)

    // get params and args we are interested in
    // e.g.
    // in the following case, the interesting args and params are
    // - params: [apple, banana]
    // - args: [apple, b]
    // ```
    // def curry(x; Int)(apple: String, banana: String) = ???
    // curry(1)(apple = "test", b@@)
    // ```
    val (baseParams, baseArgs) =
      vparamss.zip(argss).lastOption.getOrElse((Nil, Nil))

    val args = ident
      .map(i => baseArgs.filterNot(_ == i))
      .getOrElse(baseArgs)
      .filterNot(isUselessLiteral)

    val isNamed: Set[Name] = args.iterator
      .zip(baseParams.iterator)
      // filter out synthesized args and default arg getters
      .filterNot {
        case (arg, _) if arg.symbol.denot.is(Flags.Synthetic) => true
        case (Ident(name), _) => name.is(DefaultGetterName) // default args
        case (Select(Ident(_), name), _) =>
          name.is(DefaultGetterName) // default args for apply method
        case _ => false
      }
      .map {
        case (NamedArg(name, _), _) => name
        case (_, param) => param.name
      }
      .toSet

    val allParams: List[Symbol] =
      baseParams.filterNot(param =>
        isNamed(param.name) ||
          param.denot.is(
            Flags.Synthetic
          ) // filter out synthesized param, like evidence
      )

    val prefix = ident.map(_.name.toString).getOrElse("").stripPrefix("CURSOR")
    val params: List[Symbol] =
      allParams.filter(param => param.name.startsWith(prefix))

    val completionSymbols = indexedContext.scopeSymbols
    def matchingTypesInScope(paramType: Type): List[String] =
      completionSymbols
        .collect {
          case sym
              if sym.info <:< paramType && sym.isTerm && !sym.info.isNullType && !sym.info.isNothingType && !sym
                .is(Flags.Method) =>
            sym.decodedName
        }
        .filter(name => name != "Nil" && name != "None")
        .sorted

    def findDefaultValue(param: Symbol): String =
      val matchingType = matchingTypesInScope(param.info)
      if matchingType.size == 1 then s":${matchingType.head}"
      else if matchingType.size > 1 then s"|???,${matchingType.mkString(",")}|"
      else ":???"

    def fillAllFields(): List[CompletionValue] =
      val suffix = "autofill"
      val shouldShow =
        allParams.exists(param => param.name.startsWith(prefix))
      val isExplicitlyCalled = suffix.startsWith(prefix)
      val hasParamsToFill = allParams.count(!_.is(Flags.HasDefault)) > 1
      if (shouldShow || isExplicitlyCalled) && hasParamsToFill && clientSupportsSnippets
      then
        val editText = allParams.zipWithIndex
          .collect {
            case (param, index) if !param.is(Flags.HasDefault) => {
              s"${param.nameBackticked.replace("$", "$$")} = $${${index + 1}${findDefaultValue(param)}}"
            }
          }
          .mkString(", ")
        List(
          CompletionValue.Autofill(
            editText
          )
        )
      else List.empty
      end if
    end fillAllFields

    def findPossibleDefaults(): List[CompletionValue] =
      params.flatMap { param =>
        val allMembers = matchingTypesInScope(param.info)
        allMembers.map { memberName =>
          val editText =
            param.nameBackticked + " = " + memberName + " "
          CompletionValue.namedArg(
            label = editText,
            param,
          )
        }
      }

    params.map(p =>
      CompletionValue.namedArg(
        s"${p.nameBackticked} = ",
        p,
      )
    ) ::: findPossibleDefaults() ::: fillAllFields()
  end contribute

end NamedArgCompletions
