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
  // Per-file memoization shared by every documented occurrence's import-scope
  // lookup, so a documentation-heavy source isn't rescanned O(n²) times.
  private val importScopeCache = new ScaladocImportScope.Cache

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
    // Relative scaladoc links resolve against the enclosing template, so we bake
    // its symbol into the link markers, plus a companion alternative for enums
    // and givens whose members live in the companion (scalameta/metals#3383).
    val (contextSymbol, contextAlternative) = currentTree match {
      case _: Defn.Class | _: Defn.Trait | _: Defn.Object | _: Pkg.Object |
          _: Pkg =>
        (occ.symbol, "")
      case _: Defn.Enum =>
        (occ.symbol, ScaladocIndexer.companion(occ.symbol))
      case _: Defn.EnumCase =>
        // A parameterized enum case is a case class whose members live under its
        // own symbol, so tie the docstring to the case and its companion value
        // rather than the enum's companion (scalameta/metals#3383).
        (occ.symbol, ScaladocIndexer.companion(occ.symbol))
      case _: Defn.Given =>
        // A given's occurrence symbol is method-shaped (`name().`) when it is
        // parameterized and value-shaped (`name.`) otherwise, but its members
        // live under the type symbol (`name#`). Derive both forms from the
        // symbol so anonymous givens (`given_<type>`) are handled too.
        val name = ScaladocIndexer.descriptorName(occ.symbol, owner)
        (
          Symbols.Global(owner, Descriptor.Type(name)),
          Symbols.Global(owner, Descriptor.Term(name))
        )
      case _ => (owner, "")
    }
    // Carry the source's import fallbacks and owner context in the link markers so
    // links resolve on click; the same scope analysis backs source go-to-definition
    // so the two can't drift — see [[ScaladocImportScope]] (scalameta/metals#3383).
    lazy val importScope =
      ScaladocImportScope.at(
        currentTree,
        ScaladocImportScope.packageOf(owner),
        importScopeCache
      )
    def withImportsAndContext(rendered: String): String =
      MetalsSymbolLink.withDocScope(
        rendered,
        DocScope(
          Some(contextSymbol).filter(_.nonEmpty),
          Some(contextAlternative).filter(_.nonEmpty),
          isJava = false,
          importScope,
          // The declaration file's URI, so hover applies same-compilation-unit
          // precedence like source go-to-definition (scalameta/metals#3383).
          Some(input.path),
          // This docstring's own dialect, so hover doesn't apply Scala 3 source-
          // order rules to a Scala 2 library's docs (scalameta/metals#3383).
          docIsScala3 = dialect.allowSignificantIndentation
        )
      )
    val docstringContent =
      withImportsAndContext(printer.toText(comment, docstring))
    def param(name: String, default: String): SymbolDocumentation = {
      val paramDoc = comment.valueParams
        .get(name)
        .orElse(comment.typeParams.get(name))
        .map(printer.toText)
        .map(withImportsAndContext)
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
        case Term.Param(_, _, _, Some(term)) =>
          term.syntax
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

  /** The companion form of a template symbol (`Foo#` <-> `Foo.`), or "". */
  private def companion(symbol: String): String =
    if (symbol.endsWith("#")) symbol.dropRight(1) + "."
    else if (symbol.endsWith(".")) symbol.dropRight(1) + "#"
    else ""

  /**
   * The simple (descriptor) name of `symbol` given its `owner` prefix, dropping
   * any method parameter list. E.g. `a/given_Foo().` with owner `a/` -> `given_Foo`.
   */
  private def descriptorName(symbol: String, owner: String): String = {
    val tail = symbol.stripPrefix(owner)
    tail.indexOf('(') match {
      case -1 => tail.stripSuffix(".").stripSuffix("#")
      case i => tail.substring(0, i)
    }
  }

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
