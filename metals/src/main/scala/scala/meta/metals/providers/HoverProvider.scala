package scala.meta.metals.providers

import scala.meta.Type
import scala.meta.metals.Uri
import scala.meta.lsp.Hover
import scala.meta.lsp.RawMarkedString
import scala.meta.metals.search.SymbolIndex
import scala.{meta => m}
import scalafix.internal.util.DenotationOps
import scalafix.internal.util.TypeSyntax
import scalafix.rule.RuleCtx
import scalafix.util.SemanticdbIndex
import scala.meta.metals.index.SymbolData
import scala.meta.metals.MetalsLogger
import org.langmeta.internal.semanticdb.schema

object HoverProvider extends MetalsLogger {
  def empty: Hover = Hover(Nil, None)
  val Template =
    m.Template(Nil, Nil, m.Self(m.Name.Anonymous(), None), Nil)

  def hover(
      index: SymbolIndex,
      uri: Uri,
      line: Int,
      column: Int
  ): Hover = {
    val result = for {
      (symbol, _) <- index.findSymbol(uri, line, column)
      data <- index.data(symbol)
      tpe <- getPrettyDefinition(symbol, data)
      document <- index.documentIndex.getDocument(uri)
    } yield {
      val scalafixIndex = SemanticdbIndex.load(
        schema.Database(document :: Nil).toDb(None),
        m.Sourcepath(Nil),
        m.Classpath(Nil)
      )
      val prettyTpe = new TypePrinter()(scalafixIndex).apply(tpe)
      Hover(
        contents = RawMarkedString(language = "scala", value = prettyTpe.syntax) :: Nil,
        range = None
      )
    }
    result.getOrElse(Hover(Nil, None))
  }

  /** Returns a definition tree for this symbol signature */
  private def getPrettyDefinition(
      symbol: m.Symbol,
      data: SymbolData
  ): Option[m.Tree] = {
    val denotation = m.Denotation(data.flags, data.name, data.signature, Nil)
    val input = m.Input.Denotation(denotation.signature, symbol)
    val mods = getMods(denotation)
    val name = m.Term.Name(denotation.name)
    val tname = m.Type.Name(denotation.name)
    def parsedTpe: Option[Type] =
      DenotationOps.defaultDialect(input).parse[m.Type].toOption
    if (denotation.isVal) {
      parsedTpe.map { tpe =>
        m.Decl.Val(mods, m.Pat.Var(name) :: Nil, tpe)
      }
    } else if (denotation.isVar) {

      parsedTpe.collect {
        case Type.Method((m.Term.Param(_, _, Some(tpe), _) :: Nil) :: Nil, _) =>
          m.Decl.Var(
            mods,
            // TODO(olafur) fix https://github.com/scalameta/scalameta/issues/1100
            m.Pat.Var(m.Term.Name(name.value.stripSuffix("_="))) :: Nil,
            tpe
          )
      }
    } else if (denotation.isDef) {
      // turn method types into defs
      // TODO(olafur) handle def macros
      DenotationOps.defaultDialect(input).parse[m.Type].toOption.map {
        case m.Type.Lambda(tparams, m.Type.Method(paramss, tpe)) =>
          m.Decl.Def(mods, name, tparams, paramss, tpe)
        case m.Type.Lambda(tparams, tpe) =>
          m.Decl.Def(mods, name, tparams, Nil, tpe)
        case m.Type.Method(paramss, tpe) =>
          m.Decl.Def(mods, name, Nil, paramss, tpe)
        case t => t
      }
    } else if (denotation.isPackageObject) {
      symbol match {
        case m.Symbol.Global(
            m.Symbol.Global(_, m.Signature.Term(pkg)),
            m.Signature.Term("package")
            ) =>
          Some(m.Pkg.Object(mods, m.Term.Name(pkg), Template))
        case _ =>
          logger.warn(s"Unexpected package object symbol: $symbol")
          None
      }
    } else if (denotation.isType && denotation.isAbstract) {
      Some(
        m.Decl.Type(
          mods.filterNot(_.is[m.Mod.Abstract]),
          tname,
          Nil,
          m.Type.Bounds(None, None)
        )
      )
    } else if (denotation.isType) {
      parsedTpe.map {
        case m.Type.Lambda(tparams, tpe) =>
          m.Defn.Type(mods, tname, tparams, tpe)
        case tpe =>
          m.Defn.Type(mods, tname, Nil, tpe)
      }
    } else if (denotation.isObject) {
      Some(m.Defn.Object(mods.filterNot(_.is[m.Mod.Final]), name, Template))
    } else if (denotation.isClass) {
      Some(
        m.Defn.Class(
          mods,
          tname,
          Nil,
          m.Ctor.Primary(Nil, m.Name.Anonymous(), Nil),
          Template
        )
      )
    } else if (denotation.isTrait) {
      Some(
        m.Defn.Trait(
          mods,
          tname,
          Nil,
          m.Ctor.Primary(Nil, m.Name.Anonymous(), Nil),
          Template
        )
      )
    } else if (denotation.isPackage) {
      Some(m.Pkg(name, Nil))
    } else if (!denotation.signature.isEmpty) {
      parsedTpe
    } else {
      Some(m.Type.Name(data.name))
    }
  }

  private def getMods(denotation: m.Denotation): List[m.Mod] = {
    import denotation._
    import scala.meta._
    val buf = List.newBuilder[m.Mod]
    if (isPrivate) buf += mod"private"
    if (isProtected) buf += mod"protected"
    if (isFinal) buf += mod"final"
    if (isAbstract) buf += mod"abstract"
    if (isImplicit) buf += mod"implicit"
    if (isLazy) buf += mod"lazy"
    if (isSealed) buf += mod"sealed"
    buf.result()
  }

  /** Pretty-prints types in a given tree
   *
   * Uses the scalafix TypeSyntax, the same one used by ExplicitResultTypes.
   * It's quite primitive for now, but the plan is to implement fancy
   * stuff in the future like scope aware printing taking into
   * account renames etc.
   */
  private class TypePrinter(implicit scalafixIndex: SemanticdbIndex)
      extends m.Transformer {
    private def pretty(tpe: m.Type): m.Tree = {
      val printed =
        TypeSyntax.prettify(tpe, RuleCtx(m.Lit.Null()), shortenNames = true)._1
      InfixSymbolicTypes.apply(printed)
    }

    override def apply(tree: m.Tree): m.Tree = tree match {
      case tpe: m.Type => {
        pretty(tpe)
      }
      case _ => super.apply(tree)
    }
  }

  /** Makes all symbolic type binary operators infix */
  private object InfixSymbolicTypes extends m.Transformer {
    private object SymbolicType {
      def unapply(arg: m.Type): Option[m.Type.Name] = arg match {
        case nme @ m.Type.Name(name) =>
          if (!name.isEmpty &&
            !Character.isJavaIdentifierStart(name.charAt(0))) {
            Some(nme)
          } else None
        case _ => None
      }
    }
    override def apply(tree: m.Tree): m.Tree = {
      val next = tree match {
        case m.Type.Apply(SymbolicType(name), lhs :: rhs :: Nil) =>
          m.Type.ApplyInfix(lhs, name, rhs)
        case t => t
      }
      super.apply(next)
    }
  }
}
