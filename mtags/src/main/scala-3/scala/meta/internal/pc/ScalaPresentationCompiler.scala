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
import org.eclipse.lsp4j.Range
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
import dotty.tools.dotc.printing.Texts._
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
import dotty.tools.dotc.ast.tpd._

import scala.meta.internal.pc.AutoImports._
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.semver.SemVer
import scala.meta.internal.mtags.BuildInfo
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.pc.DefinitionResultImpl
import scala.meta.internal.pc.CompilerAccess
import scala.meta.internal.tokenizers.Chars
import scala.meta.pc.VirtualFileParams
import scala.meta.pc.PresentationCompilerConfig
import scala.meta.pc.PresentationCompiler
import scala.meta.pc.SymbolSearch
import scala.meta.pc.DefinitionResult
import scala.meta.pc.OffsetParams
import scala.meta.pc.SymbolSearch

case class ScalaPresentationCompiler(
    buildTargetIdentifier: String = "",
    classpath: Seq[Path] = Nil,
    options: List[String] = Nil,
    search: SymbolSearch = EmptySymbolSearch,
    ec: ExecutionContextExecutor = ExecutionContext.global,
    sh: Option[ScheduledExecutorService] = None,
    config: PresentationCompilerConfig = PresentationCompilerConfigImpl(),
    workspace: Option[Path] = None
) extends PresentationCompiler {

  def this() = this("", Nil, Nil)

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
      val (items, isIncomplete) = driver.compilationUnits.get(uri) match {
        case Some(unit) =>
          val path =
            Interactive.pathTo(driver.openedTrees(uri), pos)(using ctx)

          val newctx = ctx.fresh.setCompilationUnit(unit)
          val tpdPath =
            Interactive.pathTo(newctx.compilationUnit.tpdTree, pos.span)(using
              newctx
            )
          val locatedCtx = Interactive.contextOfPath(tpdPath)(using newctx)
          val namesInScope =
            NamesInScope.build(unit.tpdTree)(using locatedCtx)
          val completionPos =
            CompletionPos.infer(pos, params.text, path)(using newctx)
          val (completions, searchResult) =
            CompletionProvider(
              pos,
              ctx.fresh.setCompilationUnit(unit),
              search,
              buildTargetIdentifier,
              completionPos,
              namesInScope,
              path
            )
              .completions()
          val history = ShortenedNames(locatedCtx)
          val autoImportsGen = AutoImports.generator(
            pos,
            params.text,
            unit.tpdTree,
            namesInScope,
            config
          )(using ctx)

          val items = completions.zipWithIndex.flatMap { case (item, idx) =>
            completionItems(
              item,
              history,
              idx,
              autoImportsGen,
              completionPos,
              path,
              namesInScope
            )(using newctx)
          }
          val isIncomplete = searchResult match {
            case SymbolSearch.Result.COMPLETE => false
            case SymbolSearch.Result.INCOMPLETE => true
          }
          (items, isIncomplete)
        case None => (Nil, false)
      }

      new CompletionList(
        isIncomplete,
        items.asJava
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

  def diagnosticsForDebuggingPurposes(): ju.List[String] =
    List[String]().asJava

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

  def autoImports(
      name: String,
      params: scala.meta.pc.OffsetParams
  ): CompletableFuture[
    ju.List[scala.meta.pc.AutoImportsResult]
  ] = {
    compilerAccess.withNonInterruptableCompiler(
      List.empty[scala.meta.pc.AutoImportsResult].asJava,
      params.token
    ) { access =>
      val driver = access.compiler()
      new AutoImportsProvider(search, driver, name, params, config)
        .autoImports()
        .asJava
    }
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
            val docComments =
              symbols.flatMap(ParsedComment.docOf(_)(using ctx))
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
                  driver.compilationUnits.get(uri) match {
                    case Some(unit) =>
                      val newctx =
                        ctx.fresh.setCompilationUnit(unit)
                      val tpdPath = Interactive.pathTo(
                        newctx.compilationUnit.tpdTree,
                        pos.span
                      )(using newctx)
                      val context =
                        Interactive.contextOfPath(tpdPath)(using newctx)
                      val history = new ShortenedNames(context)
                      printer.infoString(symbol, history, tpw)(using context)
                    case None => printer.typeString(tpw)
                  }
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
    copy(
      buildTargetIdentifier = buildTargetIdentifier,
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

  private def completionItems(
      completion: CompletionValue,
      history: ShortenedNames,
      idx: Int,
      autoImports: AutoImportsGenerator,
      completionPos: CompletionPos,
      path: List[Tree],
      namesInScope: NamesInScope
  )(using Context): List[CompletionItem] = {
    val printer = SymbolPrinter()(using ctx)

    /**
     * Calculate the string for "detail" field in CompletionItem.
     *
     * for class or module, it's package name that it belongs to (e.g. "scala.collection" for "scala.collection.Seq")
     * otherwise, it's shortened type/method signature
     * e.g. "[A: Ordering](x: List[Int]): A", " java.lang.String"
     *
     * @param sym The symbol for completion item.
     */
    def detailString(sym: Symbol): String = {
      if (sym.isClass || sym.is(Module)) {
        val printer = SymbolPrinter()
        s" ${printer.fullNameString(sym.owner)}"
      } else {
        printer.infoString(sym, history, sym.info.widenTermRefExpr)(using ctx)
      }
    }

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

    val rawCompletion = completion.value

    val editRange = completionPos.toEditRange

    rawCompletion.symbols.map { sym =>
      // For overloaded signatures we get multiple symbols, so we need
      // to recalculate the description
      // related issue https://github.com/lampepfl/dotty/issues/11941
      lazy val kind: CompletionItemKind = completionItemKind(sym)

      val description = detailString(sym)

      def mkItem(
          ident: String,
          value: String,
          isFromWorkspace: Boolean = false,
          additionalEdits: List[TextEdit] = Nil
      ): CompletionItem = {

        val label =
          kind match {
            case CompletionItemKind.Method =>
              s"${ident}${description}"
            case CompletionItemKind.Variable | CompletionItemKind.Field =>
              s"${ident}: ${description}"
            case CompletionItemKind.Module | CompletionItemKind.Class =>
              if (isFromWorkspace)
                s"${ident} -${description}"
              else
                s"${ident}${description}"
            case _ =>
              ident
          }
        val item = new CompletionItem(label)

        item.setSortText(f"${idx}%05d")
        item.setDetail(description)
        item.setFilterText(rawCompletion.label)

        val textEdit = new TextEdit(
          editRange,
          value
        )
        item.setTextEdit(textEdit)

        item.setAdditionalTextEdits(additionalEdits.asJava)

        val documentation = ParsedComment.docOf(sym)
        if (documentation.nonEmpty) {
          item.setDocumentation(hoverContent(None, None, documentation.toList))
        }

        if (sym.isDeprecated) {
          item.setTags(List(CompletionItemTag.Deprecated).asJava)
        }

        item.setKind(completionItemKind(sym))
        item
      }

      def mkWorkspaceItem(
          ident: String,
          value: String,
          additionalEdits: List[TextEdit] = Nil
      ): CompletionItem =
        mkItem(ident, value, isFromWorkspace = true, additionalEdits)

      val ident = rawCompletion.label
      completion match {
        case CompletionValue.Workspace(_) =>
          path match {
            case (_: Ident) :: (_: Import) :: _ =>
              mkWorkspaceItem(
                ident,
                sym.fullNameBackticked
              )
            case _ =>
              autoImports.forSymbol(sym) match {
                case Some(edits) =>
                  mkWorkspaceItem(ident, ident.backticked, edits)
                case None =>
                  val r = namesInScope.lookupSym(sym)
                  r match {
                    case NamesInScope.Result.InScope =>
                      mkItem(ident, ident.backticked)
                    case _ => mkWorkspaceItem(ident, sym.fullNameBackticked)
                  }
              }
          }
        case CompletionValue.NamedArg(_) => mkItem(ident, ident)
        case _ => mkItem(ident, ident.backticked)
      }
    }
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
    if (content.isEmpty) null
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
    val paramInfoss =
      signature.paramss.map(_.map(paramToParameterInformation))
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
}
