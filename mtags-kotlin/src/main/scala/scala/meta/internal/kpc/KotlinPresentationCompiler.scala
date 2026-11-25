package scala.meta.internal.kpc

import java.lang
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import java.util
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.function.Supplier

import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

import scala.meta.internal.pc.DefinitionResultImpl
import scala.meta.internal.pc.EmptySymbolSearch
import scala.meta.internal.pc.PresentationCompilerConfigImpl
import scala.meta.pc.AutoImportsResult
import scala.meta.pc.DefinitionResult
import scala.meta.pc.HoverSignature
import scala.meta.pc.OffsetParams
import scala.meta.pc.PresentationCompiler
import scala.meta.pc.PresentationCompilerConfig
import scala.meta.pc.RangeParams
import scala.meta.pc.SymbolSearch
import scala.meta.pc.VirtualFileParams

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.util.PsiTreeUtil
import org.eclipse.lsp4j
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.SelectionRange
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.TextEdit
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.KaCompletionExtensionCandidateChecker
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.components.KaExtensionApplicabilityResult
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity
import org.jetbrains.kotlin.analysis.api.projectStructure.DanglingFilesKt
import org.jetbrains.kotlin.analysis.api.renderer.declarations.impl.KaDeclarationRendererForSource
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.scopes.KaScope
import org.jetbrains.kotlin.analysis.api.session.AnalyzeKt
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassifierSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPackageSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolVisibility
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.idea.references.ReferenceUtilsKt
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.renderer.RenderingUtilsKt
import org.jetbrains.kotlin.types.Variance

/**
 * Presentation compiler for Kotlin on the Kotlin Analysis API (K2) in
 * standalone mode. Supports hover, definition, completion and diagnostics.
 */
case class KotlinPresentationCompiler(
    override val buildTargetId: String = "",
    classpath: Seq[Path] = Nil,
    sources: Supplier[util.List[Path]] = () => util.Collections.emptyList(),
    search: SymbolSearch = EmptySymbolSearch,
    config: PresentationCompilerConfig = PresentationCompilerConfigImpl()
) extends PresentationCompiler {

  def this() = this(buildTargetId = "")

  /**
   * The classpath with kotlin-stdlib. A target without it (a fallback, a
   * Java target) gets the stdlib that this compiler runs with.
   */
  private lazy val fullClasspath: Seq[Path] =
    if (classpath.exists(_.getFileName.toString.startsWith("kotlin-stdlib")))
      classpath
    else
      classpath :+ Paths.get(
        classOf[kotlin.Unit].getProtectionDomain.getCodeSource.getLocation.toURI
      )

  private lazy val session = new KotlinCompilerSession(
    fullClasspath,
    () => sources.get().asScala.toSeq,
    Paths.get(System.getProperty("java.home"))
  )

  private lazy val libraryNames = new LibraryNames(fullClasspath)

  private def async[T](default: T)(f: => T): CompletableFuture[T] =
    CompletableFuture.supplyAsync { () =>
      try f
      catch { case NonFatal(_) => default }
    }

  private def onFile[T](params: VirtualFileParams, default: T)(
      f: (KtFile, KotlinCompilerSession#Snapshot) => T
  ): CompletableFuture[T] = {
    val path = Paths.get(params.uri())
    session.update(path, params.text())
    async(default)(session.withFile(path, params.text())(f).getOrElse(default))
  }

  private def analyze[T](file: KtFile)(f: KaSession => T): T =
    AnalyzeKt.analyze(file, Kt.fn[KaSession, T](f))

  // ---------------------------------------------------------------- hover

  override def hover(
      params: OffsetParams
  ): CompletableFuture[Optional[HoverSignature]] =
    onFile(params, Optional.empty[HoverSignature]()) { (file, _) =>
      analyze(file) { s =>
        val hover = referenceAt(file, params.offset()) match {
          case Some(ref) =>
            val symbol = Option(
              s.resolveToSymbol(ReferenceUtilsKt.getMainReference(ref))
            )
            val expr: KtExpression = ref.getParent match {
              case call: KtCallExpression => call
              case _ => ref
            }
            val tpe = Option(s.getExpressionType(expr)).map(t =>
              s.render(
                t,
                KaTypeRendererForSource.INSTANCE.getWITH_SHORT_NAMES,
                Variance.INVARIANT
              )
            )
            Some((symbol.flatMap(render(s, _)), tpe, ref: PsiElement))
          case None =>
            declarationAt(file, params.offset()).map { decl =>
              (render(s, s.getSymbol(decl)), None, decl: PsiElement)
            }
        }
        hover match {
          case Some((signature, tpe, element))
              if signature.isDefined || tpe.isDefined =>
            Optional.of(
              KotlinHover(
                tpe,
                signature,
                Some(range(file.getText, element.getTextRange)),
                config.hoverContentType()
              ): HoverSignature
            )
          case _ => Optional.empty[HoverSignature]()
        }
      }
    }

  private def render(s: KaSession, symbol: KaSymbol): Option[String] =
    symbol match {
      case d: KaDeclarationSymbol =>
        Some(
          s.render(
            d,
            KaDeclarationRendererForSource.INSTANCE.getWITH_SHORT_NAMES
          )
        )
      case _ => None
    }

  // ---------------------------------------------------------------- definition

  override def definition(
      params: OffsetParams
  ): CompletableFuture[DefinitionResult] =
    onFile(params, DefinitionResultImpl.empty) { (file, snapshot) =>
      analyze(file) { s =>
        referenceAt(file, params.offset())
          .flatMap(ref =>
            Option(s.resolveToSymbol(ReferenceUtilsKt.getMainReference(ref)))
          )
          .fold(DefinitionResultImpl.empty)(
            definitionOf(s, _, snapshot, params)
          )
      }
    }

  override def typeDefinition(
      params: OffsetParams
  ): CompletableFuture[DefinitionResult] =
    onFile(params, DefinitionResultImpl.empty) { (file, snapshot) =>
      analyze(file) { s =>
        val fromReference =
          referenceAt(file, params.offset()).flatMap(ref =>
            Option(s.getExpressionType(ref))
          )
        def fromDeclaration =
          declarationAt(file, params.offset()).flatMap(decl =>
            Option(s.getSymbol(decl)).collect { case c: KaCallableSymbol =>
              c.getReturnType
            }
          )
        fromReference
          .orElse(fromDeclaration)
          .collect { case tpe: KaClassType => tpe.getSymbol }
          .fold(DefinitionResultImpl.empty)(
            definitionOf(s, _, snapshot, params)
          )
      }
    }

  private def definitionOf(
      s: KaSession,
      symbol: KaSymbol,
      snapshot: KotlinCompilerSession#Snapshot,
      params: OffsetParams
  ): DefinitionResult = {
    val sourceLocation = for {
      psi <- Option(symbol.getPsi)
      target <- Option(psi.getContainingFile)
      path <- snapshot.pathOf(target)
    } yield {
      val name = psi match {
        case owner: PsiNameIdentifierOwner =>
          Option(owner.getNameIdentifier).getOrElse(psi)
        case _ => psi
      }
      new lsp4j.Location(
        path.toUri.toString,
        range(target.getText, name.getTextRange)
      )
    }
    val semanticdb = semanticdbSymbol(symbol).getOrElse("")
    // A library symbol has no source here; Metals finds it in the source
    // jar index. The classpath index finds a class file by the owner of the
    // symbol, so a top-level function also needs its JVM facade symbol.
    val locations = sourceLocation.toList match {
      case Nil if semanticdb.nonEmpty =>
        (semanticdb :: facadeSymbol(s, symbol).toList).iterator
          .map(sym => search.definition(sym, params.uri()).asScala.toList)
          .find(_.nonEmpty)
          .getOrElse(Nil)
      case found => found
    }
    DefinitionResultImpl(semanticdb, locations.asJava)
  }

  /** The symbol in the format of `KotlinMtags` and `semanticdb-kotlinc`. */
  private def semanticdbSymbol(symbol: KaSymbol): Option[String] =
    symbol match {
      case c: KaConstructorSymbol =>
        Option(c.getContainingClassId).map(id =>
          classSymbol(id) + "`<init>`()."
        )
      case c: KaClassLikeSymbol =>
        Option(c.getClassId).map(classSymbol)
      case c: KaCallableSymbol =>
        Option(c.getCallableId).map { id =>
          val owner = Option(id.getClassId)
            .map(classSymbol)
            .getOrElse(packageSymbol(id.getPackageName))
          val suffix = if (c.isInstanceOf[KaFunctionSymbol]) "()." else "."
          owner + encode(id.getCallableName.asString) + suffix
        }
      case _ => None
    }

  /** `pkg/ClientKt#f().` for a top-level `f` in `Client.kt`. */
  private def facadeSymbol(s: KaSession, symbol: KaSymbol): Option[String] =
    symbol match {
      case c: KaCallableSymbol
          if Option(c.getCallableId).exists(_.getClassId == null) =>
        for {
          facade <- Option(s.getContainingJvmClassName(c))
          member <- semanticdbSymbol(c)
        } yield {
          val owner = facade.split('.').map(encode)
          owner.init.map(_ + "/").mkString + owner.last + "#" +
            member.substring(member.lastIndexOf('/') + 1)
        }
      case _ => None
    }

  private def packageSymbol(pkg: FqName): String =
    if (pkg.isRoot) "_empty_/"
    else pkg.pathSegments().asScala.map(n => encode(n.asString) + "/").mkString

  private def classSymbol(id: ClassId): String =
    packageSymbol(id.getPackageFqName) +
      id.getRelativeClassName
        .pathSegments()
        .asScala
        .map(n => encode(n.asString) + "#")
        .mkString

  private def encode(name: String): String =
    if (
      name.nonEmpty && Character.isJavaIdentifierStart(name.head) &&
      name.forall(Character.isJavaIdentifierPart)
    ) name
    else s"`$name`"

  // ---------------------------------------------------------------- completion

  // Completion runs on a copy of the file with a name inserted at the caret,
  // so that `u.` parses as a member selection.
  private val Dummy = "MetalsKotlinCompletionDummy"

  override def complete(
      params: OffsetParams
  ): CompletableFuture[CompletionList] =
    onFile(params, new CompletionList()) { (file, snapshot) =>
      val text = params.text()
      val offset = params.offset()
      val copy = new KtPsiFactory(snapshot.project, false).createFile(
        file.getName,
        text.substring(0, offset) + Dummy + text.substring(offset)
      )
      DanglingFilesKt.setContextModule(copy, snapshot.sourceModule)
      val items = analyze(copy) { s =>
        referenceAt(copy, offset).toList.flatMap { ref =>
          // The caret can be inside a name: only the text before it counts.
          val name = ref.getReferencedName
          val prefix = name.substring(0, math.max(name.indexOf(Dummy), 0))
          def matches(name: Name) = name.asString.startsWith(prefix)
          // In `a.b()` the selector of `a.` is the call, not the name.
          val selector = ref.getParent match {
            case call: KtCallExpression if call.getCalleeExpression == ref =>
              call
            case _ => ref
          }
          val found = selector.getParent match {
            case qualified: KtQualifiedExpression
                if qualified.getSelectorExpression == selector =>
              val receiver = qualified.getReceiverExpression
              packageOf(s, receiver) match {
                case Some(pkg) =>
                  val inImport = PsiTreeUtil.getParentOfType(
                    ref,
                    classOf[KtImportDirective]
                  ) != null
                  packageMembers(s, pkg, matches, inImport)
                case None => members(s, copy, ref, receiver, matches)
              }
            case _ => scopeNames(s, copy, ref, matches)
          }
          // In `` `wh@@` `` the user already typed the backticks.
          if (ref.getText.startsWith("`"))
            found.foreach(item => item.setInsertText(item.getLabel))
          found
        }
      }
      val unique = items.groupBy(_.getLabel).values.map(_.head).toList
      new CompletionList(false, unique.sortBy(_.getLabel).asJava)
    }

  private def members(
      s: KaSession,
      file: KtFile,
      ref: KtNameReferenceExpression,
      receiver: KtExpression,
      matches: Name => Boolean
  ): List[CompletionItem] = {
    val receiverType = Option(s.getExpressionType(receiver))
    val memberItems = for {
      tpe <- receiverType.toList
      scope <- Option(s.getScope(tpe)).toList
      signature <- Kt.toList(scope.getCallableSignatures(Kt.predicate(matches)))
    } yield callableItem(s, signature.getSymbol.asInstanceOf[KaCallableSymbol])
    val checker = s.createExtensionCandidateChecker(file, ref, receiver)
    val extensionItems = for {
      callable <- callables(s, file, scopesAt(s, file, ref), matches)
      if callable.isExtension && isApplicable(checker, callable)
    } yield callableItem(s, callable)
    memberItems ++ extensionItems
  }

  /** The package that `receiver` names, as `java.util` in `java.util.List`. */
  private def packageOf(
      s: KaSession,
      receiver: KtExpression
  ): Option[KaPackageSymbol] = {
    val name = receiver match {
      case q: KtQualifiedExpression => q.getSelectorExpression
      case e => e
    }
    name match {
      case r: KtNameReferenceExpression =>
        Option(s.resolveToSymbol(ReferenceUtilsKt.getMainReference(r)))
          .collect { case pkg: KaPackageSymbol => pkg }
      case _ => None
    }
  }

  /** Classes, top-level callables and subpackages of `pkg`. */
  private def packageMembers(
      s: KaSession,
      pkg: KaPackageSymbol,
      matches: Name => Boolean,
      inImport: Boolean
  ): List[CompletionItem] = {
    val scope = s.getPackageScope(pkg)
    val names = libraryNames(pkg.getFqName.asString).toList
      .map(Name.identifier)
      .filter(matches)
      .asJava
    val classifiers = Kt.toList(scope.classifiers(Kt.predicate(matches))) ++
      Kt.toList(scope.classifiers(names)).filter(isUsable)
    // An import can name an extension; an expression can call it only on a
    // receiver.
    val callables = (Kt.toList(scope.callables(Kt.predicate(matches))) ++
      Kt.toList(scope.callables(names)).filter(isUsable))
      .filter(inImport || !_.isExtension)
    val packages = Kt
      .toList(scope.getPackageSymbols(Kt.predicate(matches)))
      .map(p =>
        nameItem(Some(p.getFqName.shortName), CompletionItemKind.Module)
      )
    classifiers.map(classifierItem) ++ callables.map(callableItem(s, _)) ++
      packages
  }

  private val DefaultImports = List(
    "kotlin", "kotlin.annotation", "kotlin.collections", "kotlin.comparisons",
    "kotlin.io", "kotlin.ranges", "kotlin.sequences", "kotlin.text",
    "kotlin.jvm"
  )

  private def scopeNames(
      s: KaSession,
      file: KtFile,
      ref: KtNameReferenceExpression,
      matches: Name => Boolean
  ): List[CompletionItem] = {
    val scopes = scopesAt(s, file, ref)
    val classifiers =
      scopes.flatMap(scope =>
        Kt.toList(scope.classifiers(Kt.predicate(matches)))
      ) ++
        libraryScopes(s, file, matches).flatMap { case (scope, names) =>
          Kt.toList(scope.classifiers(names.asJava)).filter(isUsable)
        }
    // Without a receiver, an extension applies to an implicit receiver, such
    // as `this` in `x.run { ... }`.
    val checker = s.createExtensionCandidateChecker(file, ref, null)
    callables(s, file, scopes, matches)
      .filter(c => !c.isExtension || isApplicable(checker, c))
      .map(callableItem(s, _)) ++
      classifiers.map(classifierItem(_))
  }

  private def isApplicable(
      checker: KaCompletionExtensionCandidateChecker,
      callable: KaCallableSymbol
  ): Boolean =
    !checker
      .computeApplicability(callable)
      .isInstanceOf[KaExtensionApplicabilityResult.NonApplicable]

  private def scopesAt(
      s: KaSession,
      file: KtFile,
      ref: KtNameReferenceExpression
  ): List[KaScope] =
    s.scopeContext(file, ref).getScopes.asScala.toList.map(_.getScope)

  /** Callables in `scopes` and in the libraries. */
  private def callables(
      s: KaSession,
      file: KtFile,
      scopes: List[KaScope],
      matches: Name => Boolean
  ): List[KaCallableSymbol] =
    scopes.flatMap(scope =>
      Kt.toList(scope.callables(Kt.predicate(matches)))
    ) ++ libraryScopes(s, file, matches).flatMap { case (scope, names) =>
      Kt.toList(scope.callables(names.asJava)).filter(isUsable)
    }

  /** A library declaration that code outside the library can use. */
  private def isUsable(symbol: KaDeclarationSymbol): Boolean =
    symbol.getVisibility == KaSymbolVisibility.PUBLIC ||
      symbol.getVisibility == KaSymbolVisibility.PROTECTED

  /**
   * Scopes of the packages that `file` sees without a qualifier, each with
   * the library names in it that match. A scope lists library declarations
   * only by their exact names.
   */
  private def libraryScopes(
      s: KaSession,
      file: KtFile,
      matches: Name => Boolean
  ): List[(KaScope, List[Name])] = {
    val starImports = file.getImportDirectives.asScala.toList
      .filter(_.isAllUnder)
      .flatMap(d => Option(d.getImportedFqName))
      .map(_.asString)
    val packages =
      file.getPackageFqName.asString :: DefaultImports ++ starImports
    for {
      pkg <- packages.distinct
      names = libraryNames(pkg).toList.map(Name.identifier).filter(matches)
      if names.nonEmpty
      symbol <- Option(s.findPackage(new FqName(pkg))).toList
    } yield s.getPackageScope(symbol) -> names
  }

  private def callableItem(s: KaSession, symbol: KaCallableSymbol) = {
    val name = symbol match {
      case named: KaNamedSymbol => Option(named.getName)
      case _ => Option(symbol.getCallableId).map(_.getCallableName)
    }
    val item = nameItem(
      name,
      if (symbol.isInstanceOf[KaFunctionSymbol]) CompletionItemKind.Method
      else CompletionItemKind.Property
    )
    render(s, symbol).foreach(sig =>
      item.setDetail(sig.linesIterator.toList.last)
    )
    item
  }

  private def classifierItem(symbol: KaClassifierSymbol) =
    nameItem(Option(symbol.getName), CompletionItemKind.Class)

  /** Inserts a keyword or a name with spaces in backticks. */
  private def nameItem(name: Option[Name], kind: CompletionItemKind) = {
    val item = new CompletionItem(name.fold("")(_.asString))
    name.foreach(n => item.setInsertText(RenderingUtilsKt.render(n, false)))
    item.setKind(kind)
    item
  }

  // ---------------------------------------------------------------- diagnostics

  override def didChange(
      params: VirtualFileParams
  ): CompletableFuture[util.List[Diagnostic]] =
    onFile(params, util.Collections.emptyList[Diagnostic]()) {
      (file, snapshot) =>
        analyze(file) { s =>
          s.collectDiagnostics(
            file,
            KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS
          ).asScala
            .toList
            // A copy of an edited file is in a module of its own.
            .filterNot(d =>
              d.getFactoryName == "SEALED_INHERITOR_IN_DIFFERENT_MODULE" &&
                snapshot.isCopy(file)
            )
            .flatMap { d =>
              d.getTextRanges.asScala.headOption.map { r =>
                val diagnostic = new Diagnostic(
                  range(file.getText, r),
                  d.getDefaultMessage,
                  severity(d.getSeverity),
                  "kotlin"
                )
                diagnostic.setCode(d.getFactoryName)
                diagnostic
              }
            }
            .asJava
        }
    }

  private def severity(s: KaSeverity): DiagnosticSeverity = s match {
    case KaSeverity.ERROR => DiagnosticSeverity.Error
    case KaSeverity.WARNING => DiagnosticSeverity.Warning
    case _ => DiagnosticSeverity.Information
  }

  override def didClose(uri: URI): Unit = session.didClose(Paths.get(uri))

  // ---------------------------------------------------------------- positions

  private def referenceAt(
      file: KtFile,
      offset: Int
  ): Option[KtNameReferenceExpression] = {
    // At the end of a name the caret is after its last character.
    List(offset, offset - 1)
      .filter(_ >= 0)
      .flatMap(at => Option(file.findElementAt(at)))
      .flatMap(e =>
        Option(
          PsiTreeUtil
            .getParentOfType(e, classOf[KtNameReferenceExpression], false)
        )
      )
      .headOption
  }

  private def declarationAt(file: KtFile, offset: Int): Option[KtDeclaration] =
    Option(file.findElementAt(offset)).flatMap { leaf =>
      Option(PsiTreeUtil.getParentOfType(leaf, classOf[KtDeclaration], false))
        .collect {
          case decl: PsiNameIdentifierOwner
              if Option(decl.getNameIdentifier).contains(leaf) =>
            decl.asInstanceOf[KtDeclaration]
        }
    }

  private def range(
      text: String,
      r: com.intellij.openapi.util.TextRange
  ): lsp4j.Range =
    new lsp4j.Range(
      position(text, r.getStartOffset),
      position(text, r.getEndOffset)
    )

  private def position(text: String, offset: Int): lsp4j.Position = {
    val before = text.substring(0, math.min(offset, text.length))
    val line = before.count(_ == '\n')
    new lsp4j.Position(line, before.length - (before.lastIndexOf('\n') + 1))
  }

  // ---------------------------------------------------------------- not supported yet

  override def completionItemResolve(
      item: CompletionItem,
      symbol: String
  ): CompletableFuture[CompletionItem] = CompletableFuture.completedFuture(item)

  override def signatureHelp(
      params: OffsetParams
  ): CompletableFuture[SignatureHelp] =
    CompletableFuture.completedFuture(new SignatureHelp())

  override def prepareRename(
      params: OffsetParams
  ): CompletableFuture[Optional[lsp4j.Range]] =
    CompletableFuture.completedFuture(Optional.empty())

  override def rename(
      params: OffsetParams,
      name: String
  ): CompletableFuture[util.List[TextEdit]] =
    CompletableFuture.completedFuture(util.Collections.emptyList())

  override def documentHighlight(
      params: OffsetParams
  ): CompletableFuture[util.List[DocumentHighlight]] =
    CompletableFuture.completedFuture(util.Collections.emptyList())

  override def getTasty(
      targetUri: URI,
      isHttpEnabled: Boolean
  ): CompletableFuture[String] = CompletableFuture.completedFuture("")

  override def autoImports(
      name: String,
      params: OffsetParams,
      isExtension: lang.Boolean
  ): CompletableFuture[util.List[AutoImportsResult]] =
    CompletableFuture.completedFuture(util.Collections.emptyList())

  override def implementAbstractMembers(
      params: OffsetParams
  ): CompletableFuture[util.List[TextEdit]] =
    CompletableFuture.completedFuture(util.Collections.emptyList())

  override def insertInferredType(
      params: OffsetParams
  ): CompletableFuture[util.List[TextEdit]] =
    CompletableFuture.completedFuture(util.Collections.emptyList())

  override def extractMethod(
      range: RangeParams,
      extractionPos: OffsetParams
  ): CompletableFuture[util.List[TextEdit]] =
    CompletableFuture.completedFuture(util.Collections.emptyList())

  override def convertToNamedArguments(
      params: OffsetParams,
      argIndices: util.List[Integer]
  ): CompletableFuture[util.List[TextEdit]] =
    CompletableFuture.completedFuture(util.Collections.emptyList())

  override def semanticdbTextDocument(
      filename: URI,
      code: String
  ): CompletableFuture[Array[Byte]] =
    CompletableFuture.completedFuture(Array.emptyByteArray)

  override def selectionRange(
      params: util.List[OffsetParams]
  ): CompletableFuture[util.List[SelectionRange]] =
    CompletableFuture.completedFuture(util.Collections.emptyList())

  // ---------------------------------------------------------------- lifecycle

  override def shutdown(): Unit = session.shutdown()
  override def restart(): Unit = session.restart()
  override def diagnosticsForDebuggingPurposes(): util.List[String] =
    util.Collections.emptyList()
  override def isLoaded: Boolean = true
  override def scalaVersion(): String = "kotlin"

  override def withSearch(search: SymbolSearch): PresentationCompiler =
    copy(search = search)
  override def withExecutorService(
      executorService: ExecutorService
  ): PresentationCompiler = this
  override def withScheduledExecutorService(
      scheduledExecutorService: ScheduledExecutorService
  ): PresentationCompiler = this
  override def withConfiguration(
      config: PresentationCompilerConfig
  ): PresentationCompiler = copy(config = config)
  override def withWorkspace(workspace: Path): PresentationCompiler = this

  override def newInstance(
      buildTargetIdentifier: String,
      classpath: util.List[Path],
      options: util.List[String]
  ): PresentationCompiler =
    copy(
      buildTargetId = buildTargetIdentifier,
      classpath = classpath.asScala.toSeq
    )

  override def newInstance(
      buildTargetIdentifier: String,
      classpath: util.List[Path],
      options: util.List[String],
      sources: Supplier[util.List[Path]]
  ): PresentationCompiler =
    copy(
      buildTargetId = buildTargetIdentifier,
      classpath = classpath.asScala.toSeq,
      sources = sources
    )
}
