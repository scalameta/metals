package scala.meta.internal.metals

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.meta._
import scala.meta.internal.docstrings._
import scala.meta.internal.mtags.ScalaMtags
import scala.meta.internal.semanticdb.Scala.Descriptor
import scala.meta.internal.semanticdb.Scala.Symbols
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.trees.Origin
import scala.meta.pc.SymbolDocumentation

/**
 * Extracts Scaladoc from Scala source code.
 */
class ScaladocIndexer(
    input: Input.VirtualFile,
    fn: SymbolDocumentation => Unit
) extends ScalaMtags(input) {
  val describes = mutable.Map.empty[String, String]
  def toMarkdown(symbol: String, docstring: String): String = {
    describes ++= ScaladocParser.extractDefines(docstring)
    val comment = ScaladocParser.parseComment(docstring, describes)
    MarkdownGenerator.toMarkdown(comment)
  }
  override def visitOccurrence(
      occ: SymbolOccurrence,
      sinfo: SymbolInformation,
      owner: _root_.scala.Predef.String
  ): Unit = {
    val docstring = currentTree.origin match {
      case Origin.None => ""
      case parsed: Origin.Parsed =>
        val leadingDocstring =
          ScaladocIndexer.findLeadingDocstring(
            source.tokens,
            parsed.pos.start - 1
          )
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
    lazy val markdown = toMarkdown(occ.symbol, docstring)
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
      case _: Defn.Trait | _: Pkg.Object | _: Defn.Val | _: Defn.Var |
          _: Decl.Val | _: Decl.Var | _: Defn.Type | _: Decl.Type =>
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
    info.foreach(fn)
  }
}

object ScaladocIndexer {

  def foreach(
      input: Input.VirtualFile
  )(fn: SymbolDocumentation => Unit): Unit = {
    new ScaladocIndexer(input, fn).indexRoot()
  }

  def toMarkdown(docstring: String): String = {

    List(1).headOption
//        MarkdownGenerator.toMarkdown(docstring)
    docstring
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
