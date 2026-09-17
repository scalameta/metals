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
 * symbol indexes, once per compiler generation. Scalac resolves their members.
 */
trait PackageObjectMemberSearch { compiler: MetalsGlobal =>

  /**
   * Memo of a discovery that completed without cancellation, held for the
   * lifetime of this compiler. Discovery reads the classpath and workspace
   * symbol indexes, and neither is updated by an unsaved edit: saving a new
   * package object reindexes and compiles, and compiling restarts this
   * compiler, so the memo cannot outlive the state it was built from.
   */
  private var discoveredPackages: Option[List[Symbol]] = None

  private def packagesWithPackageObjects(
      isCancelled: () => Boolean
  ): List[Symbol] =
    discoveredPackages match {
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
        // the classpath search yields packages in an order the comparator
        // leaves unspecified, since every hit has the same `package.class`
        // filename; sort so that a name exposed by several package objects
        // is always offered in the same order
        val symbols = packages.iterator
          .takeWhile(_ => !requestCancelled())
          .flatMap(pkg => packageSymbolFromString(pkg))
          .toList
          .sortBy(_.fullName)
        // an interrupted discovery may be missing packages, do not cache it
        if (!isCancelled()) {
          discoveredPackages = Some(symbols)
          val durationMs = (System.nanoTime() - start) / 1000000
          logger.fine(
            s"discovered ${symbols.size} packages with package objects on the classpath in ${durationMs}ms"
          )
        }
        symbols
    }

  /**
   * Offers to `visit` every member named `name` that a package object on the
   * classpath exposes, in both the type and the term namespace, paired with
   * the package class it is importable through.
   *
   * A member declared in (or inherited by) the package object of package
   * `doobie` is importable as `import doobie.<name>`, so auto-import must
   * render it through the package rather than through its declared owner. A
   * member exposed by several package objects is importable through each.
   */
  def searchPackageObjectMembers(
      name: String,
      context: Context,
      visit: (Symbol, Symbol) => Unit,
      isCancelled: () => Boolean
  ): Unit = {
    def isUniversalOwner(owner: Symbol): Boolean =
      owner == definitions.ObjectClass ||
        owner == definitions.AnyClass ||
        owner == definitions.AnyRefClass
    def isUniversalMember(sym: Symbol): Boolean =
      isUniversalOwner(sym.owner) ||
        sym.allOverriddenSymbols.exists(overridden =>
          isUniversalOwner(overridden.owner)
        )

    val encoded = NameTransformer.encode(name)
    val packages = packagesWithPackageObjects(isCancelled).iterator
    while (packages.hasNext && !isCancelled()) {
      val pkg = packages.next()
      try {
        for {
          // the term namespace first, matching the classfile search, so that
          // when a package object exposes both a type and a term of one name
          // the surviving candidate is the one `correctInTreeContext` can
          // judge in a call position; a single import covers both anyway
          member <- List(
            pkg.info.member(TermName(encoded)),
            pkg.info.member(TypeName(encoded))
          )
          sym <- member.alternatives
          if sym.exists && !sym.isErroneous
          // plain toplevel classes and nested packages live in the package
          // scope without belonging to the package object; classfile search
          // already discovers those
          if !sym.hasPackageFlag && !sym.owner.hasPackageFlag
          if !sym.isConstructor && !sym.isSynthetic && !sym.isArtifact
          if !isUniversalMember(sym)
          if context.isAccessible(sym, sym.info)
          if context.lookupSymbol(sym.name, _ => true).symbol != sym
        } visit(sym, pkg.moduleClass)
      } catch {
        // completing a package object reads classfiles from arbitrary jars,
        // which can fail with a linkage error rather than an exception
        case NonFatal(_) | (_: LinkageError) =>
      }
    }
  }
}
