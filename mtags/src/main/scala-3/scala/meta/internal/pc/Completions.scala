package scala.meta.internal.pc

import dotty.tools.dotc.ast.tpd.{
  Ident,
  Apply,
  Tree,
  NamedArg,
  Template,
  Block,
  Literal,
  Select
}
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.util.SourcePosition
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.interactive.Completion
import dotty.tools.dotc.core.Symbols.Symbol
import dotty.tools.dotc.core.Names.Name
import dotty.tools.dotc.core.NameKinds.DefaultGetterName
import dotty.tools.dotc.core.Flags

trait Completions {
  def completionPosition(
      pos: SourcePosition,
      path: List[Tree]
  )(using ctx: Context): List[Completion] = {
    path match {
      case (ident: Ident) :: (app: Apply) :: _ => // fun(arg@@)
        ArgCompletion(Some(ident), app, pos).contribute
      case (app: Apply) :: _ => // fun(@@)
        ArgCompletion(None, app, pos).contribute
      case _ =>
        Nil
    }
  }
}

case class ArgCompletion(
    ident: Option[Ident],
    apply: Apply,
    pos: SourcePosition
)(using ctx: Context) {
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

  val allParams: List[Symbol] = {
    baseParams.filterNot(param =>
      isNamed(param.name) ||
        param.denot.is(
          Flags.Synthetic
        ) // filter out synthesized param, like evidence
    )
  }

  val prefix = ident.map(_.name.toString).getOrElse("")
  val params: List[Symbol] =
    allParams.filter(param => param.name.startsWith(prefix))

  def contribute: List[Completion] = {
    val printer = SymbolPrinter()
    params.map(p => {
      Completion(
        s"${p.name.toString} = ",
        printer.typeString(p.info),
        List(p)
      )
    })
  }

  private def isUselessLiteral(arg: Tree): Boolean = {
    arg match {
      case Literal(Constant(())) => true // unitLiteral
      case Literal(Constant(null)) => true // nullLiteral
      case _ => false
    }
  }

  private def collectArgss(a: Apply): List[List[Tree]] = {
    a.fun match {
      case app: Apply => collectArgss(app) :+ a.args
      case _ => List(a.args)
    }
  }
}
