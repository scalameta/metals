package scala.meta.internal.pc

import java.nio.file.Path
import java.{util => ju}

import scala.collection.mutable
import scala.reflect.NameTransformer
import scala.util.control.NonFatal

import scala.meta.pc.SymbolSearchVisitor

import org.eclipse.{lsp4j => l}

/**
 * Searches members of package objects on the classpath, both declared in the
 * package object and inherited from its mixin parents (issue #2583).
 *
 * Libraries commonly expose their public API through a package object that
 * mixes in "aliases" traits, e.g. `package object doobie extends Aliases`
 * where a parent trait declares `type Transactor[M[_]] = ...` and
 * `val Transactor = ...`. Such members produce no classfiles, so
 * classfile-based search cannot discover them; the only authority on what a
 * package object exposes is the compiler itself, which unpickles the package
 * object's signature and enters its own and its parents' members into the
 * package scope (see the forced `openPackageModule` in `Compat`). This trait
 * asks exactly that question: for each package with a package object, does
 * `pkg.info.member(name)` resolve?
 *
 * Packages that have a package object are discovered through the injected
 * `SymbolSearch`: a package object always compiles to a `package.class`
 * classfile, so an exact search for the name `package` returns one classfile
 * hit per package object from the server's existing classpath index (which
 * also applies the user's excluded packages), plus one workspace-symbol hit
 * per package object defined in a workspace module. No classpath is scanned
 * here.
 *
 * The caches below are valid for a single edit/compilation generation. A
 * fresh compiler instance is constructed after every successful compilation
 * of this build target or one of its dependencies (`Compilers.didCompile`
 * restarts the presentation compiler, which discards the underlying global)
 * as well as on classpath changes. Within an instance's lifetime, outlined
 * workspace sources are typechecked into the same symbol table before a
 * request is served (`ScalaCompilerWrapper.compiler`), so member lookups can
 * observe not-yet-compiled source changes; [[resetPackageObjectMemberSearch]]
 * is therefore called from `didChange` to drop cached answers whenever a
 * source changes. Caching happens at three levels:
 *   - the discovered package symbols are cached once discovery completes
 *     without cancellation,
 *   - member lookups force `Symbol.info`, which the compiler itself memoizes,
 *   - resolved candidates are cached by name in [[packageObjectMemberCache]],
 *     so repeated requests for the same name skip the probe entirely.
 */
trait PackageObjectMemberSearch { compiler: MetalsGlobal =>

  /** Memo of a discovery that completed without cancellation. */
  private var discoveredPackageObjects: Option[List[Symbol]] = None

  /**
   * Drops all cached answers; called on `didChange` because outlined source
   * changes (e.g. an alias added to a workspace package object or one of its
   * parent traits) are visible to member lookups before any compilation, so
   * a cached miss could otherwise outlive the edit that fixes it.
   */
  def resetPackageObjectMemberSearch(): Unit = {
    discoveredPackageObjects = None
    packageObjectMemberCache.clear()
  }

  private def packagesWithPackageObjects(
      isCancelled: () => Boolean
  ): List[Symbol] =
    discoveredPackageObjects match {
      case Some(cached) => cached
      case None =>
        val start = System.nanoTime()
        val packages = new mutable.LinkedHashSet[String]()
        val requestCancelled = isCancelled
        val collector = new SymbolSearchVisitor {
          override def shouldVisitPackage(pkg: String): Boolean = true
          override def visitClassfile(pkg: String, filename: String): Int =
            if (filename == "package.class" && packages.add(pkg)) 1 else 0
          // package objects of workspace modules (e.g. a dependency on a
          // sibling sbt module) are not in the classpath index but are
          // reported as workspace symbols shaped `lib/package.`; their
          // classfiles are on this compiler's classpath, so the package
          // resolves like any library package below
          override def visitWorkspaceSymbol(
              path: Path,
              symbol: String,
              kind: l.SymbolKind,
              range: l.Range
          ): Int =
            if (
              symbol.endsWith("/package.") &&
              symbol != "_empty_/package." &&
              packages.add(symbol.stripSuffix("package."))
            ) 1
            else 0
          override def isCancelled: Boolean = requestCancelled()
        }
        search.search(
          "package",
          buildTargetIdentifier,
          ju.Optional.empty(),
          collector
        )
        val symbols = packages.iterator
          .takeWhile(_ => !requestCancelled())
          .flatMap(pkg => packageSymbolFromString(pkg))
          .toList
        // an interrupted discovery may be missing packages, do not cache it
        if (!isCancelled()) {
          discoveredPackageObjects = Some(symbols)
          val durationMs = (System.nanoTime() - start) / 1000000
          logger.info(
            s"discovered ${symbols.size} packages with package objects on the classpath in ${durationMs}ms"
          )
        }
        symbols
    }

  /**
   * Encoded member name -> (member, package classes whose package object
   * exposes it, in discovery order). Only name-dependent facts are cached;
   * context-dependent filters (accessibility, already in scope) are applied
   * per request.
   */
  private val packageObjectMemberCache =
    mutable.Map.empty[String, List[(Symbol, List[Symbol])]]

  /**
   * Offers to `visit` every member named `name` that a package object on the
   * classpath exposes, in both the type and the term namespace.
   *
   * Returns the visited symbols mapped to the package classes they are
   * importable through: a symbol declared in (or inherited by) the package
   * object of package `doobie` is importable as `import doobie.<name>`, so
   * auto-import must render it through the package rather than its declared
   * owner. A symbol exposed by several package objects is importable through
   * each of them.
   */
  def searchPackageObjectMembers(
      name: String,
      context: Context,
      visit: Symbol => Boolean,
      isCancelled: () => Boolean
  ): collection.Map[Symbol, List[Symbol]] = {
    if (isCancelled()) Map.empty[Symbol, List[Symbol]]
    else {
      val encoded = NameTransformer.encode(name)
      val candidates = packageObjectMemberCache.get(encoded) match {
        case Some(cached) => cached
        case None =>
          val computed = probePackageObjects(encoded, isCancelled)
          // an interrupted probe may be missing candidates, do not cache it
          if (!isCancelled()) {
            packageObjectMemberCache.update(encoded, computed)
          }
          computed
      }
      val result = mutable.LinkedHashMap.empty[Symbol, List[Symbol]]
      for {
        (sym, pkgClasses) <- candidates
        if context.isAccessible(sym, sym.info)
        if context.lookupSymbol(sym.name, _ => true).symbol != sym
      } {
        result.update(sym, pkgClasses)
        visit(sym)
      }
      result
    }
  }

  private def probePackageObjects(
      encoded: String,
      isCancelled: () => Boolean
  ): List[(Symbol, List[Symbol])] = {
    def isUniversalOwner(owner: Symbol): Boolean =
      owner == definitions.ObjectClass ||
        owner == definitions.AnyClass ||
        owner == definitions.AnyRefClass
    def isUniversalMember(sym: Symbol): Boolean =
      isUniversalOwner(sym.owner) ||
        sym.allOverriddenSymbols.exists(overridden =>
          isUniversalOwner(overridden.owner)
        )

    val candidates =
      mutable.LinkedHashMap.empty[Symbol, mutable.ListBuffer[Symbol]]
    val packages = packagesWithPackageObjects(isCancelled).iterator
    while (packages.hasNext && !isCancelled()) {
      val pkg = packages.next()
      try {
        for {
          member <- List(
            pkg.info.member(TypeName(encoded)),
            pkg.info.member(TermName(encoded))
          )
          sym <- member.alternatives
          if sym.exists && !sym.isErroneous
          // plain toplevel classes and nested packages live in the package
          // scope without belonging to the package object; classfile search
          // already discovers those
          if !sym.hasPackageFlag && !sym.owner.hasPackageFlag
          if !sym.isConstructor && !sym.isSynthetic && !sym.isArtifact
          if !isUniversalMember(sym)
        } {
          candidates.getOrElseUpdate(
            sym,
            mutable.ListBuffer.empty[Symbol]
          ) += pkg.moduleClass
        }
      } catch {
        case NonFatal(_) =>
      }
    }
    candidates.iterator.map { case (sym, pkgClasses) =>
      (sym, pkgClasses.toList)
    }.toList
  }
}
