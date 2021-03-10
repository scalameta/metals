package scala.meta.internal.pc

import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.CompletableFuture
import java.util.Optional
import java.io.File
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.file.Path
import java.nio.file.Paths
import java.net.URI
import java.{util => ju}
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutor
import scala.io.Codec
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.language.implicitConversions
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionItemTag
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureInformation
import org.eclipse.lsp4j.ParameterInformation
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.FoldingRange
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentOnTypeFormattingParams
import org.eclipse.lsp4j.DocumentRangeFormattingParams
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.DiagnosticSeverity

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.core.Contexts._
import dotty.tools.dotc.core.Symbols._
import dotty.tools.dotc.core.Names._
import dotty.tools.dotc.core.Types._
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.SymDenotations._
import dotty.tools.dotc.core.NameOps._
import dotty.tools.dotc.core.NameKinds._
import dotty.tools.dotc.core.Flags._
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.interactive.Completion
import dotty.tools.dotc.printing.PlainPrinter
import dotty.tools.dotc.reporting.StoreReporter
import dotty.tools.dotc.transform.SymUtils._
import dotty.tools.dotc.util.SourcePosition
import dotty.tools.dotc.util.NoSourcePosition
import dotty.tools.dotc.util.Spans
import dotty.tools.dotc.util.ParsedComment
import dotty.tools.dotc.util.Signatures
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.util.ScriptSourceFile
import dotty.tools.io.VirtualFile

import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.semver.SemVer
import scala.meta.internal.mtags.BuildInfo
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.pc.DefinitionResultImpl
import scala.meta.internal.pc.CompilerAccess
import scala.meta.pc.VirtualFileParams
import scala.meta.pc.PresentationCompilerConfig
import scala.meta.pc.PresentationCompiler
import scala.meta.pc.SymbolSearch
import scala.meta.pc.DefinitionResult
import scala.meta.pc.OffsetParams

case class ScalaPresentationCompiler(
    classpath: Seq[Path] = Nil,
    options: List[String] = Nil,
    search: SymbolSearch = EmptySymbolSearch,
    ec: ExecutionContextExecutor = ExecutionContext.global,
    sh: Option[ScheduledExecutorService] = None,
    config: PresentationCompilerConfig = PresentationCompilerConfigImpl(),
    workspace: Option[Path] = None
) extends PresentationCompiler
    with Completions {

  def this() = this(Nil, Nil)

  import InteractiveDriver._

  val scalaVersion = BuildInfo.scalaCompilerVersion

  val compilerAccess: CompilerAccess[StoreReporter, InteractiveDriver] = {
    Scala3CompilerAccess(
      config,
      sh,
      () => new Scala3CompilerWrapper(newDriver)
    )(
      using ec
    )
  }

  def newDriver: InteractiveDriver = {
    val implicitSuggestionTimeout = List("-Ximport-suggestion-timeout", "0")
    val defaultFlags = List("-color:never")
    val settings =
      options ::: defaultFlags ::: implicitSuggestionTimeout ::: "-classpath" :: classpath
        .mkString(
          File.pathSeparator
        ) :: Nil
    new InteractiveDriver(settings)
  }

  def complete(params: OffsetParams): CompletableFuture[CompletionList] = {
    compilerAccess.withInterruptableCompiler(
      EmptyCompletionList(),
      params.token
    ) { access =>
      val driver = access.compiler()
      val uri = params.uri
      val sourceFile = CompilerInterfaces.toSource(params.uri, params.text)
      driver.run(uri, sourceFile)

      val ctx = driver.currentCtx
      val pos = sourcePosition(driver, params, uri)
      val items = driver.compilationUnits.get(uri) match {
        case Some(unit) =>
          val path = Interactive.pathTo(driver.openedTrees(uri), pos)(using ctx)
          val completions =
            CompletionProvider(pos, ctx.fresh.setCompilationUnit(unit))
              .completions()
          val metalsCompletions = completionPosition(pos, path)(using ctx)
          completions ++ metalsCompletions
        case None => Nil
      }
      new CompletionList(
        /*isIncomplete = */ false,
        items.zipWithIndex.map { case (item, idx) =>
          completionItem(item, idx)(using ctx)
        }.asJava
      )
    }
  }

  def definition(params: OffsetParams): CompletableFuture[DefinitionResult] = {
    compilerAccess.withNonInterruptableCompiler(
      DefinitionResultImpl.empty,
      params.token
    ) { access =>
      val driver = access.compiler()
      val ctx = driver.currentCtx
      val uri = params.uri
      val sourceFile = CompilerInterfaces.toSource(params.uri, params.text)
      driver.run(uri, sourceFile)
      val pos = sourcePosition(driver, params, uri)
      val path = Interactive.pathTo(driver.openedTrees(uri), pos)(using ctx)
      val definitions = Interactive.findDefinitions(path, pos, driver).toList

      DefinitionResultImpl(
        "",
        definitions.flatMap(d => location(d.namePos(using ctx))).asJava
      )

    }
  }

  def shutdown(): Unit = {
    compilerAccess.shutdown()
  }

  def restart(): Unit = {
    compilerAccess.shutdownCurrentCompiler()
  }

  def diagnosticsForDebuggingPurposes(): ju.List[String] = List[String]().asJava

  def semanticdbTextDocument(
      filename: URI,
      code: String
  ): CompletableFuture[Array[Byte]] = {
    compilerAccess.withNonInterruptableCompiler(
      Array.empty[Byte],
      EmptyCancelToken
    ) { access =>
      val driver = access.compiler()
      val provider = SemanticdbTextDocumentProvider(driver, workspace)
      provider.textDocument(filename, code)
    }
  }

  // TODO NOT IMPLEMENTED
  def completionItemResolve(
      item: CompletionItem,
      symbol: String
  ): CompletableFuture[CompletionItem] = {
    CompletableFuture.completedFuture(
      null
    )
  }

  // TODO NOT IMPLEMENTED
  def autoImports(
      file: String,
      params: scala.meta.pc.OffsetParams
  ): CompletableFuture[
    ju.List[scala.meta.pc.AutoImportsResult]
  ] = {
    CompletableFuture.completedFuture(
      List.empty[scala.meta.pc.AutoImportsResult].asJava
    )
  }

  // TODO NOT IMPLEMENTED
  def implementAbstractMembers(
      params: OffsetParams
  ): CompletableFuture[ju.List[TextEdit]] = {
    CompletableFuture.completedFuture(
      List.empty[TextEdit].asJava
    )
  }

  // TODO NOT IMPLEMENTED
  override def insertInferredType(
      params: OffsetParams
  ): CompletableFuture[ju.List[TextEdit]] = {
    CompletableFuture.completedFuture(
      List.empty[TextEdit].asJava
    )
  }

  def hover(params: OffsetParams): CompletableFuture[ju.Optional[Hover]] =
    compilerAccess.withNonInterruptableCompiler(
      ju.Optional.empty[Hover](),
      params.token
    ) { access =>
      val driver = access.compiler()
      val uri = params.uri
      val sourceFile = CompilerInterfaces.toSource(params.uri, params.text)
      driver.run(uri, sourceFile)

      val ctx = driver.currentCtx
      val pos = sourcePosition(driver, params, uri)
      val trees = driver.openedTrees(uri)
      val path = Interactive.pathTo(trees, pos)(using ctx)
      val tp = Interactive.enclosingType(trees, pos)(using ctx)
      val tpw = tp.widenTermRefExpr(using ctx)

      if (tp.isError(using ctx) || tpw == NoType || tpw.isError(using ctx))
        ju.Optional.empty()
      else {
        Interactive.enclosingSourceSymbols(path, pos)(using ctx) match {
          case Nil =>
            ju.Optional.empty()
          case symbols =>
            val printer = SymbolPrinter()(using ctx)
            val docComments = symbols.flatMap(ParsedComment.docOf(_)(using ctx))
            val keywordName = symbols.headOption.map { symbol =>
              printer.fullDefinition(
                symbol,
                tpw
              )
            }
            val typeString = symbols.headOption.map { symbol =>
              tpw match {
                // https://github.com/lampepfl/dotty/issues/8891
                case _: ImportType =>
                  printer.typeString(symbol.paramRef(using ctx))
                case _ =>
                  val shortendType = driver.compilationUnits.get(uri) match {
                    case Some(unit) =>
                      val newctx = ctx.fresh.setCompilationUnit(unit)
                      val path = Interactive.pathTo(
                        newctx.compilationUnit.tpdTree,
                        pos.span
                      )(using newctx)
                      val context =
                        Interactive.contextOfPath(path)(using newctx)
                      val history = ShortenedNames(context)
                      shortType(tpw, history)(using newctx)
                    case None => tpw
                  }
                  printer.typeString(shortendType)
              }
            }
            val content = hoverContent(
              keywordName,
              typeString,
              docComments
            )(using ctx)
            ju.Optional.of(new Hover(content))
        }
      }
    }

  def newInstance(
      buildTargetIdentifier: String,
      classpath: ju.List[Path],
      options: ju.List[String]
  ): PresentationCompiler = {
    new ScalaPresentationCompiler(
      classpath = classpath.asScala.toSeq,
      options = options.asScala.toList
    )
  }

  def signatureHelp(params: OffsetParams): CompletableFuture[SignatureHelp] =
    compilerAccess.withNonInterruptableCompiler(
      new SignatureHelp(),
      params.token
    ) { access =>
      val driver = access.compiler()
      val uri = params.uri
      val sourceFile = CompilerInterfaces.toSource(params.uri, params.text)
      driver.run(uri, sourceFile)

      val ctx = driver.currentCtx

      val pos = sourcePosition(driver, params, uri)
      val trees = driver.openedTrees(uri)

      // @tgodzik tpd.TypeApply doesn't seem to be handled here
      val path =
        Interactive
          .pathTo(trees, pos)(using ctx)
          .dropWhile(!_.isInstanceOf[tpd.Apply])

      val (paramN, callableN, alternatives) =
        Signatures.callInfo(path, pos.span)(using ctx)

      val signatureInfos =
        alternatives.flatMap(Signatures.toSignature(_)(using ctx))
      new SignatureHelp(
        signatureInfos.map(signatureToSignatureInformation).asJava,
        callableN,
        paramN
      )
    }

  override def didChange(
      params: VirtualFileParams
  ): CompletableFuture[ju.List[Diagnostic]] = {
    compilerAccess.withNonInterruptableCompiler(
      Nil.asJava,
      params.token
    ) { access =>
      val driver = access.compiler()
      val uri = params.uri
      CompilerInterfaces.parseErrors(driver, uri, params.text)
    }
  }

  override def didClose(uri: URI): Unit = {
    compilerAccess.withNonInterruptableCompiler(
      (),
      EmptyCancelToken
    ) { access => access.compiler().close(uri) }
  }

  override def withExecutorService(
      executorService: ExecutorService
  ): PresentationCompiler = {
    copy(ec = ExecutionContext.fromExecutorService(executorService))
  }

  override def withConfiguration(
      config: PresentationCompilerConfig
  ): PresentationCompiler = {
    copy(config = config)
  }

  override def withScheduledExecutorService(
      sh: ScheduledExecutorService
  ): PresentationCompiler =
    copy(sh = Some(sh))

  def withSearch(search: SymbolSearch): PresentationCompiler = {
    copy(search = search)
  }

  def withWorkspace(workspace: Path): PresentationCompiler = {
    copy(workspace = Some(workspace))
  }

  private def location(p: SourcePosition): Option[Location] = {
    for {
      uri <- toUriOption(p.source)
      r <- range(p)
    } yield new Location(uri.toString, r)
  }

  private def sourcePosition(
      driver: InteractiveDriver,
      params: OffsetParams,
      uri: URI
  ): SourcePosition = {
    val source = driver.openedFiles(uri)
    val p = Spans.Span(params.offset)
    new SourcePosition(source, p)
  }

  private def range(p: SourcePosition): Option[Range] = {
    if (p.exists) {
      Some(
        new Range(
          new Position(
            p.startLine,
            p.startColumn
          ),
          new Position(p.endLine, p.endColumn)
        )
      )
    } else {
      None
    }
  }

  private def completionItem(
      completion: Completion,
      idx: Int
  )(using ctx: Context): CompletionItem = {
    def completionItemKind(
        sym: Symbol
    )(using ctx: Context): CompletionItemKind = {
      if (sym.is(Package) || sym.is(Module))
        CompletionItemKind.Module // No CompletionItemKind.Package (https://github.com/Microsoft/language-server-protocol/issues/155)
      else if (sym.isConstructor)
        CompletionItemKind.Constructor
      else if (sym.isClass)
        CompletionItemKind.Class
      else if (sym.is(Mutable))
        CompletionItemKind.Variable
      else if (sym.is(Method))
        CompletionItemKind.Method
      else
        CompletionItemKind.Field
    }
    val colonNotNeeded = completion.symbols.headOption.exists(_.is(Method))
    val colon = if (colonNotNeeded) "" else ": "
    val label = s"${completion.label}$colon${completion.description}"
    val item = new CompletionItem(label)

    item.setSortText(f"${idx}%05d")

    item.setFilterText(completion.label)
    // TODO we should use edit text
    item.setInsertText(completion.label)
    val documentation = for {
      sym <- completion.symbols
      doc <- ParsedComment.docOf(sym)
    } yield doc

    if (documentation.nonEmpty) {
      item.setDocumentation(hoverContent(None, None, documentation))
    }

    if (completion.symbols.forall(_.isDeprecated)) {
      item.setTags(List(CompletionItemTag.Deprecated).asJava)
    }

    completion.symbols.headOption
      .foreach(s => item.setKind(completionItemKind(s)))
    item
  }

  private def hoverContent(
      keywordName: Option[String],
      typeInfo: Option[String],
      comments: List[ParsedComment]
  )(using ctx: Context): MarkupContent = {
    val buf = new StringBuilder
    typeInfo.foreach { info =>
      val keyName = keywordName.getOrElse("")
      buf.append(s"""```scala
                    |$keyName$info
                    |```
                    |""".stripMargin)
    }
    comments.foreach { comment => buf.append(comment.renderAsMarkdown) }

    markupContent(buf.toString)
  }

  private def markupContent(content: String): MarkupContent = {
    if (content.isEmpty)
      null
    else {
      val markup = new MarkupContent
      markup.setKind("markdown")
      markup.setValue(content.trim)
      markup
    }
  }

  def signatureToSignatureInformation(
      signature: Signatures.Signature
  ): SignatureInformation = {
    val paramInfoss = signature.paramss.map(_.map(paramToParameterInformation))
    val paramLists = signature.paramss
      .map { paramList =>
        val labels = paramList.map(_.show)
        val prefix = if (paramList.exists(_.isImplicit)) "implicit " else ""
        labels.mkString(prefix, ", ", "")
      }
      .mkString("(", ")(", ")")
    val tparamsLabel =
      if (signature.tparams.isEmpty) ""
      else signature.tparams.mkString("[", ", ", "]")
    val returnTypeLabel = signature.returnType.map(t => s": $t").getOrElse("")
    val label = s"${signature.name}$tparamsLabel$paramLists$returnTypeLabel"
    val documentation = signature.doc.map(markupContent)
    val sig = new SignatureInformation(label)
    sig.setParameters(paramInfoss.flatten.asJava)
    documentation.foreach(sig.setDocumentation(_))
    sig
  }

  /**
   * Convert `param` to `ParameterInformation`
   */
  private def paramToParameterInformation(
      param: Signatures.Param
  ): ParameterInformation = {
    val documentation = param.doc.map(markupContent)
    val info = new ParameterInformation(param.show)
    documentation.foreach(info.setDocumentation(_))
    info
  }

  override def isLoaded() = compilerAccess.isLoaded()

  private case class ShortName(
      name: Name,
      symbol: Symbol
  )
  private object ShortName {
    def apply(sym: Symbol)(using ctx: Context): ShortName =
      ShortName(sym.name, sym)
  }

  private class ShortenedNames(context: Context) {
    val history = collection.mutable.Map.empty[Name, ShortName]

    def lookupSymbol(short: ShortName): Type = {
      context.findRef(short.name)
    }

    def tryShortenName(short: ShortName)(using Context): Boolean = {
      history.get(short.name) match {
        case Some(ShortName(_, other)) => true
        case None =>
          val foundTpe = lookupSymbol(short)
          val syms = List(foundTpe.termSymbol, foundTpe.typeSymbol)
          val isOk = syms.filter(_ != NoSymbol) match {
            case Nil => false
            case founds => founds.exists(_ == short.symbol)
          }
          if (isOk) {
            history(short.name) = short
            true
          } else {
            false
          }
      }
    }

    def tryShortenName(name: Option[ShortName])(using Context): Boolean =
      name match {
        case Some(short) => tryShortenName(short)
        case None => false
      }
  }

  /**
   * Shorten the long (fully qualified) type to shorter representation, so printers
   * can obtain more readable form of type like `SrcPos` instead of `dotc.util.SrcPos`
   * (if the name can be resolved from the context).
   *
   * For example,
   * when the longType is like `TypeRef(TermRef(ThisType(TypeRef(NoPrefix,module class dotc)),module util),SrcPos)`,
   * if `dotc.util.SrcPos` found from the scope, then `TypeRef(NoPrefix, SrcPos)`
   * if not, and `dotc.util` found from the scope then `TypeRef(TermRef(NoPrefix, module util), SrcPos)`
   *
   * @see Scala 3/Internals/Type System https://dotty.epfl.ch/docs/internals/type-system.html
   */
  private def shortType(longType: Type, history: ShortenedNames)(using
      ctx: Context
  ): Type = {
    val isVisited = collection.mutable.Set.empty[(Type, Option[ShortName])]
    val cached = new ju.HashMap[(Type, Option[ShortName]), Type]()

    def loop(tpe: Type, name: Option[ShortName]): Type = {
      val key = tpe -> name
      // NOTE: Prevent infinite recursion, see https://github.com/scalameta/metals/issues/749
      if (isVisited(key)) return cached.getOrDefault(key, tpe)
      isVisited += key
      val result = tpe match {
        case TypeRef(prefix, designator) =>
          // designator is not necessarily an instance of `Symbol` and it's an instance of `Name`
          // this can be seen, for example, when we are shortening the signature of 3rd party APIs.
          val sym =
            if (designator.isInstanceOf[Symbol]) designator.asInstanceOf[Symbol]
            else tpe.typeSymbol
          val short = ShortName(sym)
          TypeRef(loop(prefix, Some(short)), sym)

        case TermRef(prefix, designator) =>
          val sym =
            if (designator.isInstanceOf[Symbol]) designator.asInstanceOf[Symbol]
            else tpe.termSymbol
          val short = ShortName(sym)
          if (history.tryShortenName(name)) NoPrefix
          else TermRef(loop(prefix, None), sym)

        case ThisType(tyref) =>
          if (history.tryShortenName(name)) NoPrefix
          else ThisType.raw(loop(tyref, None).asInstanceOf[TypeRef])

        case mt @ MethodTpe(pnames, ptypes, restpe) if mt.isImplicitMethod =>
          ImplicitMethodType(
            pnames,
            ptypes.map(loop(_, None)),
            loop(restpe, None)
          )
        case mt @ MethodTpe(pnames, ptypes, restpe) =>
          MethodType(pnames, ptypes.map(loop(_, None)), loop(restpe, None))

        case pl @ PolyType(_, restpe) =>
          PolyType(
            pl.paramNames,
            pl.paramInfos.map(bound =>
              TypeBounds(loop(bound.lo, None), loop(bound.hi, None))
            ),
            loop(restpe, None)
          )
        case ConstantType(value) => value.tpe
        case SuperType(thistpe, supertpe) =>
          SuperType(loop(thistpe, None), loop(supertpe, None))
        case AppliedType(tycon, args) =>
          AppliedType(loop(tycon, None), args.map(a => loop(a, None)))
        case TypeBounds(lo, hi) =>
          TypeBounds(loop(lo, None), loop(hi, None))
        case ExprType(res) =>
          ExprType(loop(res, None))
        case AnnotatedType(parent, annot) =>
          AnnotatedType(loop(parent, None), annot)
        case AndType(tp1, tp2) =>
          AndType(loop(tp1, None), loop(tp2, None))
        case or @ OrType(tp1, tp2) =>
          OrType(loop(tp1, None), loop(tp2, None), or.isSoft)
        case t => t
      }

      cached.putIfAbsent(key, result)
      result
    }
    loop(longType, None)
  }
}
