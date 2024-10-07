package scala.meta.internal.pc

import java.nio.file.Path
import java.util
import java.util.logging.Logger
import java.{util => ju}

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.language.implicitConversions
import scala.reflect.internal.util.Position
import scala.reflect.internal.util.ScriptSourceFile
import scala.reflect.internal.util.SourceFile
import scala.reflect.internal.{Flags => gf}
import scala.tools.nsc.Mode
import scala.tools.nsc.Settings
import scala.tools.nsc.interactive.Global
import scala.tools.nsc.interactive.GlobalProxy
import scala.tools.nsc.interactive.InteractiveAnalyzer
import scala.tools.nsc.reporters.Reporter
import scala.util.control.NonFatal
import scala.{meta => m}

import scala.meta.XtensionSyntax
import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.semanticdb.scalac.SemanticdbOps
import scala.meta.pc.CompletionItemPriority
import scala.meta.pc.ParentSymbols
import scala.meta.pc.PresentationCompilerConfig
import scala.meta.pc.SymbolDocumentation
import scala.meta.pc.SymbolSearch

import org.eclipse.{lsp4j => l}

class MetalsGlobal(
    settings: Settings,
    reporter: Reporter,
    val search: SymbolSearch,
    val buildTargetIdentifier: String,
    val metalsConfig: PresentationCompilerConfig,
    val workspace: Option[Path],
    val completionItemPriority: CompletionItemPriority
) extends Global(settings, reporter)
    with completions.Completions
    with completions.AmmoniteFileCompletions
    with completions.AmmoniteIvyCompletions
    with completions.ArgCompletions
    with completions.FilenameCompletions
    with completions.InterpolatorCompletions
    with completions.MatchCaseCompletions
    with completions.NewCompletions
    with completions.NoneCompletions
    with completions.ScaladocCompletions
    with completions.TypeCompletions
    with completions.OverrideCompletions
    with completions.DependencyCompletions
    with completions.ScalaCliCompletions
    with completions.MillIvyCompletions
    with completions.SbtLibCompletions
    with completions.MultilineCommentCompletions
    with Signatures
    with Compat
    with GlobalProxy
    with AutoImports
    with Keywords
    with WorkspaceSymbolSearch { compiler =>
  hijackPresentationCompilerThread()

  val logger: Logger = Logger.getLogger(classOf[MetalsGlobal].getName)

  val richCompilationCache: TrieMap[String, RichCompilationUnit] =
    TrieMap.empty[String, RichCompilationUnit]

  // for those paths units were fully compiled (not just outlined)
  val fullyCompiled: mutable.Set[String] = mutable.Set.empty[String]

  class MetalsInteractiveAnalyzer(val global: compiler.type)
      extends InteractiveAnalyzer {

    /**
     * Disable blackbox macro expansion for better reliability and performance.
     *
     * The benefits of disabling blackbox macros are
     * - we insure ourselves from misbehaving macro library that mess up with compiler APIs
     * - we avoid potentially expensive computation during macro expansion
     * It's safe to disable blackbox macros because they don't affect typing, meaning
     * they cannot change the results from completions/signatureHelp/hover.
     *
     * Here are basic benchmark numbers running completions in Exprs.scala from fastparse,
     * a 150 line source file where a scope completion triggers 80 macros.
     * {{{
     *   // expand 80 blackbox like normal
     *   [info] CachedSearchAndCompilerCompletionBench.complete  scopeFastparse    ss   60  118.484 ± 56.395  ms/op
     *   // disable all blackbox macros
     *   [info] CachedSearchAndCompilerCompletionBench.complete  scopeFastparse    ss   60  60.489 ± 9.197  ms/op
     * }}}
     *
     * We don't use `analyser.addMacroPlugin()` to disable blackbox macros because benchmarks show that
     * macro plugins add ~10ms overhead compared to overriding this method directly.
     */
    override def pluginsMacroExpand(
        typer: Typer,
        expandee: Tree,
        mode: Mode,
        pt: Type
    ): Tree = {
      if (standardIsBlackbox(expandee.symbol)) expandee
      else super.pluginsMacroExpand(typer, expandee, mode, pt)
    }
  }

  override lazy val analyzer: this.MetalsInteractiveAnalyzer =
    new MetalsInteractiveAnalyzer(compiler)

  def isDocs: Boolean = System.getProperty("metals.signature-help") != "no-docs"

  def isJavaSymbol(sym: Symbol): Boolean =
    !sym.hasPackageFlag && sym.isJava

  class MetalsGlobalSemanticdbOps(val global: compiler.type)
      extends SemanticdbOps
  lazy val semanticdbOps = new MetalsGlobalSemanticdbOps(compiler)

  def semanticdbSymbol(symbol: Symbol): String = {
    import semanticdbOps._
    symbol.toSemantic
  }

  def printPretty(pos: sourcecode.Text[Position]): Unit = {
    if (pos.value == null || pos.value == NoPosition) {
      println(pos.value.toString())
    } else {
      import scala.meta.internal.metals.PositionSyntax._
      val input = scala.meta.Input.String(new String(pos.value.source.content))
      val (start, end) =
        if (pos.value.isRange) {
          (pos.value.start, pos.value.end)
        } else {
          (pos.value.point, pos.value.point)
        }
      val range =
        scala.meta.Position.Range(input, start, end)
      println(range.formatMessage("info", pos.source))
    }
  }

  def pretty(pos: Position): String = {
    if (pos.isDefined) {
      val lineCaret =
        if (pos.isRange) {
          val indent = " " * (pos.column - 1)
          val caret = "^" * (pos.end - pos.start)
          indent + caret
        } else {
          pos.lineCaret
        }
      pos.lineContent + "\n" + lineCaret
    } else {
      "<none>"
    }
  }

  def treePos(tree: Tree): Position = {
    if (tree.pos == null) {
      NoPosition
    } else if (
      tree.symbol != null &&
      tree.symbol.name.startsWith("x$") &&
      tree.symbol.isArtifact
    ) {
      tree.symbol.pos
    } else {
      tree.pos
    }
  }

  /**
   * If we run outline compilation on a file this means it's fresher
   * and we don't want to get older data from last compilation.
   *
   * @param symbol symbol to check
   */
  def isOutlinedFile(path: Path): Boolean = {
    richCompilationCache.contains(path.toUri().toString())
  }

  def workspaceSymbolListMembers(
      query: String,
      pos: Position,
      visit: Member => Boolean
  ): SymbolSearch.Result = {

    def isRelevantWorkspaceSymbol(sym: Symbol): Boolean =
      sym.isStatic && !sym.isStale

    lazy val isInStringInterpolation = {
      lastVisitedParentTrees match {
        case Apply(
              Select(Apply(Ident(TermName("StringContext")), _), _),
              _
            ) :: _ =>
          true
        case _ => false
      }
    }

    def visitMember(sym: Symbol) = {
      if (isRelevantWorkspaceSymbol(sym))
        visit {
          if (isInStringInterpolation)
            new WorkspaceInterpolationMember(
              sym,
              Nil,
              edit => s"{$edit}",
              None
            )
          else
            new WorkspaceMember(sym)
        }
      else false
    }

    if (query.isEmpty) SymbolSearch.Result.INCOMPLETE
    else {
      val context = doLocateContext(pos)
      val visitor = new CompilerSearchVisitor(
        context,
        visitMember
      )
      searchOutline(visitMember, query)
      search.search(query, buildTargetIdentifier, visitor)
    }
  }

  def workspaceSymbolListMembers(
      query: String,
      pos: Position
  ): List[Member] = {
    val buffer = mutable.ListBuffer.empty[Member]
    val isSeen = mutable.Set.empty[String]
    workspaceSymbolListMembers(
      query,
      pos,
      mem => {
        val id = mem.sym.fullName
        if (!isSeen(id)) {
          isSeen += id
          buffer.append(mem)
          true
        } else true
      }
    )
    buffer.toList
  }

  def symbolDocumentation(
      symbol: Symbol,
      contentType: m.pc.ContentType = m.pc.ContentType.MARKDOWN
  ): Option[SymbolDocumentation] = {
    def toSemanticdbSymbol(sym: Symbol) = compiler.semanticdbSymbol(
      if (!sym.isJava && sym.isPrimaryConstructor) sym.owner
      else sym
    )
    val sym = toSemanticdbSymbol(symbol)
    val documentation = search.documentation(
      sym,
      new ParentSymbols {
        def parents(): util.List[String] = {
          val parentSymbols =
            if (symbol.name == nme.apply && symbol.safeOwner.isModuleClass)
              List(
                symbol.safeOwner,
                symbol.safeOwner.companion
              ).filter(_ != NoSymbol) ++ symbol.overrides
            else symbol.overrides

          parentSymbols.map(toSemanticdbSymbol).asJava
        }
      },
      contentType
    )

    if (documentation.isPresent) {
      Some(documentation.get())
    } else {
      None
    }
  }

  private def backtickify(sym: Symbol) =
    if (
      Identifier.needsBacktick(sym.name.decoded)
      && sym.owner != definitions.ScalaPackageClass
      && !sym.isPackageObjectOrClass
    ) {
      val name0: sym.NameType = sym.rawname
      val name: Name = name0.newName(Identifier.backtickWrap(name0))
      sym.setName(name)
    } else sym

  /**
   * Shortens fully qualified package prefixes to make type signatures easier to read.
   *
   * It becomes difficult to read method signatures when they have a large number of parameters
   * with fully qualified names. This method strips out package prefixes to shorten the names while
   * making sure to not convert two different symbols into same short name.
   */
  def shortType(longType: Type, history: ShortenedNames): Type = {
    val isVisited = mutable.Set.empty[(Type, Option[ShortName])]
    val cached = new ju.HashMap[(Type, Option[ShortName]), Type]()
    def loop(tpe: Type, name: Option[ShortName]): Type = {
      val key = tpe -> name
      // NOTE(olafur) Prevent infinite recursion, see https://github.com/scalameta/metals/issues/749
      if (isVisited(key)) return cached.getOrDefault(key, tpe)
      isVisited += key
      val result = tpe match {
        case TypeRef(pre, sym, args) =>
          def shortSymbol = {
            // workaround for Tuple1 (which is incorrectly printed by Scala 2 compiler)
            def isTuple1 = sym == definitions.TupleClass(1)
            /* If it's an alias type we want to prevent dealiasing it
               AnyRef should stay to be dropped if neded later on since it's
               not an important class.
             */
            if ((sym.isAliasType && sym != definitions.AnyRefClass) || isTuple1)
              backtickify(sym.newErrorSymbol(sym.name).updateInfo(sym.info))
            else backtickify(sym)
          }
          if (history.isSymbolInScope(sym, pre)) {
            TypeRef(
              NoPrefix,
              shortSymbol,
              args.map(arg => loop(arg, None))
            )
          } else {
            val ownerSymbol = pre.termSymbol
            def hasConflictingMembersInScope =
              history.lookupSymbol(sym.name).exists {
                case _: LookupSucceeded => true
                case _ => false
              }

            def canRename(rename: Name, ownerSym: Symbol): Boolean = {
              val shouldRenamePrefix =
                !metalsConfig.isDefaultSymbolPrefixes || hasConflictingMembersInScope

              if (shouldRenamePrefix) {
                val existingRename = history.rename(ownerSym)
                existingRename.isEmpty && history.tryShortenName(
                  ShortName(rename, ownerSymbol)
                )
              } else false
            }

            history.config.get(ownerSymbol) match {
              case Some(rename) if canRename(rename, ownerSymbol) =>
                TypeRef(
                  SingleType(
                    NoPrefix,
                    sym.newErrorSymbol(rename)
                  ),
                  shortSymbol,
                  args.map(arg => loop(arg, None))
                )
              case _ =>
                history.rename(sym) match {
                  case Some(rename) =>
                    TypeRef(
                      NoPrefix,
                      sym.newErrorSymbol(rename),
                      args.map(arg => loop(arg, None))
                    )
                  case _ =>
                    if (
                      sym.isAliasType &&
                      (sym.isAbstract ||
                        sym.overrides.lastOption.exists(_.isAbstract))
                    ) {

                      // Always dealias abstract type aliases but leave concrete aliases alone.
                      // trait Generic { type Repr /* dealias */ }
                      // type Catcher[T] = PartialFunction[Throwable, T] // no dealias
                      loop(tpe.dealias, name)
                    } else if (history.owners(pre.typeSymbol)) {
                      if (history.nameResolvesToSymbol(sym.name, sym)) {
                        TypeRef(
                          NoPrefix,
                          shortSymbol,
                          args.map(arg => loop(arg, None))
                        )
                      } else {
                        TypeRef(
                          ThisType(pre.typeSymbol),
                          shortSymbol,
                          args.map(arg => loop(arg, None))
                        )
                      }
                    } else if (sym.isMethod && sym.safeOwner.isImplicit) {
                      history.tryShortenName(ShortName(sym.safeOwner))
                      TypeRef(
                        NoPrefix,
                        shortSymbol,
                        args.map(arg => loop(arg, None))
                      )
                    } else {
                      TypeRef(
                        loop(pre, Some(ShortName(sym))),
                        shortSymbol,
                        args.map(arg => loop(arg, None))
                      )
                    }
                }
            }
          }
        case SingleType(pre, sym) =>
          def backtickifiedSymbol = backtickify(sym)
          if (sym.hasPackageFlag || sym.isPackageObjectOrClass) {
            val dotSyntaxFriendlyName = name.map { name0 =>
              if (name0.symbol.isStatic) name0
              else {
                // Use the prefix rather than the real owner to maximize the
                // chances of shortening the reference: when `name` is directly
                // nested in a non-statically addressable type (class or trait),
                // its original owner is that type (requiring a type projection
                // to reference it) while the prefix is its concrete owner value
                // (for which the dot syntax works).
                // https://docs.scala-lang.org/tour/inner-classes.html
                // https://danielwestheide.com/blog/the-neophytes-guide-to-scala-part-13-path-dependent-types/
                ShortName(name0.symbol.cloneSymbol(sym))
              }
            }
            if (history.tryShortenName(dotSyntaxFriendlyName)) NoPrefix
            else SingleType(pre, backtickifiedSymbol)
          } else {
            if (history.isSymbolInScope(sym, pre))
              SingleType(NoPrefix, backtickifiedSymbol)
            else {
              pre match {
                case ThisType(psym) if history.isSymbolInScope(psym, pre) =>
                  SingleType(NoPrefix, backtickifiedSymbol)
                case _ =>
                  SingleType(
                    loop(pre, Some(ShortName(sym))),
                    backtickifiedSymbol
                  )
              }
            }
          }
        case ThisType(sym) =>
          val owners = sym.ownerChain
          // to make sure we always use renamed package
          // what is saved in renames is actually companion module of a package
          val renamedOwnerIndex =
            owners.indexWhere(s => history.rename(s.companionModule).nonEmpty)
          if (renamedOwnerIndex < 0 && history.tryShortenName(name)) NoPrefix
          else {
            val prefix =
              if (renamedOwnerIndex < 0)
                owners.indexWhere { owner =>
                  owner.owner != definitions.ScalaPackageClass &&
                  history.tryShortenName(
                    Some(ShortName(owner.name, owner))
                  )
                }
              else renamedOwnerIndex
            if (prefix < 0) {
              SingleType(
                NoPrefix,
                sym.newErrorSymbol(TypeName(history.fullname(sym)))
              )
            } else {
              val names = owners
                .take(prefix + 1)
                .reverse
                .map(s =>
                  m.Term.Name(
                    history
                      .rename(s.companionModule)
                      .map(_.toString())
                      .getOrElse(s.nameSyntax)
                  )
                )
              val ref = names.tail.foldLeft(names.head: m.Term.Ref) {
                case (qual, name) => m.Term.Select(qual, name)
              }
              SingleType(
                NoPrefix,
                sym.newErrorSymbol(TypeName(ref.syntax))
              )
            }
          }
        case ConstantType(Constant(sym: TermSymbol))
            if sym.hasFlag(gf.JAVA_ENUM) =>
          loop(SingleType(sym.owner.thisPrefix, sym), None)
        case ConstantType(Constant(tpe: Type)) =>
          ConstantType(Constant(loop(tpe, None)))
        case SuperType(thistpe, supertpe) =>
          SuperType(loop(thistpe, None), loop(supertpe, None))
        case RefinedType(parents, decls) =>
          RefinedType(parents.map(parent => loop(parent, None)), decls)
        case AnnotatedType(annotations, underlying) =>
          AnnotatedType(annotations, loop(underlying, None))
        case ExistentialType(quantified, underlying) =>
          ExistentialType(
            quantified.map(sym => sym.setInfo(loop(sym.info, None))),
            loop(underlying, None)
          )
        case PolyType(typeParams, resultType) =>
          resultType.map(t => loop(t, None)) match {
            // [x] => F[x] is not printable in the code, we need to use just `F`
            case TypeRef(_, sym, args)
                if typeParams == args.map(_.typeSymbol) =>
              TypeRef(
                NoPrefix,
                sym.newErrorSymbol(sym.name),
                Nil
              )
            case otherType =>
              PolyType(typeParams, otherType)
          }
        case NullaryMethodType(resultType) =>
          loop(resultType, None)
        case TypeBounds(lo, hi) =>
          TypeBounds(loop(lo, None), loop(hi, None))
        case MethodType(params, resultType) =>
          MethodType(params, loop(resultType, None))
        case ErrorType =>
          definitions.AnyTpe
        case t => t
      }
      cached.putIfAbsent(key, result)
      result
    }

    longType match {
      case ThisType(_) => longType
      case _ => loop(longType, None)
    }
  }

  /**
   * Custom `Type.toLongString` that shortens fully qualified package prefixes.
   */
  def metalsToLongString(tpe: Type, history: ShortenedNames): String = {
    shortType(tpe, history).toLongString
  }

  /**
   * Converts a SemanticDB symbol into a compiler symbol.
   */
  def inverseSemanticdbSymbols(symbol: String): List[Symbol] = {
    import scala.meta.internal.semanticdb.Scala._
    if (!symbol.isGlobal) return Nil

    def loop(s: String): List[Symbol] = {
      if (s.isNone || s.isRootPackage) rootMirror.RootPackage :: Nil
      else if (s.isEmptyPackage) rootMirror.EmptyPackage :: Nil
      else if (s.isPackage) {
        try {
          val pkg = s
            .split("/")
            .map(n => TermName(n.stripBackticks).encoded)
            .mkString(".")
          rootMirror.staticPackage(pkg) :: Nil
        } catch {
          case NonFatal(_) =>
            Nil
        }
      } else {
        val (desc, parent) = DescriptorParser(s)
        val parentSymbol = loop(parent)

        def tryMember(sym: Symbol): List[Symbol] =
          sym match {
            case NoSymbol =>
              Nil
            case owner =>
              desc match {
                case Descriptor.None =>
                  Nil
                case Descriptor.Type(value) =>
                  val member = owner.info.decl(TypeName(value).encode) :: Nil
                  if (sym.isJava)
                    owner.info.decl(TermName(value).encode) :: member
                  else member
                case Descriptor.Term(value) =>
                  owner.info.decl(TermName(value).encode) :: Nil
                case Descriptor.Package(value) =>
                  owner.info.decl(TermName(value).encode) :: Nil
                case Descriptor.Parameter(value) =>
                  owner.paramss.flatten.filter(
                    _.name.decodedName.containsName(value)
                  )
                case Descriptor.TypeParameter(value) =>
                  owner.typeParams.filter(
                    _.name.decodedName.containsName(value)
                  )
                case Descriptor.Method(value, _) =>
                  owner.info
                    .decl(TermName(value).encode)
                    .alternatives
                    .iterator
                    .filter(sym => semanticdbSymbol(sym) == s)
                    .toList
              }
          }

        parentSymbol.flatMap(tryMember)
      }
    }

    try loop(symbol).filterNot(_ == NoSymbol)
    catch {
      case NonFatal(e) =>
        logger.severe(
          s"invalid SemanticDB symbol: $symbol\n${e.getMessage}"
        )
        Nil
    }
  }

  def inverseSemanticdbSymbol(symbol: String): Symbol = {
    inverseSemanticdbSymbols(symbol) match {
      case head :: _ =>
        head
      case Nil =>
        NoSymbol
    }
  }

  // NOTE(olafur): see compiler plugin test cases in `CompletionLspSuite`
  // why this override is necessary. Compiler plugins like kind-projector
  // use `TypingTransformer`, which produces contexts that break completions.
  // We whitelist a set of known compiler phases which `addContext` was designed
  // to work with.
  override def addContext(contexts: Contexts, context: Context): Unit = {
    phase.name match {
      case "typer" | "namer" =>
        super.addContext(contexts, context)
      case _ =>
    }
  }

  override def locateTree(pos: Position): Tree = {
    onUnitOf(pos.source) { unit => new MetalsLocator(pos).locateIn(unit.body) }
  }

  def locateTree(
      pos: Position,
      tree: Tree,
      acceptTransparent: Boolean
  ): Tree = {
    new MetalsLocator(pos, acceptTransparent).locateIn(tree)
  }

  def locateUntyped(pos: Position): Tree = {
    onUnitOf(pos.source) { unit =>
      new MetalsLocator(pos).locateIn(parseTree(unit.source))
    }
  }

  def CURSOR = "_CURSOR_"

  def addCompilationUnit(
      code: String,
      filename: String,
      cursor: Option[Int],
      cursorName: String = CURSOR,
      isOutline: Boolean = false,
      forceNew: Boolean = false
  ): RichCompilationUnit = {
    val codeWithCursor = cursor match {
      case Some(offset) =>
        code.take(offset) + cursorName + code.drop(offset)
      case _ => code
    }
    val unit = newCompilationUnit(codeWithCursor, filename)

    val source =
      if (filename.isScalaScript || filename.isSbt || filename.isMill)
        ScriptSourceFile(unit.source.file, unit.source.content)
      else unit.source
    val richUnit = new RichCompilationUnit(source)
    unitOfFile.get(richUnit.source.file) match {
      case Some(value)
          if util.Arrays.equals(
            value.source.content,
            richUnit.source.content
          ) && (isOutline || fullyCompiled(filename)) && !forceNew =>
        value
      case _ =>
        unitOfFile(richUnit.source.file) = richUnit
        if (!isOutline) {
          fullyCompiled += filename
        } else {
          fullyCompiled -= filename
        }

        richUnit
    }
  }

  // Needed for 2.11 where `Name` doesn't extend CharSequence.
  implicit def nameToCharSequence(name: Name): CharSequence =
    name.toString

  implicit class XtensionTypeMetals(tpe: Type) {
    def isDefined: Boolean =
      tpe != null &&
        tpe != NoType &&
        !tpe.isErroneous
  }
  implicit class XtensionImportMetals(imp: Import) {
    def selector(pos: Position): Option[Symbol] =
      for {
        sel <- imp.selectors.reverseIterator.find(_.namePos <= pos.start)
      } yield imp.expr.symbol.info.member(sel.name)
  }
  implicit class XtensionPositionMetals(pos: Position) {
    // Same as `Position.includes` except handles an off-by-one bug when other.point > pos.end
    def metalsIncludes(other: Position): Boolean = {
      pos.includes(other) &&
      (!other.isOffset || other.point != pos.end)
    }
    private def toPos(offset: Int): l.Position = {
      val line = pos.source.offsetToLine(offset)
      val column = offset - pos.source.lineToOffset(line)
      new l.Position(line, column)
    }

    def isAfter(other: Position): Boolean = {
      pos.isDefined &&
      other.isDefined &&
      pos.point > other.point
    }

    def toLsp: l.Range = {
      if (pos.isRange) {
        new l.Range(toPos(pos.start), toPos(pos.end))
      } else {
        val p = toPos(pos.point)
        new l.Range(p, p)
      }
    }
  }

  implicit class XtensionContextMetals(context: Context) {
    def nameIsInScope(name: Name): Boolean = {
      def symbolNotFound(name: Name) =
        context.lookupSymbol(name, sym => !sym.isStale) != LookupNotFound
      symbolNotFound(name) || symbolNotFound(name.otherName)
    }
    def symbolIsInScope(sym: Symbol): Boolean =
      nameResolvesToSymbol(sym.name.toTypeName, sym) ||
        nameResolvesToSymbol(sym.name.toTermName, sym)
    def nameResolvesToSymbol(name: Name, sym: Symbol): Boolean =
      context.lookupSymbol(name, sym => !sym.isStale).symbol match {
        case `sym` => true
        case other => other.isKindaTheSameAs(sym)
      }
  }

  implicit class XtensionTreeMetals(tree: Tree) {
    def findSubtree(pos: Position): Tree = {
      def loop(tree: Tree): Tree =
        tree match {
          case Select(qual, _) if qual.pos.includes(pos) => loop(qual)
          case t => t
        }
      loop(tree)
    }
  }
  implicit class XtensionDefTreeMetals(defn: DefTree) {

    /**
     * Returns the position of the name/identifier of this definition.
     */
    def namePosition: Position = {
      val name =
        if (defn.symbol.isPackageObject) defn.symbol.enclosingPackageClass.name
        else defn.name
      val start = defn.pos.point
      val end = start + name.dropLocal.decoded.length()
      Position.range(defn.pos.source, start, start, end)
    }
  }

  implicit class XtensionNameTreeMetals(sel: NameTreeApi) {

    /**
     * Returns the position of the name/identifier of this select.
     */
    def namePosition: Position = {
      sel match {
        case _ if !sel.pos.isRange => sel.pos
        case Select(qualifier: Select, name)
            if (name == nme.apply || name == nme.unapply) && sel.pos.point == qualifier.pos.point =>
          qualifier.namePosition
        case Select(qualifier: Ident, name)
            if (name == nme.apply || name == nme.unapply) && sel.pos.point == qualifier.pos.point =>
          qualifier.pos
        case _ =>
          val start = sel.pos.point
          val end = start + sel.name.getterName.decoded.trim.length()
          Position.range(sel.pos.source, start, start, end)
      }
    }

  }

  implicit class XtensionImportSelectorMetals(sel: ImportSelector) {

    /**
     * Returns the position of the name/identifier of this import selector.
     */
    def namePosition(source: SourceFile): Position = {
      val start = sel.namePos
      val end = start + sel.name.getterName.decoded.trim.length()
      Position.range(source, start, start, end)
    }

    /**
     * Returns the position of the rename of this import selector.
     */
    def renamePosition(source: SourceFile): Position = {
      val start = sel.renamePos
      val end = start + sel.rename.getterName.decoded.trim.length()
      Position.range(source, start, start, end)
    }

  }

  implicit class XtensionSymbolMetals(sym: Symbol) {
    def foreachKnownDirectSubClass(fn: Symbol => Unit): Unit = {
      // NOTE(olafur) The logic in this method is fairly involved because `knownDirectSubClasses`
      // returns a lot of redundant and unrelevant symbols in long-running sessions. For example,
      // `knownDirectSubClasses` returns `Subclass_CURSOR_` symbols if the user ran a completion while
      // defining the class with name "Subclass".
      val isVisited = mutable.Set.empty[String]
      def loop(sym: Symbol): Unit = {
        sym.knownDirectSubclasses.foreach { child =>
          val unique = semanticdbSymbol(child)
          if (!isVisited(unique) && !child.isStale) {
            isVisited += unique
            if (child.name.containsName(CURSOR)) ()
            else if (child.name == tpnme.LOCAL_CHILD) ()
            else if (child.isSealed && (child.isAbstract || child.isTrait)) {
              loop(child)
            } else {
              fn(child)
            }
          }
        }
      }
      loop(sym)
    }
    // Returns true if this symbol is locally defined from an old version of the source file.
    def isStale: Boolean =
      sym.pos.isRange &&
        unitOfFile.get(sym.pos.source.file).exists { unit =>
          if (unit.source ne sym.pos.source) {
            // HACK(olafur) Check if the position of the symbol in the old
            // source points to the symbol's name in the new source file. There
            // are cases where the same class definition has two different
            // symbols between two completion request and
            // `knownDirectSubClasses` returns the version of the class symbol
            // while `Context.lookupSymbol` returns the new version of the class
            // symbol.
            !unit.source.content.startsWith(sym.decodedName, sym.pos.point)
          } else {
            false
          }
        }
    def isScalaPackageObject: Boolean = {
      sym.isPackageObject &&
      sym.owner == definitions.ScalaPackageClass
    }
    def javaClassSymbol: Symbol = {
      if (sym.isJavaModule && !sym.hasPackageFlag) sym.companionClass
      else sym
    }
    def nameSyntax: String = {
      if (sym.isEmptyPackage || sym.isEmptyPackageClass) "_empty_"
      else if (sym.isRootPackage || sym.isRoot) "_root_"
      else sym.nameString
    }
    def fullNameSyntax: String = {
      val out = new java.lang.StringBuilder
      def loop(s: Symbol): Unit = {
        if (
          s.isRoot || s.isRootPackage || s == NoSymbol || s.owner.isEffectiveRoot
        ) {
          out.append(Identifier(s.nameSyntax))
        } else if (s.isPackageObjectOrClass) {
          // package object doesn't have a name if we use s.name we will get `package`
          loop(s.effectiveOwner.enclClass)
        } else {
          loop(s.effectiveOwner.enclClass)
          out.append('.').append(Identifier(s.name))
        }
      }
      loop(sym)
      out.toString
    }
    def isLocallyDefinedSymbol: Boolean = {
      sym.isLocalToBlock && sym.pos.isDefined
    }

    def asInfixPattern: Option[String] =
      if (
        sym.isCase &&
        !Character.isUnicodeIdentifierStart(sym.decodedName.head)
      ) {
        sym.primaryConstructor.paramss match {
          case (a :: b :: Nil) :: _ =>
            Some(s"${a.decodedName} ${sym.decodedName} ${b.decodedName}")
          case _ => None
        }
      } else {
        None
      }

    def isKindaTheSameAs(other: Symbol): Boolean = {
      if (other == NoSymbol) sym == NoSymbol
      else if (sym == NoSymbol) false
      else if (sym.isStale) false
      else if (sym.hasPackageFlag) {
        // NOTE(olafur) hacky workaround for comparing module symbol with package symbol
        other.fullName == sym.fullName
      } else {
        other.dealiased == sym.dealiased ||
        other.companion == sym.dealiased ||
        semanticdbSymbol(other.dealiased) == semanticdbSymbol(sym.dealiased)
      }
    }

    def snippetCursor: String =
      sym.paramss match {
        case Nil =>
          if (clientSupportsSnippets) "$0" else ""
        case Nil :: Nil =>
          if (clientSupportsSnippets) "()$0" else "()"
        case _ =>
          if (clientSupportsSnippets) "($0)" else ""
      }

    def isDefined: Boolean =
      sym != null &&
        sym != NoSymbol &&
        !sym.isErroneous

    def isNonNullaryMethod: Boolean =
      sym.isMethod &&
        !sym.info.isInstanceOf[NullaryMethodType] &&
        !sym.paramss.isEmpty

    def isJavaModule: Boolean =
      sym.isJava && sym.isModule

    def hasTypeParams: Boolean =
      sym.typeParams.nonEmpty ||
        (sym.isJavaModule && sym.companionClass.typeParams.nonEmpty)

    def requiresTemplateCurlyBraces: Boolean = {
      sym.isTrait || sym.isInterface || sym.isAbstractClass
    }
    def isTypeSymbol: Boolean =
      sym.isType ||
        sym.isClass ||
        sym.isTrait ||
        sym.isInterface ||
        sym.isJavaModule

    def dealiasedSingleType: Symbol =
      if (sym.isValue) {
        sym.info match {
          case SingleType(_, dealias) => dealias
          case _ => sym
        }
      } else {
        sym
      }
    def dealiased: Symbol =
      if (sym.isAliasType) sym.info.dealias.typeSymbol
      else sym.dealiasedSingleType

    /**
     * For classes defined in methods it's not possible to find
     * companion via methods in symbol.
     *
     * @param pos position for locating context
     * @return companion if it exists
     */
    def localCompanion(pos: Position): Option[Symbol] =
      if (!sym.owner.isMethod) Some(sym.companion)
      else {
        val nameToLookFor =
          if (sym.isModuleClass) sym.name.companionName.companionName
          else sym.name.companionName
        locateContext(pos).flatMap(
          _.lookupSymbol(
            nameToLookFor,
            s => s.owner == sym.owner
          ) match {
            case LookupSucceeded(_, symbol) =>
              Some(symbol)
            case _ => None
          }
        )
      }

  }

  def metalsSeenFromType(tree: Tree, symbol: Symbol): Type = {
    def qual(t: Tree): Tree =
      t match {
        case TreeApply(q, _) => qual(q)
        case Select(q, _) => q
        case Import(q, _) => q
        case t => t
      }
    val pre = stabilizedType(qual(tree))
    if (pre != null) {
      val memberType = pre.memberType(symbol)
      if (memberType.isErroneous) symbol.info
      else memberType
    } else NoType
  }

  /**
   * Traverses up the parent tree nodes to the largest enclosing application node.
   *
   * Example: {{{
   *   original = println(List(1).map(_.toString))
   *   pos      = List(1).map
   *   expanded = List(1).map(_.toString)
   * }}}
   */
  def expandRangeToEnclosingApply(pos: Position): Tree = {
    def tryTail(enclosing: List[Tree]): Option[Tree] =
      enclosing match {
        case Nil => None
        case head :: tail =>
          head match {
            case TreeApply(qual, _) if qual.pos.includes(pos) =>
              tryTail(tail).orElse(Some(head))
            case New(_) =>
              tail match {
                case Nil => None
                case Select(_, _) :: next =>
                  tryTail(next)
                case _ =>
                  None
              }
            case _ =>
              None
          }
      }
    lastVisitedParentTrees match {
      case head :: tail =>
        tryTail(tail).getOrElse(head)
      case _ =>
        EmptyTree
    }
  }

  def seenFromType(tree0: Tree, symbol: Symbol): Type = {
    def qual(t: Tree): Tree =
      t match {
        case TreeApply(q, _) => qual(q)
        case Select(q, _) => q
        case Import(q, _) => q
        case t => t
      }
    try {
      val tree = qual(tree0)
      val pre = stabilizedType(tree)
      val memberType = pre.memberType(symbol)
      if (memberType.isErroneous) symbol.info
      else memberType
    } catch {
      case NonFatal(_) => symbol.info
    }
  }

  private val forCompMethods =
    Set(nme.map, nme.flatMap, nme.withFilter, nme.foreach)

  // We don't want to collect synthetic `map`, `withFilter`, `foreach` and `flatMap` in for-comprenhesions
  def isForComprehensionMethod(sel: Select): Boolean = {
    val syntheticName = sel.name match {
      case name: TermName => forCompMethods(name)
      case _ => false
    }
    val wrongSpan = sel.qualifier.pos.includes(sel.namePosition.focusStart)
    syntheticName && wrongSpan
  }

  private val infixNames =
    Set(nme.apply, nme.unapply, nme.unapplySeq)

  def isInfix(tree: Tree, text: String): Boolean =
    tree match {
      case Select(New(_), _) => false
      case Select(_, name: TermName) if infixNames(name) => false
      case Select(This(_), _) => false
      // is a select statement without a dot `qual.name`
      case sel: Select if !sel.qualifier.pos.isOffset =>
        val qualEnd = sel.qualifier.pos.end
        val qualStart = sel.qualifier.pos.start
        val nameStart = sel.namePosition.start
        qualStart != nameStart && nameStart < text.length() &&
        !text.slice(qualEnd, nameStart).contains(".")
      case _ => false
    }

  def enclosedChildren(tree: Tree, pos: Position): List[Tree] = {
    tree.children
      .filter(c =>
        c.pos.isDefined && c.pos.start <= pos.end && c.pos.end >= pos.start
      )
  }

  // Extractor for both term and type applications like `foo(1)` and foo[T]`
  object TreeApply {
    def unapply(tree: Tree): Option[(Tree, List[Tree])] =
      tree match {
        case TypeApply(qual, args) => Some(qual -> args)
        case Apply(qual, args) => Some(qual -> args)
        case UnApply(qual, args) => Some(qual -> args)
        case AppliedTypeTree(qual, args) => Some(qual -> args)
        case _ => None
      }
  }

  /**
   * Creates a bounded wildcard type for a type of parameter
   * using information about type parameters.
   *
   * E.g. for class A[T](x: List[T])
   * List[Int] <:< List[T] is false,
   * this method for List[T] will return List[_ >: Nothing <: Any],
   * and List[Int] <:< List[_ >: Nothing <: Any] is true.
   */
  def boundedWildcardType(
      tpe: Type,
      typeParams: List[Symbol]
  ): Type = {
    if (typeParams.isEmpty) tpe
    else {
      typeParams.find(_ == tpe.typeSymbol) match {
        case Some(tpeDef) =>
          tpeDef.info match {
            case bounds: TypeBounds => BoundedWildcardType(bounds)
            case tpe => tpe
          }
        case None =>
          tpe match {
            case TypeRef(pre, sym, args) =>
              TypeRef(pre, sym, args.map(boundedWildcardType(_, typeParams)))
            case t => t
          }
      }
    }
  }

}
