package scala.meta.internal.metals

import scala.collection.mutable

import scala.meta._
import scala.meta.internal.docstrings.ImportFallbacks
import scala.meta.internal.docstrings.ImportLevel
import scala.meta.internal.docstrings.ImportScope

/**
 * Shared scaladoc import-scope analysis used by both the indexer and source
 * go-to-definition, so the two paths resolve links identically
 * (scalameta/metals#3383).
 */
object ScaladocImportScope {

  /**
   * Per-source memoization of each scope's parsed imports, so a doc-heavy file
   * doesn't re-parse importees for every link (was O(n²)). One-shot lookups can
   * use a throwaway cache (scalameta/metals#3383).
   */
  final class Cache {
    private[ScaladocImportScope] val scopes =
      mutable.Map.empty[Tree, List[RawImporter]]
  }

  /**
   * One import clause's parsed bindings, cached per scope independently of the
   * documented occurrence (scalameta/metals#3383).
   */
  private case class RawImporter(
      pos: Int,
      isAbsolute: Boolean,
      prefix: String,
      explicit: List[(String, String)],
      hasWildcard: Boolean,
      unimports: Set[String]
  )

  private def rawImportersOf(parent: Tree, cache: Cache): List[RawImporter] =
    cache.scopes.getOrElseUpdate(
      parent,
      parent.children.iterator
        .collect { case imp: Import => imp }
        .flatMap { imp =>
          val pos = imp.pos.start
          imp.importers.map { importer =>
            // A `_root_`/`_root_.` prefix (not `_root_foo`) is dropped to an
            // absolute path; an aliased prefix (`this`, `super`) stays verbatim,
            // a known limitation. Bindings keep `original.syntax` so escaped
            // imports resolve as one symbol (scalameta/metals#3383).
            val rawPrefix = importer.ref.syntax
            val isRootAnchored =
              rawPrefix == "_root_" || rawPrefix.startsWith("_root_.")
            // Bare `_root_` is the root, so its prefix is empty rather than the
            // literal `_root_` that `stripPrefix("_root_.")` would leave
            // (scalameta/metals#3383).
            val strippedPrefix =
              if (rawPrefix == "_root_") ""
              else rawPrefix.stripPrefix("_root_.")
            val explicit = List.newBuilder[(String, String)]
            val unimports = Set.newBuilder[String]
            var hasWildcard = false
            importer.importees.foreach {
              case Importee.Name(name) =>
                explicit += ((name.value, name.syntax))
              case Importee.Rename(name, rename) =>
                explicit += ((rename.value, name.syntax))
                // A rename also hides the original name from this importer's own
                // wildcard (`import p.{X => Y, _}` binds `Y`, not `X`).
                unimports += name.value
              case _: Importee.Wildcard => hasWildcard = true
              case Importee.Unimport(name) => unimports += name.value
              case _ =>
            }
            RawImporter(
              pos,
              isRootAnchored,
              strippedPrefix,
              explicit.result(),
              hasWildcard,
              unimports.result()
            )
          }
        }
        .toList
    )

  /**
   * The import scope lexically in effect at `tree`: nearer scopes shadow farther
   * ones, and only imports preceding the owner count (so siblings don't bleed).
   * `enclosingPackage` is the documented symbol's package, the anchor for
   * relative prefixes; each enclosing scope resolves against its own package
   * (`enclosingPackage` minus the intervening blocks) (scalameta/metals#3383).
   */
  def at(
      tree: Tree,
      enclosingPackage: String,
      cache: Cache
  ): ImportScope = {
    // Drop `rawRef`'s trailing segments from a dotted package (segment-aligned,
    // so `a.b` minus `b` is `a`; a mismatch leaves it untouched). Backticks are
    // stripped first since scalameta re-escapes keyword segments while the
    // SemanticDB anchor is decoded (scalameta/metals#3383).
    def dropPackageSuffix(pkg: String, rawRef: String): String = {
      val ref = rawRef.replace("`", "")
      if (pkg == ref) ""
      else if (pkg.endsWith("." + ref))
        pkg.substring(0, pkg.length - ref.length - 1)
      else pkg
    }

    // Pass 1: in-scope importers per enclosing scope, innermost first, each paired
    // with the package in effect there. Targets are materialized in pass 2 because
    // a prefix may itself be an alias bound in this or an outer scope, known only
    // once every scope's bindings are collected (scalameta/metals#3383).
    val perScope = List.newBuilder[List[RawImporter]]
    val perScopePackage = List.newBuilder[String]
    var scopePackage = enclosingPackage
    var child: Tree = tree
    var current: Option[Tree] = tree.parent
    while (current.isDefined) {
      // Climbing out of a package block drops it from the lexical prefix, so an
      // import in the outer scope of `package a { package b … }` resolves against
      // `a`, not the symbol's `a.b` (scalameta/metals#3383).
      child match {
        case pkg: Pkg =>
          scopePackage = dropPackageSuffix(scopePackage, pkg.ref.syntax)
        // A `package object q` adds `q` to the package but is a `Pkg.Object`, not
        // a `Pkg`, so leaving it must also drop `q` — else an import outside it
        // would resolve under `a.q` instead of `a` (scalameta/metals#3383).
        case obj: Pkg.Object =>
          scopePackage = dropPackageSuffix(scopePackage, obj.name.syntax)
        case _ =>
      }
      val parent = current.get
      val childStart = child.pos.start
      perScope += rawImportersOf(parent, cache).filter(_.pos < childStart)
      perScopePackage += scopePackage
      child = parent
      current = parent.parent
    }
    val importersByScope = perScope.result()
    val packageByScope = perScopePackage.result().toVector

    // A relative prefix resolves against the enclosing package first, so offer
    // that too (`import b.X` in `package a` → `a.b.X` and `b.X`); a `_root_` or
    // empty-package prefix is absolute. `pkg` is the scope's own package, so
    // outer-scope imports resolve where they are written (scalameta/metals#3383).
    def relativePrefixes(
        prefix: String,
        isAbsolute: Boolean,
        pkg: String
    ): List[String] =
      if (isAbsolute || pkg.isEmpty) List(prefix)
      else List(s"$pkg.$prefix", prefix)

    // Append a member to a resolved prefix; an empty prefix is the root, so the
    // member stands alone rather than gaining a leading dot (scalameta/metals#3383).
    def joinTarget(prefix: String, member: String): String =
      if (prefix.isEmpty) member else s"$prefix.$member"

    // Strip one enclosing pair of backticks from a path segment. Alias keys come
    // from `name.value` (never backticked), but a prefix head from `ref.syntax`
    // re-escapes keyword segments, so it must be unescaped before comparison
    // (scalameta/metals#3383).
    def unbacktick(segment: String): String =
      if (segment.length >= 2 && segment.head == '`' && segment.last == '`')
        segment.substring(1, segment.length - 1)
      else segment

    // The first `.` of `s` outside a backticked segment, or -1, so `u.syntax`
    // splits at `u` while a backticked segment with a dot stays whole
    // (scalameta/metals#3383).
    def firstUnescapedDot(s: String): Int = {
      var inBacktick = false
      var i = 0
      var found = -1
      while (i < s.length && found < 0) {
        s.charAt(i) match {
          case '`' => inBacktick = !inBacktick
          case '.' if !inBacktick => found = i
          case _ =>
        }
        i += 1
      }
      found
    }

    // Each scope's explicit bindings as `(pos, boundName, targets)` — the alias
    // table for expanding a prefix whose head is itself an alias. Positions keep
    // a same-scope alias visible only to later imports (scalameta/metals#3383).
    val aliasByScope: Array[List[(Int, String, List[String])]] =
      Array.fill(importersByScope.length)(Nil)

    // The targets alias `head` is bound to, visible to an import at `importPos` in
    // scope `from`: its own scope contributes only earlier aliases, enclosing
    // scopes all; the nearest binding scope wins (scalameta/metals#3383).
    def aliasTargets(
        head: String,
        from: Int,
        importPos: Int
    ): Option[List[String]] =
      aliasByScope.iterator.zipWithIndex
        .drop(from)
        .map { case (bindings, idx) =>
          val visible =
            if (idx == from)
              bindings.filter { case (pos, _, _) => pos < importPos }
            else bindings
          visible.collect {
            case (_, key, targets) if key == head => targets
          }.flatten
        }
        .collectFirst { case targets if targets.nonEmpty => targets }

    // The prefixes an import resolves against: if the leading segment is an alias,
    // expand it to the alias's targets and keep the rest of the path; otherwise
    // the relative + literal prefix (scalameta/metals#3383).
    def expandedPrefixes(
        prefix: String,
        isAbsolute: Boolean,
        scopeIndex: Int,
        importPos: Int
    ): List[String] = {
      val (head, rest) = firstUnescapedDot(prefix) match {
        case -1 => (prefix, "")
        case dot => (prefix.substring(0, dot), prefix.substring(dot))
      }
      aliasTargets(unbacktick(head), scopeIndex, importPos) match {
        case Some(targets) => targets.map(_ + rest)
        case None =>
          relativePrefixes(prefix, isAbsolute, packageByScope(scopeIndex))
      }
    }

    // Fill `aliasByScope` in dependency order — enclosing scopes first, then by
    // source position — so each alias expands against the aliases it can see and
    // chained aliases resolve fully. An alias references only earlier ones, so
    // there is no cycle (scalameta/metals#3383).
    importersByScope.indices.reverse.foreach { scopeIndex =>
      importersByScope(scopeIndex).foreach { importer =>
        importer.explicit.foreach { case (key, syntax) =>
          val targets =
            expandedPrefixes(
              importer.prefix,
              importer.isAbsolute,
              scopeIndex,
              importer.pos
            ).map(p => joinTarget(p, syntax))
          aliasByScope(scopeIndex) =
            aliasByScope(scopeIndex) :+ ((importer.pos, key, targets))
        }
      }
    }

    // Pass 2: materialize explicit and wildcard targets, expanding alias prefixes.
    // Scala wildcards never type-force a bare reference (scalameta/metals#3383).
    val explicitScopes = importersByScope.zipWithIndex.map {
      case (importers, scopeIndex) =>
        val scopeExplicit = mutable.Map.empty[String, List[String]]
        importers.foreach { importer =>
          importer.explicit.foreach { case (key, syntax) =>
            val targets =
              expandedPrefixes(
                importer.prefix,
                importer.isAbsolute,
                scopeIndex,
                importer.pos
              ).map(p => joinTarget(p, syntax))
            scopeExplicit(key) = scopeExplicit.getOrElse(key, Nil) ++ targets
          }
        }
        scopeExplicit.toMap
    }
    val wildcardScopes = importersByScope.zipWithIndex.map {
      case (importers, scopeIndex) =>
        importers.filter(_.hasWildcard).flatMap { importer =>
          expandedPrefixes(
            importer.prefix,
            importer.isAbsolute,
            scopeIndex,
            importer.pos
          ).distinct
            .map(resolved => (resolved, importer.unimports, false))
        }
    }
    // Keep each scope's explicit and wildcard imports together (innermost first),
    // dropping scopes that contribute neither, so the resolver can apply per-scope
    // precedence and spot cross-scope ambiguity (scalameta/metals#3383).
    val levels = explicitScopes.zip(wildcardScopes).collect {
      case (explicit, wildcards) if explicit.nonEmpty || wildcards.nonEmpty =>
        ImportLevel(explicit, wildcards)
    }
    ImportScope(levels)
  }

  /**
   * The fully-qualified fallback tiers for a bare leading name, from the import
   * scope plus the language's implicit scope (Scala: Predef, `scala`, `java.lang`;
   * Java: only `java.lang`). Explicit imports always apply; wildcard and implicit
   * scopes only for a bare/single-member reference. Backs both Scala and Java
   * markers and source go-to-definition (scalameta/metals#3383).
   */
  def fallbacksFor(
      scope: ImportScope,
      isJava: Boolean,
      name: String,
      rest: String,
      bareScope: Boolean
  ): ImportFallbacks = {
    val plain = name.replace("`", "")
    val scoped = bareScope && plain.nonEmpty
    // One (explicit, wildcard) pair per scope, innermost first, empty scopes
    // dropped. Wildcards apply only to a bare/single-member reference (`scoped`);
    // a type-forced wildcard (Java non-static on-demand) appends `!` so a bare
    // reference resolves only to a member type (scalameta/metals#3383).
    val importScopes = scope.levels
      .map { level =>
        val explicit = level.explicit.getOrElse(plain, Nil).map(_ + rest)
        val wildcards =
          if (scoped)
            level.wildcards.collect {
              case (prefix, hidden, typeForce) if !hidden(plain) =>
                val force = if (typeForce && rest.isEmpty) "!" else ""
                // An empty prefix is the root, so the name stands alone rather
                // than gaining a leading dot (scalameta/metals#3383).
                val dot = if (prefix.isEmpty) "" else "."
                s"$prefix$dot$name$rest$force"
            }
          else Nil
        (explicit, wildcards)
      }
      .filter { case (explicit, wildcards) =>
        explicit.nonEmpty || wildcards.nonEmpty
      }
    val implicits =
      if (!scoped) Nil
      else if (isJava) List(s"java.lang.$name$rest")
      else
        // Implicit imports shadow java.lang < scala < Predef, so precedence is
        // Predef, then scala, then java.lang (scalameta/metals#3383).
        List(
          s"scala.Predef.$name$rest",
          s"scala.$name$rest",
          s"java.lang.$name$rest"
        ) ++ collectionAliases.get(plain).map(_ + rest).toList
    ImportFallbacks(importScopes, implicits)
  }

  /**
   * Common `scala`/`Predef` aliases whose members live on the underlying
   * collection type, so a member link resolves against
   * `scala.collection.immutable.List` etc.; inherited factory members
   * (`List.apply`) still can't be found by direct lookup (scalameta/metals#3383).
   */
  val collectionAliases: Map[String, String] = {
    val immutable = "scala.collection.immutable"
    Map(
      "List" -> s"$immutable.List",
      "Nil" -> s"$immutable.Nil",
      "Seq" -> s"$immutable.Seq",
      "IndexedSeq" -> s"$immutable.IndexedSeq",
      "Iterable" -> s"$immutable.Iterable",
      "Vector" -> s"$immutable.Vector",
      "Stream" -> s"$immutable.Stream",
      "LazyList" -> s"$immutable.LazyList",
      "Range" -> s"$immutable.Range",
      "Map" -> s"$immutable.Map",
      "Set" -> s"$immutable.Set"
    )
  }

  /**
   * The dotted package of a SemanticDB `owner` symbol, or "" for the empty
   * package (e.g. `a/b/Foo#` and `a/b/` both yield `a.b`) (scalameta/metals#3383).
   */
  def packageOf(owner: String): String = {
    var inBacktick = false
    var lastSlash = -1
    var descriptor = -1
    var i = 0
    while (i < owner.length && descriptor < 0) {
      owner.charAt(i) match {
        case '`' => inBacktick = !inBacktick
        case '/' if !inBacktick => lastSlash = i
        case ('.' | '#') if !inBacktick => descriptor = i
        case _ =>
      }
      i += 1
    }
    val pkgPath = if (lastSlash >= 0) owner.substring(0, lastSlash) else ""
    if (pkgPath == "_empty_") "" else pkgPath.replace('/', '.')
  }
}
