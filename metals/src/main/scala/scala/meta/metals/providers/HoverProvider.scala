package scala.meta.metals.providers

import com.typesafe.scalalogging.LazyLogging
import org.langmeta.lsp.Hover
import org.langmeta.lsp.RawMarkedString
import scala.meta.Tree
import scala.meta._
import scala.meta.internal.semanticdb3.SymbolInformation.Property
import scala.meta.internal.{semanticdb3 => s}
import scala.meta.internal.semanticdb3.Scala._
import scala.meta.metals.Uri
import scala.meta.metals.compiler.SymtabProvider
import scala.meta.metals.ScalametaEnrichments._
import scala.meta.metals.search.SymbolIndex
import scala.util.control.NonFatal
import scala.{meta => m}
import scalafix.internal.util.PrettyType
import scalafix.internal.util.QualifyStrategy
import scalafix.internal.util.SymbolTable

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
  ): Option[Tree] = {
    try {
      Some(unsafeToTree(info, symtab))
    } catch {
      case NonFatal(e) =>
        logger.error(
          s"""Failed to pretty print symbol ${info.symbol}
             |Classpath=$symtab
             |${info.toProtoString}
             """.stripMargin,
          e
        )
        None
    }
  }

  private def unsafeToTree(
      info: s.SymbolInformation,
      symtab: SymbolTable
  ): Tree = {
    if (info.kind.isPackage) {
      val ref =
        info.symbol.stripSuffix(".").parse[Term].get.asInstanceOf[Term.Ref]
      Pkg(ref, Nil)
    } else if (info.kind.isPackageObject) {
      // NOTE(olafur) custom handling for name due to https://github.com/scalameta/scalameta/issues/1484
      Pkg.Object(Nil, Term.Name(info.owner.desc.name), EmptyTemplate)
    } else if (info.kind.isObject) {
      // NOTE(olafur) custom handling for object due to https://github.com/scalameta/scalameta/issues/1480
      val mods = List.newBuilder[Mod]
      if (info.has(Property.IMPLICIT)) mods += mod"implicit"
      if (info.has(Property.CASE)) mods += mod"case"
      Defn.Object(mods.result(), Term.Name(info.name), EmptyTemplate)
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
          if (t.tag.isMethodType) {
            t.methodType.get.returnType.get
          } else {
            t
          }
        case _ =>
          throw new IllegalArgumentException(info.toProtoString)
      }
      PrettyType.toType(tpe, symtab, QualifyStrategy.Readable).tree
    } else {
      val tpe = info.tpe.get
      val noDeclarations =
        if (tpe.tag.isClassInfoType) {
          val classInfoType = tpe.classInfoType.get
          // Remove declarations from object/trait/class to reduce noise
          val declarations =
            if (info.kind.isClass) {
              classInfoType.declarations.find { sym =>
                symtab
                  .info(sym)
                  .exists(i => i.kind.isConstructor && i.has(Property.PRIMARY))
              }.toList
            } else {
              Nil
            }
          info.copy(
            tpe = Some(
              tpe.copy(
                classInfoType =
                  Some(classInfoType.withDeclarations(declarations))
              )
            )
          )
        } else {
          info
        }
      PrettyType.toTree(noDeclarations, symtab, QualifyStrategy.Readable).tree
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
      tree <- toTree(info, symtab)
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
