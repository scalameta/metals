package scala.meta.internal.metals

import scala.collection.mutable.ListBuffer
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import scala.meta.Defn
import scala.meta.Pkg
import scala.meta.Term
import scala.meta.Tree
import scala.meta.inputs.Input
import scala.meta.inputs.Position
import scala.meta.internal.docstrings.ImportFallbacks
import scala.meta.internal.docstrings.MetalsSymbolLink
import scala.meta.internal.docstrings.WikiLink
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags
import scala.meta.internal.parsing.Trees
import scala.meta.io.AbsolutePath
import scala.meta.tokens.Token.Comment

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.TextDocumentPositionParams

class ScaladocDefinitionProvider(
    buffers: Buffers,
    trees: Trees,
    destinationProvider: DestinationProvider,
) {

  def definition(
      path: AbsolutePath,
      params: TextDocumentPositionParams,
      isScala3: Boolean,
  ): Option[DefinitionResult] = {
    val isJava = path.toString.endsWith(".java")
    // A Java doc link uses Javadoc/Scaladoc-2 precedence, never the target's Scala 3
    // source order, matching the Java hover markers so both agree (scalameta/metals#3383).
    val isDocScala3 = isScala3 && !isJava
    for {
      buffer <- buffers.get(path)
      position <- params.getPosition().toMeta(Input.String(buffer))
      // The link is in this file, so its language drives Scaladoc-vs-Javadoc
      // parsing (e.g. a Java `Foo$` is not a value-force) (scalameta/metals#3383).
      symbol <- extractScalaDocLinkAtPos(buffer, position, isDocScala3, isJava)
      rawLink = symbol.rawSymbol
      // Owner context and import fallbacks resolved the way hover does, so source
      // go-to-definition and hover navigate identically (scalameta/metals#3383).
      (context, fallbacksOf) =
        linkContext(path, position, buffer, isJava, isScala3)
      definitionResult <- resolveLinkLocations(
        rawLink,
        path,
        isDocScala3,
        context,
        fallbacksOf,
        isJava,
        knownDocstringFile = Some(path),
      ) match {
        case Nil => None
        case locations =>
          Some(DefinitionResult(locations.asJava, rawLink, None, None, rawLink))
      }
    } yield definitionResult
  }

  /**
   * The owner context and a target->fallbacks function for the link under the
   * cursor. Scala parses via `trees`/`ScaladocImportScope.at`, Java via
   * `JavadocIndexer.sourceContext`, so links resolve on click too (scalameta/metals#3383).
   */
  private def linkContext(
      path: AbsolutePath,
      position: Position,
      buffer: String,
      isJava: Boolean,
      isScala3: Boolean,
  ): (ContextSymbols, String => ImportFallbacks) =
    if (isJava) {
      val input = Input.VirtualFile(path.toURI.toString, buffer)
      val (owner, importScope) =
        JavadocIndexer.sourceContext(input, position.start)(EmptyReportContext)
      val context =
        owner.fold(ContextSymbols.empty)(ContextSymbols.fromSymbols(_, None))
      val fallbacksOf = (target: String) =>
        MetalsSymbolLink.fallbacksForTarget(
          target,
          isJava,
          (name, rest, bare) =>
            ScaladocImportScope.fallbacksFor(
              importScope,
              isJava,
              name,
              rest,
              bare,
            ),
        )
      (context, fallbacksOf)
    } else {
      val (context, enclosingNode, enclosingPackage) =
        getContext(path, position, isScala3)
      val fallbacksOf =
        enclosingNode.fold((_: String) => ImportFallbacks.empty) { node =>
          val scope =
            ScaladocImportScope.at(
              node,
              enclosingPackage,
              new ScaladocImportScope.Cache,
            )
          (target: String) =>
            MetalsSymbolLink.fallbacksForTarget(
              target,
              isJava,
              (name, rest, bare) =>
                ScaladocImportScope.fallbacksFor(
                  scope,
                  isJava,
                  name,
                  rest,
                  bare,
                ),
            )
        }
      (context, fallbacksOf)
    }

  /**
   * Resolves an already-extracted scaladoc link to the distinct locations of every
   * candidate symbol. No cursor needed, so it also turns wiki links in rendered
   * docstrings into navigable ones (e.g. on hover); aggregating candidate locations
   * means the caller navigates only when the link is unambiguous.
   *
   * A member link resolves against the type that *declares* it, not the inheritance
   * chain, so an inherited member (`Child#fromParent`) fails safely via owner capping
   * rather than navigating elsewhere — a known limitation (scalameta/metals#3383).
   */
  def resolveLinkLocations(
      rawLink: String,
      fromPath: AbsolutePath,
      isScala3: Boolean,
      contextSymbols: => ContextSymbols = ContextSymbols.empty,
      // Fallback candidates for any link target, from the docstring's import scope —
      // used for the link and (for a member link) its owner cap (scalameta/metals#3383).
      fallbacksOf: String => ImportFallbacks = _ => ImportFallbacks.empty,
      // The documentation's OWN language, not the hovered file's — so a Java `Foo$`
      // is a type, not a Scaladoc value-force (scalameta/metals#3383).
      isJava: Boolean = false,
      // The docstring's own file when the caller knows it, so a same-file sibling
      // outranks an import even if the enclosing symbol can't resolve (scalameta/metals#3383).
      knownDocstringFile: Option[AbsolutePath] = None,
  ): List[Location] = {
    val context = contextSymbols
    def fileOf(loc: Location): Option[AbsolutePath] =
      Try(loc.getUri.toAbsolutePath).toOption
    // The docstring's own file drives same-compilation-unit precedence.
    val docstringFile = knownDocstringFile.orElse(
      context.enclosingSymbol
        .flatMap(sym =>
          resolveSymbol(sym, fromPath).flatMap(_.locations.asScala.headOption)
        )
        .flatMap(fileOf)
    )
    def resolveContextFor(
        link: String,
        ctx: ContextSymbols,
    ): List[Location] =
      resolveGroups(
        ScalaDocLink(link, isScala3, isJava).toScalaMetaSymbolGroups(ctx),
        fromPath,
        isScala3,
      )
    def resolveCands(candidates: List[String]): List[Location] =
      candidates
        .flatMap(resolveFallback(_, fromPath, isScala3, isJava))
        .distinct

    // A member link's simple owner (`Child#m`): its binding caps where the member
    // may be found, so an off-owner member fails safely (scalameta/metals#3383).
    val ownerLink: Option[String] =
      MetalsSymbolLink.memberLinkOwner(rawLink, isJava)
    lazy val fullFallbacks = fallbacksOf(rawLink)
    lazy val ownerFallbacks = ownerLink.map(fallbacksOf)

    // One rung of the binding ladder: `Some(locs)` decides (`Nil` = owner binds here
    // but the member doesn't, a safe failure); `None` descends (scalameta/metals#3383).
    def rung(
        full: => List[Location],
        owner: => List[Location],
    ): Option[List[Location]] = {
      val resolved = full
      if (resolved.nonEmpty) Some(resolved)
      else if (ownerLink.isDefined && owner.nonEmpty) Some(List.empty)
      else None
    }
    def ownerAt(ctx: ContextSymbols): List[Location] =
      ownerLink.map(resolveContextFor(_, ctx)).getOrElse(List.empty)

    // Binding precedence: enclosing/local or same-unit sibling first, then the file's
    // imports, then a same-package sibling from another file, then the implicit scope.
    // With the file unknown, same-package candidates count as other-file (scalameta/metals#3383).
    val enclosingCtx = context.copy(enclosingPackagePath = None)
    val packageCtx =
      context.copy(enclosingSymbol = None, alternativeEnclosingSymbol = None)
    def unitsOf(locs: List[Location]): (List[Location], List[Location]) =
      docstringFile match {
        case Some(file) => locs.partition(loc => fileOf(loc).contains(file))
        case None => (List.empty[Location], locs)
      }
    lazy val (sameUnit, otherUnit) =
      unitsOf(resolveContextFor(rawLink, packageCtx))
    lazy val (ownerSameUnit, ownerOtherUnit) = unitsOf(ownerAt(packageCtx))

    // The file's imports, scope-aware: a binding is `(scopeIndex, isExplicit, locs)`.
    // A nearer scope shadows a farther one only at equal-or-higher precedence, so an
    // outer explicit and inner wildcard stay mutually ambiguous (scalameta/metals#3383).
    type Binding = (Int, Boolean, List[Location])
    def rankOf(b: Binding): Int = b match {
      case (scope, explicit, _) => -scope * 2 + (if (explicit) 1 else 0)
    }
    def importBindings(fb: ImportFallbacks): List[Binding] =
      fb.importScopes.zipWithIndex.flatMap {
        case ((explicit, wildcard), scope) =>
          val e = resolveCands(explicit)
          val w = resolveCands(wildcard)
          (if (e.nonEmpty) List((scope, true, e)) else Nil) ++
            (if (w.nonEmpty) List((scope, false, w)) else Nil)
      }
    def shadows(y: Binding, x: Binding): Boolean = {
      val (j, qExplicit, _) = y
      val (i, pExplicit, _) = x
      (j < i && (qExplicit || !pExplicit)) ||
      (j == i && qExplicit && !pExplicit)
    }
    def survivors(bindings: List[Binding]): List[Binding] =
      bindings.filter(x => !bindings.exists(y => (y ne x) && shadows(y, x)))
    def scalaImports: Option[List[Location]] = {
      val full = survivors(importBindings(fullFallbacks))
      def fullLocs = full.flatMap(_._3).distinct
      ownerFallbacks match {
        case None => if (full.nonEmpty) Some(fullLocs) else None
        case Some(ofb) =>
          val owner = survivors(importBindings(ofb))
          val ownerLocs = owner.flatMap(_._3).distinct
          if (owner.isEmpty) if (full.nonEmpty) Some(fullLocs) else None
          // If the owner name itself binds ambiguously it can't be referred to, so
          // the member link fails safely instead of guessing (scalameta/metals#3383).
          else if (ownerLocs.lengthCompare(1) > 0) Some(List.empty)
          else {
            val ownerRank = owner.map(rankOf).max
            val accepted = full.filter(rankOf(_) >= ownerRank)
            if (accepted.nonEmpty) Some(accepted.flatMap(_._3).distinct)
            else Some(List.empty)
          }
      }
    }

    // Java: one file-level scope; `import p.*` and the implicit `java.lang.*` are
    // equal-precedence on-demand imports (a name in both is ambiguous), while an
    // explicit single import and a same-package sibling outrank them (scalameta/metals#3383).
    def explicitCands(fb: ImportFallbacks): List[String] =
      fb.importScopes.flatMap(_._1)
    def onDemandCands(fb: ImportFallbacks): List[String] =
      fb.importScopes.flatMap(_._2) ++ fb.implicitImports
    def ownerCands(select: ImportFallbacks => List[String]): List[Location] =
      ownerFallbacks.map(ofb => resolveCands(select(ofb))).getOrElse(List.empty)

    val ladder: List[() => Option[List[Location]]] =
      if (isJava)
        List(
          () =>
            rung(
              resolveContextFor(rawLink, enclosingCtx),
              ownerAt(enclosingCtx),
            ),
          () => rung(sameUnit, ownerSameUnit),
          () =>
            rung(
              resolveCands(explicitCands(fullFallbacks)),
              ownerCands(explicitCands),
            ),
          () => rung(otherUnit, ownerOtherUnit),
          () =>
            rung(
              resolveCands(onDemandCands(fullFallbacks)),
              ownerCands(onDemandCands),
            ),
        )
      else
        List(
          () =>
            rung(
              resolveContextFor(rawLink, enclosingCtx),
              ownerAt(enclosingCtx),
            ),
          () => rung(sameUnit, ownerSameUnit),
          () => scalaImports,
          () => rung(otherUnit, ownerOtherUnit),
          () =>
            rung(
              resolveCands(fullFallbacks.implicitImports),
              ownerCands(_.implicitImports),
            ),
        )

    ladder.iterator
      .map(_())
      .collectFirst { case Some(locations) => locations }
      .getOrElse(List.empty)
  }

  private def resolveFallback(
      fqn: String,
      fromPath: AbsolutePath,
      isScala3: Boolean,
      isJava: Boolean,
  ): List[Location] =
    resolveGroups(
      ScalaDocLink(fqn, isScala3, isJava)
        .toScalaMetaSymbolGroups(ContextSymbols.empty),
      fromPath,
      isScala3,
    )

  private def resolveGroups(
      groups: List[List[ScalaDocLinkSymbol]],
      fromPath: AbsolutePath,
      isScala3: Boolean,
  ): List[Location] = {
    val direct = resolveGroupsRaw(groups, fromPath, isScala3)
    if (direct.nonEmpty) direct
    else resolveBoundaryVariants(groups, fromPath)
  }

  private def resolveGroupsRaw(
      groups: List[List[ScalaDocLinkSymbol]],
      fromPath: AbsolutePath,
      isScala3: Boolean,
  ): List[Location] =
    groups
      // Within a group, take the precedence winner; across groups, aggregate the
      // distinct locations so we only navigate when the link is unambiguous. Scala
      // 2 Scaladoc resolves an ambiguous `[[Name]]` type-before-value; Scala 3
      // Scaladoc instead binds the entity FIRST in source order, so there we order
      // a same-file companion pair by definition position (see sourceFirstLocations).
      .flatMap(group =>
        if (isScala3) sourceFirstLocations(group, fromPath)
        else
          group
            .collectFirst(scala.Function.unlift(search(_, fromPath)))
            .toList
            .flatMap(_.locations.asScala)
      )
      .distinct

  /**
   * The Scala 3 "first in source order" winner of a precedence-ordered group: when
   * several candidates resolve (a companion object + class), pick the earliest-defined
   * one, but only when they share a file, with ties broken by the original precedence
   * order (type first) (scalameta/metals#3383).
   */
  private def sourceFirstLocations(
      group: List[ScalaDocLinkSymbol],
      fromPath: AbsolutePath,
  ): List[Location] = {
    val resolved = group.flatMap { sym =>
      search(sym, fromPath).toList
        .map(_.locations.asScala.toList)
        .filter(_.nonEmpty)
    }
    resolved match {
      case Nil => Nil
      case single :: Nil => single
      case many =>
        val sameFile = many.map(_.head.getUri).distinct.lengthCompare(1) == 0
        if (sameFile)
          many.minBy { locs =>
            val start = locs.head.getRange.getStart
            (start.getLine, start.getCharacter)
          }
        else many.head
    }
  }

  /** The most boundary variants to resolve on one link, bounding the work. */
  private val maxBoundaryCandidates = 512

  /**
   * Boundary-recovery fallback when nothing resolved directly: retry the `/`<->`.`
   * assignments `guessFromPath` had to commit to (`a/b/util/Tool` -> `a/b/util.Tool`).
   * Variants resolve by descriptor-kind precedence (type before value before method),
   * aggregating within a kind and capping the count (scalameta/metals#3383).
   */
  private def resolveBoundaryVariants(
      groups: List[List[ScalaDocLinkSymbol]],
      fromPath: AbsolutePath,
  ): List[Location] = {
    val variants = groups.iterator
      .flatMap(_.iterator)
      .flatMap {
        case StringSymbol(symbol) =>
          boundaryVariants(symbol).iterator.map(StringSymbol(_))
        case MethodSymbol(prefix) =>
          boundaryVariants(prefix).iterator.map(MethodSymbol(_))
      }
      .take(maxBoundaryCandidates)
      .toList
    def resolvedOfKind(pred: ScalaDocLinkSymbol => Boolean): List[Location] =
      variants
        .filter(pred)
        .flatMap(search(_, fromPath).toList.flatMap(_.locations.asScala))
        .distinct
    val isType: ScalaDocLinkSymbol => Boolean = {
      case StringSymbol(symbol) => symbol.endsWith("#")
      case _ => false
    }
    val isValue: ScalaDocLinkSymbol => Boolean = {
      case StringSymbol(symbol) => !symbol.endsWith("#")
      case _ => false
    }
    val isMethod: ScalaDocLinkSymbol => Boolean = _.isInstanceOf[MethodSymbol]
    List(isType, isValue, isMethod).iterator
      .map(resolvedOfKind)
      .find(_.nonEmpty)
      .getOrElse(List.empty)
  }

  /**
   * The most `/`<->`.` boundary assignments to enumerate for one symbol,
   * mirroring [[maxOwnershipCombinations]]: a deeper chain falls back to the two
   * extremes (all-package and all-object) plus each single flip.
   */
  private val maxBoundaryCombinations = 64

  /**
   * Every `/`<->`.` assignment of a symbol's package/object boundaries, not just one
   * flip, so a chain of several wrong boundaries resolves. Boundaries inside a
   * backtick-escaped name and a trailing `.` descriptor are left untouched, and the
   * original assignment is dropped (scalameta/metals#3383).
   */
  private def boundaryVariants(symbol: String): List[String] = {
    val positions = boundaryPositions(symbol)
    if (positions.isEmpty) Nil
    else if ((1L << positions.length) > maxBoundaryCombinations) {
      val allPackage = setBoundaries(symbol, positions, '/')
      val allObject = setBoundaries(symbol, positions, '.')
      val singles =
        positions.map(p => symbol.updated(p, flipBoundary(symbol.charAt(p))))
      (allPackage :: allObject :: singles).filterNot(_ == symbol).distinct
    } else
      (0 until (1 << positions.length)).iterator
        .map(mask => setBoundaries(symbol, positions, mask))
        .filterNot(_ == symbol)
        .toList
        .distinct
  }

  private def flipBoundary(c: Char): Char = if (c == '/') '.' else '/'

  /** Sets every boundary at `positions` to `c`. */
  private def setBoundaries(
      s: String,
      positions: List[Int],
      c: Char,
  ): String = {
    val sb = new StringBuilder(s)
    positions.foreach(sb.setCharAt(_, c))
    sb.toString
  }

  /** Sets each boundary to `.` where its `mask` bit is set, else `/`. */
  private def setBoundaries(
      s: String,
      positions: List[Int],
      mask: Int,
  ): String = {
    val sb = new StringBuilder(s)
    positions.zipWithIndex.foreach { case (pos, bit) =>
      sb.setCharAt(pos, if ((mask & (1 << bit)) != 0) '.' else '/')
    }
    sb.toString
  }

  /**
   * The indices of the `/` and `.` package/object boundaries in a symbol, outside
   * any backtick-escaped name and excluding a trailing `.` (the value descriptor,
   * not a boundary).
   */
  private def boundaryPositions(symbol: String): List[Int] = {
    val buf = List.newBuilder[Int]
    var inBacktick = false
    var i = 0
    while (i < symbol.length) {
      symbol.charAt(i) match {
        case '`' => inBacktick = !inBacktick
        case ('/' | '.') if !inBacktick && i != symbol.length - 1 => buf += i
        case _ =>
      }
      i += 1
    }
    buf.result()
  }

  private def search(symbol: ScalaDocLinkSymbol, path: AbsolutePath) =
    symbol match {
      case method: MethodSymbol => findAllOverLoadedMethods(method, path)
      case StringSymbol(symbol) =>
        resolveSymbol(symbol, path).filter(_.symbol == symbol)
    }

  /**
   * Resolves a single candidate. Many speculative forms are tried per link and most
   * miss, so a failure here must never abort the others: a malformed symbol is
   * filtered and an exception is logged and treated as a miss (scalameta/metals#3383).
   */
  private def resolveSymbol(
      symbol: String,
      path: AbsolutePath,
  ): Option[DefinitionResult] =
    if (mtags.Symbol.validated(symbol).isLeft) None
    else
      Try(destinationProvider.fromSymbol(symbol, Some(path))) match {
        case Success(result) => result
        case Failure(error) =>
          scribe.debug(s"failed to resolve scaladoc candidate `$symbol`", error)
          None
      }

  private def findAllOverLoadedMethods(
      method: MethodSymbol,
      path: AbsolutePath,
  ) = {
    var ident: Int = 0
    val results: ListBuffer[DefinitionResult] = new ListBuffer
    var ok: Boolean = true
    while (ok) {
      val currentSymbol = method.symbol(ident)
      resolveSymbol(currentSymbol, path) match {
        case Some(value) if value.symbol == currentSymbol =>
          ident += 1
          results.addOne(value)
        case _ => ok = false
      }
    }

    if (results.isEmpty) None
    else
      Some(
        new DefinitionResult(
          results.toList.flatMap(_.locations.asScala).asJava,
          results.head.symbol,
          None,
          None,
          results.head.querySymbol,
        )
      )
  }

  private def extractScalaDocLinkAtPos(
      buffer: String,
      position: Position,
      isScala3: Boolean,
      isJava: Boolean,
  ) =
    for {
      tokens <- buffer.safeTokenize(Trees.defaultTokenizerDialect).toOption
      comment <- tokens.collectFirst {
        case token: Comment if token.pos.encloses(position) => token
      }
      if comment.text.startsWith("/**") && comment.text.endsWith("*/")
      offset = position.start - comment.start
      symbol <- ScalaDocLink.atOffset(comment.text, offset, isScala3, isJava)
    } yield symbol

  /**
   * The owner context at `pos` plus the deepest enclosing tree node and the dotted
   * enclosing package — all source go-to-definition needs to compute the same import
   * fallbacks hover uses, so both paths share one analysis (scalameta/metals#3383).
   */
  private def getContext(
      path: AbsolutePath,
      pos: Position,
      isScala3: Boolean,
  ): (ContextSymbols, Option[scala.meta.Tree], String) = {
    // Encode a declaration name as its SemanticDB descriptor, matching the hover
    // path. SemanticDB backticks by CHARACTERS (a dotted/`<init>` name is wrapped),
    // not by keyword (`type` stays `type`), so it's dialect-independent (scalameta/metals#3383).
    def descName(value: String): String = {
      def plain(c: Char) = c.isLetterOrDigit || c == '_' || c == '$'
      def operator(c: Char) = "!#%&*+-/:<=>?@\\^|~".contains(c)
      if (value.nonEmpty && (value.forall(plain) || value.forall(operator)))
        value
      else s"`$value`"
    }
    def extractName(ref: Term): String =
      ref match {
        case Term.Select(qual, name) =>
          s"${extractName(qual)}/${descName(name.value)}"
        case name: Term.Name => descName(name.value)
        case _ => ""
      }

    // The Scala 3 synthetic object owning this file's top-level members
    // (`Main.scala` → `Main$package`), matching hover (scalameta/metals#3383).
    val filePackageObject: String = {
      val filename = path.filename
      val dot = filename.lastIndexOf('.')
      val stem = if (dot > 0) filename.substring(0, dot) else filename
      descName(s"$stem$$package")
    }

    def enclosedChild(tree: Tree): Option[Tree] =
      tree.children
        .find { child =>
          child.pos.start <= pos.start && pos.start <= child.pos.end
        }

    // The owner context contributed by ONE tree node: a package extends the path, a
    // template/case/given becomes the enclosing symbol (companion as alternative),
    // everything else is transparent, mirroring the indexer (scalameta/metals#3383).
    def contextOf(
        tree: Tree,
        enclosingPackagePath: String,
        enclosingSymbol: String,
        alternativeEnclosingSymbol: Option[String],
    ): (String, String, Option[String]) =
      tree match {
        case Pkg(name, _) =>
          (
            s"$enclosingPackagePath${extractName(name)}/",
            enclosingSymbol,
            None,
          )
        case d: Pkg.Object =>
          // A package object extends its package by its own name and owns a
          // `package.` template, matching the indexer (scalameta/metals#3383).
          (
            s"$enclosingPackagePath${descName(d.name.value)}/",
            s"${enclosingSymbol}package.",
            None,
          )
        case d: Defn.Object =>
          (
            enclosingPackagePath,
            s"$enclosingSymbol${descName(d.name.value)}.",
            None,
          )
        case d: Defn.Class =>
          (
            enclosingPackagePath,
            s"$enclosingSymbol${descName(d.name.value)}#",
            None,
          )
        case d: Defn.Trait =>
          (
            enclosingPackagePath,
            s"$enclosingSymbol${descName(d.name.value)}#",
            None,
          )
        case d: Defn.Enum =>
          (
            enclosingPackagePath,
            s"$enclosingSymbol${descName(d.name.value)}#",
            Some(s"$enclosingSymbol${descName(d.name.value)}."),
          )
        case d: Defn.EnumCase =>
          // Enum cases live in the enum's COMPANION, so build off its alternative
          // (`E.`), not its type; a parameterized case is a case class (`Case#`), a
          // bare case a value (`Case.`) (scalameta/metals#3383).
          val base = alternativeEnclosingSymbol.getOrElse(enclosingSymbol)
          val name = descName(d.name.value)
          if (d.ctor.paramss.flatten.nonEmpty)
            (
              enclosingPackagePath,
              s"$base$name#",
              Some(s"$base$name."),
            )
          else
            (
              enclosingPackagePath,
              s"$base$name.",
              Some(s"$base$name#"),
            )
        case d: Defn.Given if d.name.value.nonEmpty =>
          // A NAMED given owns members under its type form (`name#`) but is a value
          // (`name.`); offer both so either link resolves (scalameta/metals#3383).
          (
            enclosingPackagePath,
            s"$enclosingSymbol${descName(d.name.value)}#",
            Some(s"$enclosingSymbol${descName(d.name.value)}."),
          )
        case (_: Defn.Def | _: Defn.Val | _: Defn.Var | _: Defn.Type)
            if isScala3 && enclosingSymbol.isEmpty =>
          // A Scala 3 top-level member compiles into the file's synthetic
          // `<file>$package` object, so its relative links resolve against that owner
          // (package-relative; `ContextSymbols` prepends the package) (scalameta/metals#3383).
          (
            enclosingPackagePath,
            s"$filePackageObject.",
            None,
          )
        // An ANONYMOUS given has no syntactic name, and this tree-only path can't
        // reconstruct the compiler's synthetic `given_<type>` symbol, so fall through
        // rather than build a bogus owner — a known gap (scalameta/metals#3383).
        case _ =>
          (enclosingPackagePath, enclosingSymbol, alternativeEnclosingSymbol)
      }

    def loop(
        tree: Tree,
        enclosingPackagePath: String = "",
        enclosingSymbol: String = "",
        alternativeEnclosingSymbol: Option[String] = None,
    ): (String, String, Option[String], Tree) = {
      val (
        enclosingPackagePath1,
        enclosingSymbol1,
        alternativeEnclosingSymbol1,
      ) =
        contextOf(
          tree,
          enclosingPackagePath,
          enclosingSymbol,
          alternativeEnclosingSymbol,
        )
      enclosedChild(tree)
        .map(
          loop(
            _,
            enclosingPackagePath1,
            enclosingSymbol1,
            alternativeEnclosingSymbol1,
          )
        )
        .getOrElse {
          // The documented member is the first child following the docstring comment.
          // Apply ITS OWN context (relative links resolve against the member) and
          // return it as the node so the import-scope walk starts there (scalameta/metals#3383).
          tree.children.find(_.pos.start >= pos.start) match {
            case Some(member0) =>
              // Recent scalameta wraps package statements in a `Pkg.Body`; descend
              // through it so a top-level member keeps its owner (scalameta/metals#3383).
              val member = member0 match {
                case body: Pkg.Body =>
                  body.children
                    .find(_.pos.start >= pos.start)
                    .getOrElse(member0)
                case other => other
              }
              val (pkg, sym, alt) =
                contextOf(
                  member,
                  enclosingPackagePath1,
                  enclosingSymbol1,
                  alternativeEnclosingSymbol1,
                )
              (pkg, sym, alt, member)
            case None =>
              (
                enclosingPackagePath1,
                enclosingSymbol1,
                alternativeEnclosingSymbol1,
                tree,
              )
          }
        }
    }

    trees
      .get(path)
      .map { tree =>
        val (
          enclosingPackagePath,
          enclosingSymbol,
          alternativeEnclosingSymbol,
          enclosingNode,
        ) =
          loop(tree)
        (
          ContextSymbols(
            enclosingPackagePath,
            enclosingSymbol,
            alternativeEnclosingSymbol,
          ),
          Some(enclosingNode),
          enclosingPackagePath.stripSuffix("/").replace('/', '.'),
        )
      }
      .getOrElse((ContextSymbols.empty, None, ""))

  }

}

case class ScalaDocLink(
    rawSymbol: String,
    isScala3: Boolean,
    isJava: Boolean = false,
) {

  // Normalize Javadoc syntax so the link resolves against SemanticDB: drop a leading
  // `module/`, treat a `##fragment` anchor as the type, and a leading `#` (`{@link
  // #foo}`) as `this.foo` (scalameta/metals#3383).
  private val (symbolText: String, isLeadingHash: Boolean) = {
    val withoutModule = ScalaDocLink.stripModulePrefix(rawSymbol)
    val withoutFragment = ScalaDocLink.stripDocFragment(withoutModule)
    if (withoutFragment.startsWith("#"))
      ("this." + withoutFragment.drop(1), true)
    else (withoutFragment, false)
  }

  def toScalaMetaSymbols(
      contextSymbols: => ContextSymbols
  ): List[ScalaDocLinkSymbol] =
    toScalaMetaSymbolGroups(contextSymbols).flatten

  /**
   * Candidate symbols grouped by interpretation (object member vs nested type,
   * keyword-wrapped vs raw, …), precedence-ordered within a group. A resolver picks
   * the winner per group, then treats differing groups as ambiguous (scalameta/metals#3383).
   */
  def toScalaMetaSymbolGroups(
      contextSymbols: => ContextSymbols
  ): List[List[ScalaDocLinkSymbol]] =
    if (symbolText.isEmpty()) List.empty
    else {
      val (symbol0, symbolType) = symbolWithType
      fixPackages(symbol0).map(candidatesFor(_, symbolType, contextSymbols))
    }

  private def candidatesFor(
      symbol: String,
      symbolType: ScalaDocLink.SymbolType,
      contextSymbols: => ContextSymbols,
  ): List[ScalaDocLinkSymbol] = {
    val optIndexOfSlash =
      symbol.findIndicesOf(List('/')).headOption
    val withPrefixes: List[String] =
      optIndexOfSlash match {
        case Some(indexOfSlash) =>
          symbol.splitAt(indexOfSlash + 1) match {
            // raw symbol [[this.<symbol>]]: substitute `this.` for `enclosingSymbol`;
            // a same-class constructor (`#Foo`) wins over a like-named method (scalameta/metals#3383).
            case ("this/", rest) =>
              constructorPrefixes(rest, contextSymbols) ++
                contextSymbols.withThis(rest)
            // raw symbol [[package.<symbol>]], e.g. [[package.SomeObject.someMethod]]
            // we substitute `package.` for `enclosingPackagePath`
            case ("package/", rest) => contextSymbols.withPackage(rest)
            // the symbol has some package defined e.g. [[a.b.SomeThing]]
            // we search for `package.<symbol>` and `<symbol>`
            case _ => contextSymbols.withPackage(symbol) ++ List(symbol)
          }
        // symbol has no package defined e.g. [[someMethod]]
        // we search for [[this.<symbol>]] and [[package.<symbol>]]
        case None =>
          contextSymbols.withThis(symbol) ++
            contextSymbols.withPackage(symbol)
      }

    // For each prefix also try its package-object member form (`pkg/package.member`),
    // type-first so a class `pkg.Target` still beats a package-object value, while a
    // bare package-object member (`[[answer]]`) still resolves (scalameta/metals#3383).
    def expand(prefix: String): List[ScalaDocLinkSymbol] = {
      val paths = prefix :: packageObjectForms(prefix)
      symbolType match {
        case ScalaDocLink.SymbolType.Method => paths.map(MethodSymbol(_))
        case ScalaDocLink.SymbolType.Value =>
          paths.map(p => StringSymbol(s"$p.")) ++ paths.map(MethodSymbol(_))
        case ScalaDocLink.SymbolType.Type =>
          paths.map(p => StringSymbol(s"$p#"))
        case ScalaDocLink.SymbolType.Any =>
          paths.map(p => StringSymbol(s"$p#")) ++
            paths.map(p => StringSymbol(s"$p.")) ++
            paths.map(MethodSymbol(_))
      }
    }
    withPrefixes.flatMap(expand)
  }

  private def symbolWithType: (String, ScalaDocLink.SymbolType) = {
    // A `[...]` type-argument list is stripped (`Map[K, V]` → `Map`) and must not
    // turn the link into a method — only a `(...)` signature does; a `foo[T]` still
    // resolves via the value/method fallback of an `Any` link (scalameta/metals#3383).
    val base = MetalsSymbolLink.stripTypeArgs(symbolText)
    if (base.isEmpty) (symbolText, ScalaDocLink.SymbolType.Any)
    else
      base.findIndicesOf(List('(')).headOption match {
        case Some(index) =>
          (base.take(index), ScalaDocLink.SymbolType.Method)
        case None =>
          // A backslash-escaped `\!`/`\$` is a literal member name, not a force
          // suffix, so it resolves as an ordinary link (scalameta/metals#3383).
          val escaped =
            base.length >= 2 && base.charAt(base.length - 2) == '\\'
          base.last match {
            // The value-force `$` is Scaladoc-only: in Javadoc `$` is an ordinary
            // identifier char (`Foo$`), so it's not interpreted there. The type-force
            // `!` is never a Java identifier char, so it holds in both (scalameta/metals#3383).
            // e.g. [[a.b.Foo$]]
            // forces link to refer to a value (an object, a value, a given)
            case '$' if !escaped && !isJava =>
              (base.dropRight(1), ScalaDocLink.SymbolType.Value)
            // e.g. [[a.b.Foo!]]
            // forces link to refer to a type (a class, a type alias, a type member)
            case '!' if !escaped =>
              (base.dropRight(1), ScalaDocLink.SymbolType.Type)
            // no meaningful suffix, e.g. [[a.b.Foo]]
            // we search for types then values
            case _ => (base, ScalaDocLink.SymbolType.Any)
          }
      }
  }

  /**
   * Replace `.` with `\` for packages and wrap with backticks when needed.
   * e.g. a.b.c.A.O to a/b/c/A.O
   */
  private def fixPackages(symbol: String): List[String] = {
    // `Type#member` (a qualified member link, e.g. the Javadoc
    // `GsonBuilder#setPrettyPrinting()`): convert the type and member separately,
    // then join with the `#` descriptor. Otherwise `guessFromPath` backtick-wraps
    // the whole thing as a single identifier.
    val hash = separatorHash(symbol)
    if (hash >= 0) {
      val typePart = symbol.substring(0, hash)
      val rawMember = symbol.substring(hash + 1)
      // Javadoc references a constructor by the class's simple name (`Foo#Foo`),
      // but SemanticDB names constructors `<init>`.
      val member =
        if (rawMember == simpleName(typePart)) "<init>" else rawMember
      val typePath = mtags.Symbol.guessFromPath(typePart, isScala3).value
      for {
        typeForm <- typeForms(typePath)
        memberForm <- memberForms(member)
      } yield typeForm + "#" + memberForm
    } else
      typeForms(mtags.Symbol.guessFromPath(symbol, isScala3).value)
  }

  /**
   * The descriptor forms to try for a member name. A Java member may be a Scala
   * keyword (`Thread#yield`) that `guessFromPath` backtick-wraps, but Java SemanticDB
   * stores it unwrapped, so try the raw name too (scalameta/metals#3383).
   */
  private def memberForms(member: String): List[String] = {
    val wrapped = mtags.Symbol.guessFromPath(member, isScala3).value
    if (wrapped != member && isPlainIdentifier(member)) List(wrapped, member)
    else List(wrapped)
  }

  private def isPlainIdentifier(s: String): Boolean =
    s.nonEmpty && s.forall(c => c.isLetterOrDigit || c == '_' || c == '$')

  /**
   * Index of the `#` separating a type from a member, or -1. It must follow an
   * identifier char (so operator members like `##` aren't split) and lie outside a
   * backtick-escaped name (so `` `Foo#Bar` `` isn't split) (scalameta/metals#3383).
   */
  private def separatorHash(symbol: String): Int = {
    var inBacktick = false
    var i = 0
    var result = -1
    while (i < symbol.length && result < 0) {
      symbol.charAt(i) match {
        case '`' => inBacktick = !inBacktick
        case '#'
            if !inBacktick && i > 0 && i + 1 < symbol.length &&
              isIdentifierChar(symbol(i - 1)) =>
          result = i
        case _ =>
      }
      i += 1
    }
    result
  }

  /**
   * The interpretations to try for a converted type path: each object-member (`.`)
   * vs nested-type (`#`) boundary assignment, and keyword-wrapped vs unwrapped (a
   * Java segment may be a Scala keyword, unwrapped in Java SemanticDB) (scalameta/metals#3383).
   */
  private def typeForms(path: String): List[String] =
    keywordBases(path).flatMap(ownershipForms).distinct

  /** A path and, if it differs, its keyword-unwrapped form (see [[typeForms]]). */
  private def keywordBases(path: String): List[String] = {
    val unwrapped = unwrapKeywords(path)
    if (unwrapped == path) List(path) else List(path, unwrapped)
  }

  /**
   * A package-object member has the symbol `pkg/package.member`, which `guessFromPath`
   * can't produce from a dotted path, so for `pkg/member` also try that form
   * (resolving `List`/`Nil` etc.). A Scala 3 `File$package` top-level member stays
   * unresolvable (scalameta/metals#3383).
   */
  private def packageObjectForms(path: String): List[String] = {
    var inBacktick = false
    var lastSlash = -1
    var separatorAfterSlash = false
    var i = 0
    while (i < path.length) {
      path.charAt(i) match {
        case '`' => inBacktick = !inBacktick
        case '/' if !inBacktick => lastSlash = i; separatorAfterSlash = false
        case ('.' | '#') if !inBacktick => separatorAfterSlash = true
        case _ =>
      }
      i += 1
    }
    val member = if (lastSlash >= 0) path.substring(lastSlash + 1) else ""
    if (lastSlash < 0 || member.isEmpty || separatorAfterSlash) Nil
    else List(path.substring(0, lastSlash + 1) + "package." + member)
  }

  /**
   * The most object-member (`.`) vs nested-type (`#`) assignments to enumerate
   * for one type path. A budget rather than a boundary count, so adding one more
   * boundary doesn't fall off a cliff: every chain whose `2^boundaries` fits is
   * explored fully (64 covers six boundaries), and only deeper ones fall back to
   * the two extremes.
   */
  private val maxOwnershipCombinations = 64

  /**
   * Every object-member (`.`) vs nested-type (`#`) assignment of `path`'s type-level
   * dots, so a mixed chain (`Outer.Middle#Inner`) resolves, not just the extremes;
   * beyond `maxOwnershipCombinations` only the two extremes are tried (scalameta/metals#3383).
   */
  private def ownershipForms(path: String): List[String] = {
    val dots = dotIndices(path)
    if (dots.isEmpty) List(path)
    else if ((1L << dots.length) > maxOwnershipCombinations)
      List(path, replaceWithHash(path, dots.toSet))
    else
      (0 until (1 << dots.length)).toList.map { mask =>
        val chosen = dots.zipWithIndex.collect {
          case (idx, bit) if (mask & (1 << bit)) != 0 => idx
        }
        replaceWithHash(path, chosen.toSet)
      }
  }

  /** Indices of the object-member dots in a type path, ignoring backtick spans. */
  private def dotIndices(path: String): List[Int] = {
    val buf = List.newBuilder[Int]
    var inBacktick = false
    var i = 0
    while (i < path.length) {
      path.charAt(i) match {
        case '`' => inBacktick = !inBacktick
        case '.' if !inBacktick => buf += i
        case _ =>
      }
      i += 1
    }
    buf.result()
  }

  /** Replaces the characters at `indices` with the nested-type separator `#`. */
  private def replaceWithHash(path: String, indices: Set[Int]): String = {
    val sb = new StringBuilder(path)
    indices.foreach(sb.setCharAt(_, '#'))
    sb.toString
  }

  /**
   * Removes the backticks `guessFromPath` added around plain-identifier segments
   * (Scala keywords like `type`), since Java SemanticDB stores them unwrapped.
   * Backticks around names escaped for special characters (e.g. `` `My.Type` ``)
   * are kept.
   */
  private def unwrapKeywords(path: String): String = {
    val sb = new StringBuilder(path.length)
    var i = 0
    while (i < path.length) {
      if (path.charAt(i) == '`') {
        val end = path.indexOf('`', i + 1)
        if (end < 0) { sb.append(path.substring(i)); i = path.length }
        else {
          val content = path.substring(i + 1, end)
          if (isPlainIdentifier(content)) sb.append(content)
          else sb.append(path.substring(i, end + 1))
          i = end + 1
        }
      } else {
        sb.append(path.charAt(i))
        i += 1
      }
    }
    sb.toString
  }

  /** The simple (last `.`-separated) name of a dotted type path. */
  private def simpleName(typePath: String): String =
    typePath.substring(typePath.lastIndexOf('.') + 1)

  /**
   * For a leading-`#` link whose member is the enclosing class name (the Javadoc
   * same-class constructor `{@link #Foo(int)}`), the class's `<init>` prefixes, so it
   * resolves to the constructor, not a like-named member (scalameta/metals#3383).
   */
  private def constructorPrefixes(
      member: String,
      contextSymbols: ContextSymbols,
  ): List[String] =
    if (
      isLeadingHash &&
      contextSymbols.enclosingSymbol.exists(enclosingClassName(_) == member)
    )
      contextSymbols.withThis(
        mtags.Symbol.guessFromPath("<init>", isScala3).value
      )
    else Nil

  /**
   * The simple name of the type a SemanticDB symbol denotes, e.g. `a/O.Foo#` ->
   * `Foo` and the nested `a/Outer#Inner#` -> `Inner`.
   */
  private def enclosingClassName(symbol: String): String = {
    val withoutDescriptor = symbol.stripSuffix("#").stripSuffix(".")
    val boundary = List('/', '.', '#').map(withoutDescriptor.lastIndexOf(_)).max
    withoutDescriptor.substring(boundary + 1)
  }

  private def isIdentifierChar(c: Char): Boolean =
    c.isLetterOrDigit || c == '_' || c == '$' || c == '`'
}

object ScalaDocLink {

  /**
   * Drops a Java module prefix (`module/...`), absent from SemanticDB symbols. The
   * `/` must follow a Java identifier char and precede a package start (outside
   * backticks), so operator members like `BigDecimal./` are kept while
   * `foo_/java.lang.String` is stripped (scalameta/metals#3383).
   */
  private[metals] def stripModulePrefix(link: String): String = {
    def isModuleNameChar(c: Char): Boolean =
      Character.isJavaIdentifierPart(c)
    def isPackageStart(c: Char): Boolean =
      Character.isJavaIdentifierStart(c) || c == '`'
    var inBacktick = false
    var i = 0
    var slash = -1
    while (i < link.length && slash < 0) {
      link.charAt(i) match {
        case '`' => inBacktick = !inBacktick
        case '/'
            if !inBacktick && i > 0 && i + 1 < link.length &&
              isModuleNameChar(link.charAt(i - 1)) &&
              isPackageStart(link.charAt(i + 1)) =>
          slash = i
        case _ =>
      }
      i += 1
    }
    if (slash < 0) link else link.substring(slash + 1)
  }

  /**
   * Drops a Javadoc fragment anchor (`Type##fragment`), a link into rendered docs,
   * so it resolves to the type. The `##` must be flanked by an identifier char and a
   * fragment name, so `Any###` (member `#` + operator `##`) isn't mistaken (scalameta/metals#3383).
   */
  private[metals] def stripDocFragment(link: String): String = {
    def isIdentifierStart(c: Char): Boolean = c.isLetterOrDigit || c == '_'
    var inBacktick = false
    var i = 0
    var cut = -1
    while (i + 2 < link.length && cut < 0) {
      link.charAt(i) match {
        case '`' => inBacktick = !inBacktick
        case '#'
            if !inBacktick && i > 0 && link.charAt(i + 1) == '#' &&
              isIdentifierStart(link.charAt(i + 2)) &&
              (link.charAt(i - 1).isLetterOrDigit || link.charAt(
                i - 1
              ) == '_' ||
                link.charAt(i - 1) == '$' || link.charAt(i - 1) == '`') =>
          cut = i
        case _ =>
      }
      i += 1
    }
    if (cut < 0) link else link.substring(0, cut)
  }

  def atOffset(
      text: String,
      offset: Int,
      isScala3: Boolean,
      isJava: Boolean,
  ): Option[ScalaDocLink] = {
    // Java links with `{@link ...}`, Scala with `[[ ... ]]` — extract whichever the
    // doc language uses (Java falls back to `[[ ... ]]`). Both also treat a `@see`
    // block tag as a link, so a source click resolves those too (scalameta/metals#3383).
    val target =
      if (isJava)
        WikiLink
          .javadocAtOffset(text, offset)
          .orElse(WikiLink.atOffset(text, offset))
          .orElse(WikiLink.seeTagAtOffset(text, offset))
      else
        WikiLink
          .atOffset(text, offset)
          .orElse(WikiLink.seeTagAtOffset(text, offset))
    target.map(ScalaDocLink(_, isScala3, isJava))
  }

  sealed trait SymbolType
  object SymbolType {
    case object Method extends SymbolType
    case object Value extends SymbolType
    case object Type extends SymbolType
    case object Any extends SymbolType
  }
}

case class ContextSymbols(
    enclosingPackagePath: Option[String],
    enclosingSymbol: Option[String],
    alternativeEnclosingSymbol: Option[String],
) {
  def withThis(sym: String): List[String] =
    enclosingSymbol.map(_ ++ sym).toList ++ alternativeEnclosingSymbol
      .map(_ ++ sym)
      .toList
  // Qualified by the immediate enclosing package. Enclosing-package members rank
  // below the implicit scope (in `package a.b`, `[[String]]` is `java.lang.String`),
  // which the flat tiers don't model, so they're not searched (scalameta/metals#3383).
  def withPackage(sym: String): List[String] =
    enclosingPackagePath.map(_ ++ sym).toList
}

object ContextSymbols {
  def apply(
      enclosingPackagePath: String,
      enclosingSymbol: String,
      alternativeEnclosingSymbol: Option[String],
  ): ContextSymbols = {
    val packageSymbol1 =
      if (enclosingPackagePath.nonEmpty) enclosingPackagePath
      else "_empty_/"
    val thisSymbol1 =
      Option.when(enclosingSymbol.nonEmpty)(packageSymbol1 ++ enclosingSymbol)
    val thisSymbolAlt =
      alternativeEnclosingSymbol.map(packageSymbol1 ++ _)
    ContextSymbols(Some(packageSymbol1), thisSymbol1, thisSymbolAlt)
  }

  def empty: ContextSymbols = ContextSymbols(None, None, None)

  /**
   * Builds the context for resolving relative scaladoc links from the SemanticDB
   * symbol of the enclosing template (e.g. `a/b/Outer#`) and an optional
   * companion alternative (e.g. the object holding an enum's cases). The
   * alternative is supplied by the indexer only where it is semantically
   * correct, so plain classes do not accidentally resolve into their companion.
   */
  def fromSymbols(
      enclosingTemplate: String,
      alternative: Option[String],
  ): ContextSymbols = {
    val (pkg, symbol) = splitPackage(enclosingTemplate)
    ContextSymbols(pkg, symbol, alternative.map(splitPackage(_)._2))
  }

  /** Splits a SemanticDB symbol into its package prefix and remaining symbol. */
  private def splitPackage(symbol: String): (String, String) = {
    // The package prefix is the run of `name/` segments before the first descriptor.
    // Track backtick spans so escaped names aren't mis-split (scalameta/metals#3383).
    var inBacktick = false
    var lastSlash = -1
    var descriptor = -1
    var i = 0
    while (i < symbol.length && descriptor < 0) {
      symbol.charAt(i) match {
        case '`' => inBacktick = !inBacktick
        case '/' if !inBacktick => lastSlash = i
        case '.' | '#' if !inBacktick => descriptor = i
        case _ =>
      }
      i += 1
    }
    if (descriptor < 0) (symbol, "") // a bare package such as `a/b/`
    else if (lastSlash < 0) ("", symbol) // a top-level symbol, no package
    else
      (symbol.substring(0, lastSlash + 1), symbol.substring(lastSlash + 1))
  }

}

/**
 * The outcome of resolving a documentation link on click, so the handler can tell a
 * clean hit from a miss/ambiguity (worth user feedback) and a genuine failure
 * (already logged) rather than collapsing the last two (scalameta/metals#3383).
 */
sealed trait ScaladocLinkResolution
object ScaladocLinkResolution {
  case class Resolved(location: Location) extends ScaladocLinkResolution
  case object NotUnique extends ScaladocLinkResolution
  case object Failed extends ScaladocLinkResolution
}

sealed trait ScalaDocLinkSymbol {
  def showSymbol: String
}
case class StringSymbol(symbol: String) extends ScalaDocLinkSymbol {
  override def showSymbol: String = symbol
}
case class MethodSymbol(prefixSymbol: String) extends ScalaDocLinkSymbol {
  def symbol(i: Int): String =
    i match {
      case 0 => s"$prefixSymbol()."
      case _ => s"$prefixSymbol(+$i)."
    }
  override def showSymbol: String = s"$prefixSymbol(+n)."
}
