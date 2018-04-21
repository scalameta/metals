package scala.meta.metals.providers

import scala.meta.metals.Uri
import org.langmeta.lsp.Hover
import org.langmeta.lsp.RawMarkedString
import scala.meta.metals.search.SymbolIndex
import scala.{meta => m}
import scala.meta.internal.semanticdb3.SymbolInformation.{Property => p}
import com.typesafe.scalalogging.LazyLogging
import scala.meta.Tree
import scala.meta._
import scala.meta.metals.compiler.SymtabProvider
import scala.util.control.NonFatal
import scalafix.internal.util.PrettyType
import scalafix.internal.util.QualifyStrategy
import scala.meta.internal.{semanticdb3 => s}
import scalafix.internal.util.SymbolTable
import scala.meta.internal.semanticdb3.Scala._

object HoverProvider extends LazyLogging {
  def empty: Hover = Hover(Nil, None)
  val Template =
    m.Template(Nil, Nil, m.Self(m.Name.Anonymous(), None), Nil)

  private def isSymbolicName(name: String): Boolean =
    !Character.isJavaIdentifierStart(name.head)
  val EmptyTemplate = template"{}"
  val EmptyCtor = q"def this"

  private def toTree(
      info: s.SymbolInformation,
      symtab: SymbolTable
  ): Tree = {
    def mods = {
      // TODO: Upstream FINAL OBJECT fix to scalafix,
      // this method is copy pasted from PrettyType
      def is(property: s.SymbolInformation.Property): Boolean =
        (info.properties & property.value) != 0
      val buf = List.newBuilder[Mod]
      info.accessibility.foreach { accessibility =>
        // TODO: private[within]
        if (accessibility.tag.isPrivate) buf += Mod.Private(Name.Anonymous())
        if (accessibility.tag.isProtected)
          buf += Mod.Protected(Name.Anonymous())
      }
      if (is(p.SEALED)) buf += Mod.Sealed()
      if (info.kind.isClass && is(p.ABSTRACT)) buf += Mod.Abstract()
      if (!info.kind.isObject && is(p.FINAL)) buf += Mod.Final()
      if (is(p.IMPLICIT)) buf += Mod.Implicit()
      if (info.kind.isClass && is(p.CASE)) buf += Mod.Case()
      buf.result()
    }
    if (info.kind.isObject) {
      Defn.Object(mods, Term.Name(info.name), EmptyTemplate)
    } else if (info.kind.isPackageObject) {
      Pkg.Object(mods, Term.Name(info.owner.desc.name), EmptyTemplate)
    } else if (info.kind.isPackage) {
      val ref =
        info.symbol.stripSuffix(".").parse[Term].get.asInstanceOf[Term.Ref]
      Pkg(ref, Nil)
    } else if (info.kind.isTrait) {
      // TODO: include type parameters
      Defn.Trait(mods, Type.Name(info.name), Nil, EmptyCtor, EmptyTemplate)
    } else if (info.kind.isClass) {
      // TODO: include type parameters and primary constructor
      Defn.Class(mods, Type.Name(info.name), Nil, EmptyCtor, EmptyTemplate)
    } else if (info.symbol == "scala.Any#asInstanceOf().") {
      // HACK(olafur) to avoid 'java.util.NoSuchElementException: scala.Any.asInstanceOf(A).[A]'
      q"final def asInstanceOf[T]: T"
    } else if (info.kind.isLocal ||
      info.kind.isParameter ||
      info.symbol.startsWith("local")) {
      // Workaround for https://github.com/scalameta/scalameta/issues/1503
      // In the future we should be able to produce `val x: Int` syntax for local symbols.
      val tpe = info.tpe match {
        case Some(t) =>
          if (t.tag.isMethodType) t.methodType.get.returnType.get
          else t
        case _ =>
          throw new IllegalArgumentException(info.toProtoString)
      }
      PrettyType.toType(tpe, symtab, QualifyStrategy.Readable).tree
    } else {
      PrettyType.toTree(info, symtab, QualifyStrategy.Readable).tree
    }
  }

  def hover(
      index: SymbolIndex,
      symtabs: SymtabProvider,
      uri: Uri,
      line: Int,
      column: Int
  ): Hover = {
    val result = for {
      (symbol, _) <- index.findSymbol(uri, line, column)
      symtab = symtabs.symtab(uri)
      info <- symtab.info(symbol.syntax)
      tree <- {
        try {
          Some(toTree(info, symtab))
        } catch {
          case NonFatal(e) =>
            logger.error(
              s"""Failed to pretty print symbol $symbol
                 |Classpath=$symtab
                 |${info.toProtoString}
                 |""".stripMargin,
              e
            )
            None
        }
      }
    } yield {
      val pretty = tree.transform {
        case Type.Apply(op: Type.Name, lhs :: rhs :: Nil)
            if isSymbolicName(op.value) =>
          Type.ApplyInfix(lhs, op, rhs)
      }
      Hover(
        contents = RawMarkedString(language = "scala", value = pretty.syntax) :: Nil,
        range = None
      )
    }
    result.getOrElse(Hover(Nil, None))
  }

}
