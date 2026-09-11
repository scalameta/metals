package scala.meta.internal.metals

import java.util

import scala.collection.mutable
import scala.util.control.NonFatal

import scala.meta.inputs.Input
import scala.meta.internal.docstrings.DocScope
import scala.meta.internal.docstrings.ImportLevel
import scala.meta.internal.docstrings.ImportScope
import scala.meta.internal.docstrings.MetalsSymbolLink
import scala.meta.internal.docstrings.printers.MarkdownGenerator
import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.mtags.JavacMtags
import scala.meta.internal.semanticdb.Scala.Descriptor
import scala.meta.internal.semanticdb.Scala.ScalaSymbolOps
import scala.meta.internal.semanticdb.Scala.Symbols
import scala.meta.pc.ContentType
import scala.meta.pc.ContentType.MARKDOWN
import scala.meta.pc.ContentType.PLAINTEXT
import scala.meta.pc.SymbolDocumentation
import scala.meta.pc.reports.ReportContext

import com.sun.source.tree.CompilationUnitTree

/**
 * Extracts Javadoc from Java source code.
 */
class JavadocIndexer(
    input: Input.VirtualFile,
    fn: SymbolDocumentation => Unit,
    contentType: ContentType
)(implicit rc: ReportContext)
    extends JavacMtags(
      input,
      includeMembers = true,
      keepDocComments = true,
      filterPrivateConstructors = true
    ) {

  /**
   * The Java file's import scope from the parsed compilation unit, so doc-link
   * resolution uses Java's implicit scope (`java.lang` only) and an unresolved
   * Java `Option` doesn't navigate to `scala.Option` (scalameta/metals#3383).
   */
  private var javaScope: ImportScope =
    ImportScope.empty

  override protected def onCompilationUnit(cu: CompilationUnitTree): Unit =
    javaScope = JavadocIndexer.importScopeOf(cu)

  override protected def onClass(
      sym: String,
      name: String,
      typeParams: List[String],
      docComment: Option[String]
  ): Unit = {
    fn(fromClass(sym, name, typeParams, docComment))
  }

  override protected def onConstructor(
      sym: String,
      params: List[String],
      typeParams: List[String],
      docComment: Option[String]
  ): Unit = {
    fn(fromConstructor(sym, params, typeParams, docComment))
  }

  override protected def onMethod(
      sym: String,
      name: String,
      params: List[String],
      typeParams: List[String],
      docComment: Option[String]
  ): Unit = {
    fn(fromMethod(sym, name, params, typeParams, docComment))
  }

  def toContent(docComment: Option[String], contextSymbol: String): String = {
    docComment match {
      case None => ""
      case Some(raw) if raw.trim.isEmpty => ""
      case Some(raw) =>
        contentType match {
          case MARKDOWN =>
            val scaladocReady = JavadocParser.toScaladocCompatible(raw)
            try
              MetalsSymbolLink.withDocScope(
                MarkdownGenerator.fromDocstring(scaladocReady, Map.empty),
                DocScope(
                  Some(contextSymbol).filter(_.nonEmpty),
                  None,
                  isJava = true,
                  javaScope,
                  // The declaration file's URI, so hover applies same-compilation-
                  // unit precedence like source go-to-definition (scalameta/metals#3383).
                  Some(input.path),
                  // Javadoc is never Scala 3 source-order.
                  docIsScala3 = false
                )
              )
            catch {
              case NonFatal(_) =>
                // The Scaladoc parser implementation uses fragile regexp processing
                // which sometimes causes exceptions.
                JavadocParser.extractBody(docComment)
            }
          case PLAINTEXT => JavadocParser.extractBody(docComment)
        }
    }
  }

  def fromMethod(
      symbol: String,
      name: String,
      params: List[String],
      typeParams: List[String],
      docComment: Option[String]
  ): SymbolDocumentation = {
    new MetalsSymbolDocumentation(
      symbol,
      name,
      // A method's relative links (`{@link #other}`) resolve against its class.
      toContent(docComment, symbol.owner),
      "",
      typeParameters(symbol, typeParams, docComment),
      parameters(symbol, params, docComment)
    )
  }
  def fromClass(
      symbol: String,
      name: String,
      typeParams: List[String],
      docComment: Option[String]
  ): SymbolDocumentation = {
    new MetalsSymbolDocumentation(
      symbol,
      name,
      toContent(docComment, symbol),
      "",
      typeParameters(symbol, typeParams, docComment),
      Nil.asJava
    )
  }
  def fromConstructor(
      symbol: String,
      params: List[String],
      typeParams: List[String],
      docComment: Option[String]
  ): SymbolDocumentation = {
    new MetalsSymbolDocumentation(
      symbol,
      "<init>",
      toContent(docComment, symbol.owner),
      "",
      typeParameters(symbol, typeParams, docComment),
      parameters(symbol, params, docComment)
    )
  }
  def param(
      symbol: String,
      name: String,
      docstring: String
  ): SymbolDocumentation =
    new MetalsSymbolDocumentation(
      symbol,
      name,
      if (docstring == null) "" else docstring,
      ""
    )
  def typeParameters(
      owner: String,
      typeParams: List[String],
      docComment: Option[String]
  ): util.List[SymbolDocumentation] = {
    val tags = JavadocParser.extractParamTags(docComment)
    typeParams.map { tparam =>
      val docstring = tags.getOrElse(s"<$tparam>", "")
      this.param(
        Symbols.Global(owner, Descriptor.TypeParameter(tparam)),
        tparam,
        docstring
      )
    }.asJava
  }
  def parameters(
      owner: String,
      params: List[String],
      docComment: Option[String]
  ): util.List[SymbolDocumentation] = {
    val tags = JavadocParser.extractParamTags(docComment)
    params.map { param =>
      val docstring = tags.getOrElse(param, "")
      this.param(
        Symbols.Global(owner, Descriptor.Parameter(param)),
        param,
        docstring
      )
    }.asJava
  }

}

object JavadocIndexer {
  def all(
      input: Input.VirtualFile,
      contentType: ContentType
  )(implicit rc: ReportContext): List[SymbolDocumentation] = {
    val buf = List.newBuilder[SymbolDocumentation]
    foreach(input, contentType)(buf += _)
    buf.result()
  }
  def foreach(
      input: Input.VirtualFile,
      contentType: ContentType
  )(fn: SymbolDocumentation => Unit)(implicit rc: ReportContext): Unit = {
    new JavadocIndexer(input, fn, contentType).indexRoot()
  }

  /**
   * The file-level import scope of a Java compilation unit: explicit single
   * imports plus on-demand (`*`) wildcards, or empty (scalameta/metals#3383).
   */
  def importScopeOf(cu: CompilationUnitTree): ImportScope = {
    val explicit = mutable.Map.empty[String, List[String]]
    val wildcards = List.newBuilder[(String, Set[String], Boolean)]
    cu.getImports().asScala.foreach { imp =>
      // `getQualifiedIdentifier.toString` renders the canonical dotted form
      // (`java.util.List`, `java.util.*`), with no comments or line breaks.
      val fqn = imp.getQualifiedIdentifier().toString()
      if (fqn.endsWith(".*")) {
        val prefix = fqn.dropRight(2)
        if (prefix.nonEmpty)
          wildcards += ((prefix, Set.empty[String], !imp.isStatic()))
      } else {
        val simple = fqn.substring(fqn.lastIndexOf('.') + 1)
        if (simple.nonEmpty)
          explicit(simple) = explicit.getOrElse(simple, Nil) :+ fqn
      }
    }
    val explicitMap = explicit.toMap
    val wildcardList = wildcards.result()
    if (explicitMap.nonEmpty || wildcardList.nonEmpty)
      ImportScope(List(ImportLevel(explicitMap, wildcardList)))
    else ImportScope.empty
  }

  /**
   * The owner symbol whose doc comment encloses `cursorOffset` (for relative
   * `{@link #m}`) and the file's import scope — what go-to-definition needs
   * inside a Java doc comment (scalameta/metals#3383).
   */
  def sourceContext(
      input: Input.VirtualFile,
      cursorOffset: Int
  )(implicit rc: ReportContext): (Option[String], ImportScope) = {
    val ctx = new JavadocContext(input, cursorOffset)
    ctx.indexRoot()
    (ctx.ownerAtCursor, ctx.imports)
  }

  private class JavadocContext(
      input: Input.VirtualFile,
      cursorOffset: Int
  )(implicit rc: ReportContext)
      extends JavacMtags(
        input,
        includeMembers = true,
        keepDocComments = false,
        filterPrivateConstructors = false
      ) {
    var imports: ImportScope = ImportScope.empty
    private val declarations = List.newBuilder[(String, Int)]

    override protected def onCompilationUnit(cu: CompilationUnitTree): Unit =
      imports = importScopeOf(cu)

    override protected def onDeclaration(
        contextOwner: String,
        startOffset: Int
    ): Unit =
      declarations += ((contextOwner, startOffset))

    def ownerAtCursor: Option[String] =
      declarations
        .result()
        .filter { case (_, start) => start >= cursorOffset }
        .sortBy { case (_, start) => start }
        .headOption
        .map { case (owner, _) => owner }
  }
}
