package scala.meta.internal.metals

import scala.meta.internal.jdk.CollectionConverters._
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
  val defines: mutable.Map[String, String] = mutable.Map.empty[String, String]
  override def visitOccurrence(
      occ: SymbolOccurrence,
      sinfo: SymbolInformation,
      owner: String
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
    // Register `@define` macros to use for expanding in later docstrings.
    defines ++= ScaladocParser.extractDefines(docstring)
    val comment = ScaladocParser.parseComment(docstring, defines)
    val markdown = MarkdownGenerator.toMarkdown(comment)
    def param(name: String, default: String): SymbolDocumentation = {
      val paramDoc = comment.valueParams
        .get(name)
        .orElse(comment.typeParams.get(name))
        .map(MarkdownGenerator.toMarkdown)
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
            markdown
          )
        )
      case t: Defn.Def =>
        Some(
          MetalsSymbolDocumentation(
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
          MetalsSymbolDocumentation(
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
          MetalsSymbolDocumentation(
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
          MetalsSymbolDocumentation(
            occ.symbol,
            t.name.value,
            markdown
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
        case _: Token.Space | _: Token.LF | _: Token.CR | _: Token.LFLF |
            _: Token.Tab =>
          findLeadingDocstring(tokens, start - 1)
        case _ =>
          None
      }
    }

}
