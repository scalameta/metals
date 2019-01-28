package scala.meta.internal.metals

import java.util.logging.Level
import java.util.logging.Logger
import scala.collection.JavaConverters._
import scala.meta._
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.internal.mtags.ScalaMtags
import scala.meta.internal.semanticdb.Language
import scala.meta.internal.semanticdb.Scala.Descriptor
import scala.meta.internal.semanticdb.Scala.Symbols
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.trees.Origin
import scala.meta.pc.SymbolDocumentation
import scala.meta.pc.SymbolIndexer
import scala.meta.pc.SymbolVisitor
import scala.meta.tokens.Token
import scala.meta.tokens.Tokens
import scala.util.control.NonFatal
import scala.meta.internal.docstrings._

class MetalsSymbolIndexer(index: OnDemandSymbolIndex) extends SymbolIndexer {
  private val logger = Logger.getLogger(classOf[MetalsSymbolIndexer].getName)
  override def visit(symbol: String, visitor: SymbolVisitor): Unit = {
    index.definition(Symbol(symbol)) match {
      case Some(defn) =>
        defn.path.toLanguage match {
          case Language.JAVA =>
            new JavaSymbolIndexer(defn.path.toInput).visit(symbol, visitor)
          case Language.SCALA =>
            val input = defn.path.toInput
            val mtags = new ScalaMtags(input) {
              override def visitOccurrence(
                  occ: SymbolOccurrence,
                  sinfo: SymbolInformation,
                  owner: _root_.scala.Predef.String
              ): Unit = {
                val docstring = currentTree.origin match {
                  case Origin.None => ""
                  case parsed: Origin.Parsed =>
                    val leadingDocstring =
                      findLeadingDocstring(source.tokens, parsed.pos.start - 1)
                    leadingDocstring match {
                      case Some(value) => value
                      case None => ""
                    }
                }
                class Param(name: String) {
                  def unapply(line: String): Option[String] = {
                    val idx = line.lastIndexOf(name)
                    if (idx < 0) None
                    else Some(line.substring(idx + name.length))
                  }
                }
                def doc(name: String): String = {
                  val param = new Param(name)
                  docstring.lines
                    .collectFirst {
                      case param(line) => line
                    }
                    .getOrElse("")
                }
                lazy val markdown = toMarkdown(docstring)
                def param(name: String, default: String): SymbolDocumentation =
                  new MetalsSymbolDocumentation(
                    Symbols.Global(owner, Descriptor.Parameter(name)),
                    name,
                    doc(name),
                    default
                  )
                def mparam(member: Member): SymbolDocumentation = {
                  val default = member match {
                    case Term.Param(_, _, _, Some(term)) => term.syntax
                    case _ =>
                      ""
                  }
                  param(member.name.value, default)
                }
                val info = currentTree match {
                  case _: Defn.Trait | _: Pkg.Object | _: Defn.Val |
                      _: Defn.Var | _: Decl.Val | _: Decl.Var | _: Defn.Type |
                      _: Decl.Type =>
                    Some(
                      new MetalsSymbolDocumentation(
                        occ.symbol,
                        sinfo.displayName,
                        markdown,
                        ""
                      )
                    )
                  case t: Defn.Def =>
                    Some(
                      new MetalsSymbolDocumentation(
                        occ.symbol,
                        t.name.value,
                        markdown,
                        "",
                        t.tparams.map(mparam).asJava,
                        t.paramss.flatten.map(mparam).asJava
                      )
                    )
                  case t: Decl.Def =>
                    Some(
                      new MetalsSymbolDocumentation(
                        occ.symbol,
                        t.name.value,
                        markdown,
                        "",
                        t.tparams.map(mparam).asJava,
                        t.paramss.flatten.map(mparam).asJava
                      )
                    )
                  case t: Defn.Class =>
                    Some(
                      new MetalsSymbolDocumentation(
                        occ.symbol,
                        t.name.value,
                        markdown,
                        "",
                        // Type parameters are intentionally excluded because constructors
                        // cannot have type parameters.
                        Nil.asJava,
                        t.ctor.paramss.flatten.map(mparam).asJava
                      )
                    )
                  case t: Member =>
                    Some(
                      new MetalsSymbolDocumentation(
                        occ.symbol,
                        t.name.value,
                        markdown,
                        ""
                      )
                    )
                  case _ =>
                    None
                }
                info.foreach(visitor.visitSymbol)
              }
            }
            try mtags.indexRoot()
            catch {
              case NonFatal(e) =>
                logger.log(Level.SEVERE, defn.path.toURI.toString, e)
            }
          case _ =>
        }
      case None =>
    }
  }

  def toMarkdown(docstring: String): String = {
    val comment = ScaladocParser.parseAtSymbol(docstring)
    val out = new StringBuilder()
    def loop(i: Inline): Unit = i match {
      case Chain(items) =>
        items.foreach(loop)
      case Italic(text) =>
        out.append('*')
        loop(text)
        out.append('*')
      case Bold(text) =>
        out.append("**")
        loop(text)
        out.append("**")
      case Underline(text) =>
        out.append("_")
        loop(text)
        out.append("_")
      case Superscript(text) =>
        loop(text)
      case Subscript(text) =>
        loop(text)
      case Link(target, title) =>
        out.append("[")
        loop(title)
        out
          .append("](")
          .append(target)
          .append(")")
      case Monospace(text) =>
        out.append("`")
        loop(text)
        out.append("`")
      case Text(text) =>
        out.append(text)
      case _: EntityLink =>
      case HtmlTag(data) =>
        out.append(data)
      case Summary(text) =>
        loop(text)
    }
    loop(comment.short)
    out.toString().trim
  }

  def findLeadingDocstring(tokens: Tokens, start: Int): Option[String] =
    if (start < 0) None
    else {
      tokens(start) match {
        case c: Token.Comment =>
          val syntax = c.syntax
          if (syntax.startsWith("/**")) Some(syntax)
          else None
        case _: Token.Space | _: Token.LF | _: Token.CR | _: Token.LFLF |
            _: Token.Tab =>
          findLeadingDocstring(tokens, start - 1)
        case _ =>
          None
      }
    }
}
