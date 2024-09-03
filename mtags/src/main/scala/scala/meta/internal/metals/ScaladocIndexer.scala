package scala.meta.internal.metals

import scala.collection.mutable

import scala.meta._
import scala.meta.internal.docstrings._
import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.mtags.ScalaMtags
import scala.meta.internal.semanticdb.Scala.Descriptor
import scala.meta.internal.semanticdb.Scala.Symbols
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.pc.ContentType
import scala.meta.pc.ContentType.MARKDOWN
import scala.meta.pc.ContentType.PLAINTEXT
import scala.meta.pc.SymbolDocumentation
import scala.meta.tokens.Token
import scala.meta.trees.Origin

/**
 * Extracts Scaladoc from Scala source code.
 */
class ScaladocIndexer(
    input: Input.VirtualFile,
    fn: SymbolDocumentation => Unit,
    dialect: Dialect,
    contentType: ContentType
) extends ScalaMtags(input, dialect) {
  val defines: mutable.Map[String, String] = mutable.Map.empty[String, String]
  override def visitOccurrence(
      occ: SymbolOccurrence,
      sinfo: SymbolInformation,
      owner: String
  ): Unit = {
    val printer =
      contentType match {
        case MARKDOWN => printers.MarkdownGenerator
        case PLAINTEXT => printers.PlaintextGenerator
      }

    val docstring = currentTree.origin match {
      case Origin.Parsed(_, start, _) =>
        val leadingDocstring =
          ScaladocIndexer.findLeadingDocstring(
            source.tokens,
            start - 1
          )
        leadingDocstring match {
          case Some(value) => value
          case None => ""
        }
      case _ => ""
    }
    // Register `@define` macros to use for expanding in later docstrings.
    defines ++= ScaladocParser.extractDefines(docstring)
    val comment = ScaladocParser.parseComment(docstring, defines)
    val docstringContent = printer.toText(comment, docstring)
    def param(name: String, default: String): SymbolDocumentation = {
      val paramDoc = comment.valueParams
        .get(name)
        .orElse(comment.typeParams.get(name))
        .map(printer.toText)
        .getOrElse("")
      MetalsSymbolDocumentation(
        Symbols.Global(owner, Descriptor.Parameter(name)),
        name,
        paramDoc,
        default
      )
    }
    def mparam(member: Member): SymbolDocumentation = {
      val default = member match {
        case Term.Param(_, _, _, Some(term)) => term.syntax
        case _ =>
          ""
      }
      param(member.name.value, default)
    }
    val info = currentTree match {
      case _: Defn.Trait | _: Pkg.Object | _: Defn.Val | _: Defn.Var |
          _: Decl.Val | _: Decl.Var | _: Defn.Type | _: Decl.Type =>
        Some(
          MetalsSymbolDocumentation(
            occ.symbol,
            sinfo.displayName,
            docstringContent
          )
        )
      case t: Defn.Def =>
        Some(
          MetalsSymbolDocumentation(
            occ.symbol,
            t.name.value,
            docstringContent,
            "",
            t.tparams.map(mparam).asJava,
            t.paramss.flatten.map(mparam).asJava
          )
        )
      case t: Decl.Def =>
        Some(
          MetalsSymbolDocumentation(
            occ.symbol,
            t.name.value,
            docstringContent,
            "",
            t.tparams.map(mparam).asJava,
            t.paramss.flatten.map(mparam).asJava
          )
        )
      case t: Defn.Class =>
        Some(
          MetalsSymbolDocumentation(
            occ.symbol,
            t.name.value,
            docstringContent,
            "",
            // Type parameters are intentionally excluded because constructors
            // cannot have type parameters.
            Nil.asJava,
            t.ctor.paramss.flatten.map(mparam).asJava
          )
        )
      case t: Member =>
        Some(
          MetalsSymbolDocumentation(
            occ.symbol,
            t.name.value,
            docstringContent
          )
        )
      case _ =>
        None
    }
    info.foreach(fn)
  }
}

object ScaladocIndexer {

  /**
   * Extracts Scaladoc from Scala source code.
   *
   * @param fn callback function for calculated SymbolDocumentation
   */
  def foreach(
      input: Input.VirtualFile,
      dialect: Dialect,
      contentType: ContentType
  )(fn: SymbolDocumentation => Unit): Unit = {
    new ScaladocIndexer(input, fn, dialect, contentType).indexRoot()
  }

  /**
   * Returns a Scaladoc string leading the given start position, if any.
   * @param start the offset in the `Tokens` array.
   */
  def findLeadingDocstring(tokens: Tokens, start: Int): Option[String] =
    if (start < 0) None
    else {
      tokens(start) match {
        case c: Token.Comment =>
          val syntax = c.syntax
          if (syntax.startsWith("/**")) Some(syntax)
          else None
        case _: Token.Whitespace =>
          findLeadingDocstring(tokens, start - 1)
        case _ =>
          None
      }
    }

}
