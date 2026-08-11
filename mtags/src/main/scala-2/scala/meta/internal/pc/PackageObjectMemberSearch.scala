package scala.meta.internal.pc

import java.nio.file.Path
import java.{util => ju}

import scala.collection.mutable
import scala.reflect.NameTransformer
import scala.util.control.NonFatal

import scala.meta.pc.SymbolSearchVisitor

import org.eclipse.{lsp4j => l}

/**
 * Finds type and term members exposed by Scala 2 package objects, including
 * members inherited from mixin parents (issue #2583).
 *
 * Package objects are discovered through the existing classpath and workspace
 * symbol indexes. Scalac resolves their members, and the results are cached for
 * the lifetime of the compiler generation.
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
          logger.debug(
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
