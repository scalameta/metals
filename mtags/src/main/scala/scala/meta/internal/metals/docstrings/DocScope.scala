package scala.meta.internal.docstrings

/**
 * One lexical scope's imports (`explicit` bindings + `(prefix, hidden,
 * typeForce)` wildcards), grouped so the resolver applies Scala's per-scope
 * binding precedence rather than shadowing across scopes (scalameta/metals#3383).
 */
case class ImportLevel(
    explicit: Map[String, List[String]],
    wildcards: List[(String, Set[String], Boolean)]
)
object ImportLevel {
  val empty: ImportLevel = ImportLevel(Map.empty, Nil)
}

/**
 * The lexical import scope at a documented occurrence: one [[ImportLevel]] per
 * enclosing scope, innermost first, keeping only scopes that contribute an
 * import (scalameta/metals#3383).
 */
case class ImportScope(levels: List[ImportLevel])
object ImportScope {
  val empty: ImportScope = ImportScope(Nil)
}

/**
 * Structured resolution context for a scaladoc/javadoc wiki link: the resolver
 * materialises candidates from this once instead of baking per-link strings into
 * every marker. `docstringFile` lets hover apply same-compilation-unit precedence
 * even when `owner` is synthetic (a Scala 3 `Foo$package.`) (scalameta/metals#3383).
 */
case class DocScope(
    owner: Option[String],
    alternative: Option[String],
    isJava: Boolean,
    imports: ImportScope,
    docstringFile: Option[String],
    // The docstring's OWN Scala dialect, not the hovered file's, so hovering a
    // Scala 2 library's docs applies Scala 2 (type-before-value) resolution.
    // False for Java (scalameta/metals#3383).
    docIsScala3: Boolean
)
object DocScope {
  val empty: DocScope =
    DocScope(None, None, isJava = false, ImportScope.empty, None, false)
}
