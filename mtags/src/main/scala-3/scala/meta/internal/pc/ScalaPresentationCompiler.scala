package scala.meta.internal.pc

import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.{util as ju}

import scala.collection.JavaConverters.*
import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutor
import scala.io.Codec
import scala.language.implicitConversions

import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.mtags.BuildInfo
import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.internal.pc.AutoImports.*
import scala.meta.internal.pc.CompilerAccess
import scala.meta.internal.pc.DefinitionResultImpl
import scala.meta.internal.semver.SemVer
import scala.meta.internal.tokenizers.Chars
import scala.meta.pc.DefinitionResult
import scala.meta.pc.OffsetParams
import scala.meta.pc.PresentationCompiler
import scala.meta.pc.PresentationCompilerConfig
import scala.meta.pc.RangeParams
import scala.meta.pc.SymbolSearch
import scala.meta.pc.VirtualFileParams

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.NameKinds.*
import dotty.tools.dotc.core.NameOps.*
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.core.SymDenotations.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.interactive.Completion
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.printing.PlainPrinter
import dotty.tools.dotc.printing.Texts.*
import dotty.tools.dotc.reporting.StoreReporter
import dotty.tools.dotc.transform.SymUtils.*
import dotty.tools.dotc.util.NoSourcePosition
import dotty.tools.dotc.util.ParsedComment
import dotty.tools.dotc.util.ScriptSourceFile
import dotty.tools.dotc.util.Signatures
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.util.SourcePosition
import dotty.tools.dotc.util.Spans
import dotty.tools.io.VirtualFile
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionItemTag
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DocumentOnTypeFormattingParams
import org.eclipse.lsp4j.DocumentRangeFormattingParams
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.FoldingRange
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.ParameterInformation
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SelectionRange
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureInformation
import org.eclipse.lsp4j.TextEdit

case class ScalaPresentationCompiler(
    buildTargetIdentifier: String = "",
    classpath: Seq[Path] = Nil,
    options: List[String] = Nil,
    search: SymbolSearch = EmptySymbolSearch,
    ec: ExecutionContextExecutor = ExecutionContext.global,
    sh: Option[ScheduledExecutorService] = None,
    config: PresentationCompilerConfig = PresentationCompilerConfigImpl(),
    workspace: Option[Path] = None
) extends PresentationCompiler:

  def this() = this("", Nil, Nil)

  import InteractiveDriver.*

  val scalaVersion = BuildInfo.scalaCompilerVersion

  private val forbiddenOptions = Set("-print-lines", "-print-tasty")
  val compilerAccess: CompilerAccess[StoreReporter, InteractiveDriver] =
    Scala3CompilerAccess(
      config,
      sh,
      () => new Scala3CompilerWrapper(newDriver)
    )(
      using ec
    )

  def newDriver: InteractiveDriver =
    val implicitSuggestionTimeout = List("-Ximport-suggestion-timeout", "0")
    val defaultFlags = List("-color:never")
    val filteredOptions = options.filterNot(forbiddenOptions)
    val settings =
      filteredOptions ::: defaultFlags ::: implicitSuggestionTimeout ::: "-classpath" :: classpath
        .mkString(
          File.pathSeparator
        ) :: Nil
    new InteractiveDriver(settings)

  override def getTasty(
      targetUri: URI,
      isHttpEnabled: Boolean
  ): CompletableFuture[String] =
    CompletableFuture.completedFuture {
      TastyUtils.getTasty(targetUri, isHttpEnabled)
    }

  def complete(params: OffsetParams): CompletableFuture[CompletionList] =
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
      val (items, isIncomplete) = driver.compilationUnits.get(uri) match
        case Some(unit) =>
          val path =
            Interactive.pathTo(driver.openedTrees(uri), pos)(using ctx)

          val newctx = ctx.fresh.setCompilationUnit(unit)
          val tpdPath =
            Interactive.pathTo(newctx.compilationUnit.tpdTree, pos.span)(using
              newctx
            )
          val locatedCtx =
            MetalsInteractive.contextOfPath(tpdPath)(using newctx)
          val indexedCtx = IndexedContext(locatedCtx)
          val completionPos =
            CompletionPos.infer(pos, params.text, path)(using newctx)
          val (completions, searchResult) =
            CompletionProvider(
              pos,
              ctx.fresh.setCompilationUnit(unit),
              search,
              buildTargetIdentifier,
              completionPos,
              indexedCtx,
              path
            )
              .completions()
          val history = ShortenedNames(indexedCtx)
          val autoImportsGen = AutoImports.generator(
            completionPos.sourcePos,
            params.text,
            unit.tpdTree,
            indexedCtx,
            config
          )

          val items = completions.zipWithIndex.map { case (item, idx) =>
            completionItems(
              item,
              history,
              idx,
              autoImportsGen,
              completionPos,
              path,
              indexedCtx
            )(using newctx)
          }
          val isIncomplete = searchResult match
            case SymbolSearch.Result.COMPLETE => false
            case SymbolSearch.Result.INCOMPLETE => true
          (items, isIncomplete)
        case None => (Nil, false)

      new CompletionList(
        isIncomplete,
        items.asJava
      )
    }

  def definition(params: OffsetParams): CompletableFuture[DefinitionResult] =
    compilerAccess.withNonInterruptableCompiler(
      DefinitionResultImpl.empty,
      params.token
    ) { access =>
      val driver = access.compiler()
      PcDefinitionProvider(driver, params, search).definitions()
    }

  def shutdown(): Unit =
    compilerAccess.shutdown()

  def restart(): Unit =
    compilerAccess.shutdownCurrentCompiler()

  def diagnosticsForDebuggingPurposes(): ju.List[String] =
    List[String]().asJava

  def semanticdbTextDocument(
      filename: URI,
      code: String
  ): CompletableFuture[Array[Byte]] =
    compilerAccess.withNonInterruptableCompiler(
      Array.empty[Byte],
      EmptyCancelToken
    ) { access =>
      val driver = access.compiler()
      val provider = SemanticdbTextDocumentProvider(driver, workspace)
      provider.textDocument(filename, code)
    }

  // TODO NOT IMPLEMENTED
  def completionItemResolve(
      item: CompletionItem,
      symbol: String
  ): CompletableFuture[CompletionItem] =
    CompletableFuture.completedFuture(
      null
    )

  def autoImports(
      name: String,
      params: scala.meta.pc.OffsetParams
  ): CompletableFuture[
    ju.List[scala.meta.pc.AutoImportsResult]
  ] =
    compilerAccess.withNonInterruptableCompiler(
      List.empty[scala.meta.pc.AutoImportsResult].asJava,
      params.token
    ) { access =>
      val driver = access.compiler()
      new AutoImportsProvider(search, driver, name, params, config)
        .autoImports()
        .asJava
    }

  // TODO NOT IMPLEMENTED
  def implementAbstractMembers(
      params: OffsetParams
  ): CompletableFuture[ju.List[TextEdit]] =
    CompletableFuture.completedFuture(
      List.empty[TextEdit].asJava
    )

  override def insertInferredType(
      params: OffsetParams
  ): CompletableFuture[ju.List[TextEdit]] =
    val empty: ju.List[TextEdit] = new ju.ArrayList[TextEdit]()
    compilerAccess.withInterruptableCompiler(empty, params.token) { pc =>
      new InferredTypeProvider(params, pc.compiler(), config)
        .inferredTypeEdits()
        .asJava
    }

  override def selectionRange(
      params: ju.List[OffsetParams]
  ): CompletableFuture[ju.List[SelectionRange]] =
    CompletableFuture.completedFuture {
      compilerAccess.withSharedCompiler(List.empty[SelectionRange].asJava) {
        pc =>
          new SelectionRangeProvider(
            pc.compiler(),
            params
          ).selectionRange().asJava
      }
    }
  end selectionRange

  def expandRangeToEnclosingApply(
      path: List[Tree],
      pos: SourcePosition
  )(using Context): List[Tree] =
    def tryTail(enclosing: List[Tree]): Option[List[Tree]] =
      enclosing match
        case Nil => None
        case head :: tail =>
          head match
            case t: GenericApply
                if t.fun.srcPos.span.contains(pos.span) && !t.tpe.isErroneous =>
              tryTail(tail).orElse(Some(enclosing))
            case New(_) =>
              tail match
                case Nil => None
                case Select(_, _) :: next =>
                  tryTail(next)
                case _ =>
                  None
            case _ =>
              None
    path match
      case head :: tail =>
        tryTail(tail).getOrElse(path)
      case _ =>
        List(EmptyTree)
  end expandRangeToEnclosingApply

  def hover(params: OffsetParams): CompletableFuture[ju.Optional[Hover]] =
    compilerAccess.withNonInterruptableCompiler(
      ju.Optional.empty[Hover](),
      params.token
    ) { access =>
      val driver = access.compiler()
      val uri = params.uri
      val sourceFile = CompilerInterfaces.toSource(params.uri, params.text)
      driver.run(uri, sourceFile)

      given ctx: Context = driver.currentCtx
      val pos = sourcePosition(driver, params, uri)
      val trees = driver.openedTrees(uri)

      def typeFromPath(path: List[Tree]) =
        if path.isEmpty then NoType else path.head.tpe

      val path = Interactive.pathTo(trees, pos)
      val tp = typeFromPath(path)
      val tpw = tp.widenTermRefExpr
      // For expression we need to find all enclosing applies to get the exact generic type
      val enclosing = expandRangeToEnclosingApply(path, pos)
      val exprTp = typeFromPath(enclosing)
      val exprTpw = exprTp.widenTermRefExpr

      if tp.isError || tpw == NoType || tpw.isError || path.isEmpty then
        ju.Optional.empty()
      else
        Interactive.enclosingSourceSymbols(enclosing, pos) match
          case Nil =>
            ju.Optional.empty()
          case symbols @ (symbol :: _) =>
            val printer = SymbolPrinter()
            val docComments =
              symbols.flatMap(ParsedComment.docOf(_))
            val history = driver.compilationUnits.get(uri) match
              case Some(unit) =>
                val newctx =
                  ctx.fresh.setCompilationUnit(unit)
                val context =
                  MetalsInteractive.contextOfPath(enclosing)(using newctx)
                new ShortenedNames(IndexedContext(context))
              case None => new ShortenedNames(IndexedContext(ctx))
            val hoverString =
              tpw match
                // https://github.com/lampepfl/dotty/issues/8891
                case tpw: ImportType =>
                  printer.hoverDetailString(
                    symbol,
                    history,
                    symbol.paramRef
                  )
                case _ =>
                  printer.hoverDetailString(symbol, history, tpw)
              end match

            val docString =
              docComments.map(_.renderAsMarkdown).mkString("\n")
            val expressionType = printer.expressionTypeString(exprTpw, history)
            val content = HoverMarkup(
              expressionType,
              hoverString,
              docString,
              !pos.span.isZeroExtent
            )
            ju.Optional.of(new Hover(content.toMarkupContent))

      end if
    }
  end hover

  def newInstance(
      buildTargetIdentifier: String,
      classpath: ju.List[Path],
      options: ju.List[String]
  ): PresentationCompiler =
    copy(
      buildTargetIdentifier = buildTargetIdentifier,
      classpath = classpath.asScala.toSeq,
      options = options.asScala.toList
    )

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
  ): CompletableFuture[ju.List[Diagnostic]] =
    CompletableFuture.completedFuture(Nil.asJava)

  override def didClose(uri: URI): Unit =
    compilerAccess.withNonInterruptableCompiler(
      (),
      EmptyCancelToken
    ) { access => access.compiler().close(uri) }

  override def withExecutorService(
      executorService: ExecutorService
  ): PresentationCompiler =
    copy(ec = ExecutionContext.fromExecutorService(executorService))

  override def withConfiguration(
      config: PresentationCompilerConfig
  ): PresentationCompiler =
    copy(config = config)

  override def withScheduledExecutorService(
      sh: ScheduledExecutorService
  ): PresentationCompiler =
    copy(sh = Some(sh))

  def withSearch(search: SymbolSearch): PresentationCompiler =
    copy(search = search)

  def withWorkspace(workspace: Path): PresentationCompiler =
    copy(workspace = Some(workspace))

  private def sourcePosition(
      driver: InteractiveDriver,
      params: OffsetParams,
      uri: URI
  ): SourcePosition =
    val source = driver.openedFiles(uri)
    val span = params match
      case p: RangeParams if p.offset != p.endOffset =>
        p.trimWhitespaceInRange.fold {
          Spans.Span(p.offset, p.endOffset)
        } {
          case trimmed: RangeParams =>
            Spans.Span(trimmed.offset, trimmed.endOffset)
          case offset =>
            Spans.Span(p.offset, p.offset)
        }
      case _ => Spans.Span(params.offset)

    new SourcePosition(source, span)
  end sourcePosition

  private def range(p: SourcePosition): Option[Range] =
    if p.exists then
      Some(
        new Range(
          new Position(
            p.startLine,
            p.startColumn
          ),
          new Position(p.endLine, p.endColumn)
        )
      )
    else None

  private def completionItems(
      completion: CompletionValue,
      history: ShortenedNames,
      idx: Int,
      autoImports: AutoImportsGenerator,
      completionPos: CompletionPos,
      path: List[Tree],
      indexedContext: IndexedContext
  )(using Context): CompletionItem =
    val printer = SymbolPrinter()(using ctx)

    def completionItemKind(
        sym: Symbol
    )(using ctx: Context): CompletionItemKind =
      if sym.is(Package) || sym.is(Module) then
        CompletionItemKind.Module // No CompletionItemKind.Package (https://github.com/Microsoft/language-server-protocol/issues/155)
      else if sym.isConstructor then CompletionItemKind.Constructor
      else if sym.isClass then CompletionItemKind.Class
      else if sym.is(Mutable) then CompletionItemKind.Variable
      else if sym.is(Method) then CompletionItemKind.Method
      else CompletionItemKind.Field

    val editRange = completionPos.toEditRange
    val sym = completion.symbol

    // For overloaded signatures we get multiple symbols, so we need
    // to recalculate the description
    // related issue https://github.com/lampepfl/dotty/issues/11941
    lazy val kind: CompletionItemKind = completionItemKind(sym)

    val description = printer.completionDetailString(sym, history)

    def mkItem0(
        ident: String,
        nameEdit: TextEdit,
        isFromWorkspace: Boolean = false,
        additionalEdits: List[TextEdit] = Nil
    ): CompletionItem =

      val label =
        kind match
          case CompletionItemKind.Method =>
            s"${ident}${description}"
          case CompletionItemKind.Variable | CompletionItemKind.Field =>
            s"${ident}: ${description}"
          case CompletionItemKind.Module | CompletionItemKind.Class =>
            if isFromWorkspace then s"${ident} -${description}"
            else s"${ident}${description}"
          case _ =>
            ident
      val item = new CompletionItem(label)

      item.setSortText(f"${idx}%05d")
      item.setDetail(description)
      item.setFilterText(completion.label)

      item.setTextEdit(nameEdit)

      item.setAdditionalTextEdits(additionalEdits.asJava)

      val documentation = ParsedComment.docOf(sym)
      if documentation.nonEmpty then
        item.setDocumentation(hoverContent(None, documentation.toList))

      if sym.isDeprecated then
        item.setTags(List(CompletionItemTag.Deprecated).asJava)

      item.setKind(completionItemKind(sym))
      item
    end mkItem0

    def mkItem(
        ident: String,
        value: String,
        isFromWorkspace: Boolean = false,
        additionalEdits: List[TextEdit] = Nil
    ): CompletionItem =
      val nameEdit = new TextEdit(
        editRange,
        value
      )
      mkItem0(ident, nameEdit, isFromWorkspace, additionalEdits)

    def mkWorkspaceItem(
        ident: String,
        value: String,
        additionalEdits: List[TextEdit] = Nil
    ): CompletionItem =
      mkItem(ident, value, isFromWorkspace = true, additionalEdits)

    val ident = completion.label
    completion.kind match
      case CompletionValue.Kind.Workspace =>
        path match
          case (_: Ident) :: (_: Import) :: _ =>
            mkWorkspaceItem(
              ident,
              sym.fullNameBackticked
            )
          case _ =>
            autoImports.editsForSymbol(sym) match
              case Some(edits) =>
                edits match
                  case AutoImportEdits(Some(nameEdit), other) =>
                    mkItem0(
                      ident,
                      nameEdit,
                      isFromWorkspace = true,
                      other.toList
                    )
                  case _ =>
                    mkItem(
                      ident,
                      ident.backticked,
                      isFromWorkspace = true,
                      edits.edits
                    )
              case None =>
                val r = indexedContext.lookupSym(sym)
                r match
                  case IndexedContext.Result.InScope =>
                    mkItem(ident, ident.backticked)
                  case _ => mkWorkspaceItem(ident, sym.fullNameBackticked)
      case CompletionValue.Kind.NamedArg => mkItem(ident, ident)
      case _ => mkItem(ident, ident.backticked)
    end match
  end completionItems

  private def hoverContent(
      typeInfo: Option[String],
      comments: List[ParsedComment]
  )(using ctx: Context): MarkupContent =
    val buf = new StringBuilder
    typeInfo.foreach { info =>
      buf.append(s"""```scala
                    |$info
                    |```
                    |""".stripMargin)
    }
    comments.foreach { comment => buf.append(comment.renderAsMarkdown) }
    markupContent(buf.toString)
  end hoverContent

  private def markupContent(content: String): MarkupContent =
    if content.isEmpty then null
    else
      val markup = new MarkupContent
      markup.setKind("markdown")
      markup.setValue(content.trim)
      markup

  def signatureToSignatureInformation(
      signature: Signatures.Signature
  ): SignatureInformation =
    val paramInfoss =
      signature.paramss.map(_.map(paramToParameterInformation))
    val paramLists = signature.paramss
      .map { paramList =>
        val labels = paramList.map(_.show)
        val prefix = if paramList.exists(_.isImplicit) then "implicit " else ""
        labels.mkString(prefix, ", ", "")
      }
      .mkString("(", ")(", ")")
    val tparamsLabel =
      if signature.tparams.isEmpty then ""
      else signature.tparams.mkString("[", ", ", "]")
    val returnTypeLabel = signature.returnType.map(t => s": $t").getOrElse("")
    val label = s"${signature.name}$tparamsLabel$paramLists$returnTypeLabel"
    val documentation = signature.doc.map(markupContent)
    val sig = new SignatureInformation(label)
    sig.setParameters(paramInfoss.flatten.asJava)
    documentation.foreach(sig.setDocumentation(_))
    sig
  end signatureToSignatureInformation

  /**
   * Convert `param` to `ParameterInformation`
   */
  private def paramToParameterInformation(
      param: Signatures.Param
  ): ParameterInformation =
    val documentation = param.doc.map(markupContent)
    val info = new ParameterInformation(param.show)
    documentation.foreach(info.setDocumentation(_))
    info

  override def isLoaded() = compilerAccess.isLoaded()

end ScalaPresentationCompiler
