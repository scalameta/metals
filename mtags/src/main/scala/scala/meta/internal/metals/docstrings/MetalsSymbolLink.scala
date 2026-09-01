package scala.meta.internal.docstrings

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Shared marker for scaladoc entity (wiki) links in rendered docstring markdown.
 *
 * mtags has no symbol resolver, so `MarkdownGenerator` emits each entity link with
 * a [[scheme]]-prefixed target and an encoded payload
 * `owner/alternative/isJava/scopes/docstringFile/docIsScala3/link-target` — the
 * docstring's structured resolution context (owner template, companion, language,
 * import scopes, own file and dialect), all known at index time. The server
 * materialises resolution candidates from it and rewrites each marker to a command
 * link on hover, or strips it elsewhere, so no broken link is ever shown.
 *
 * Every leaf is URL-encoded, so none contains the field separator `/` or the
 * structural delimiters `|~;=,`. The versioned scheme lets a marker an older server
 * can't parse degrade to plain text instead of mis-resolving (scalameta/metals#3383).
 */
object MetalsSymbolLink {
  // The U+E000 (private-use) prefix keeps user-typed links from colliding with this
  // marker: it can't appear in source, unlike bare `metals-wiki-link2`, a valid URL
  // scheme `MarkdownGenerator.isUrl` would treat as external (scalameta/metals#3383).
  val scheme: String = 0xe000.toChar.toString + "metals-wiki-link2:"

  /**
   * The markdown sequence `](` + [[scheme]] that opens a marker's target.
   * `MarkdownGenerator` escapes a label's own `]`, so this only occurs at a real
   * target; producer (`withDocScope`) and consumer (`Compilers.rewriteMarkerLinks`)
   * both anchor on it to stay in lockstep (scalameta/metals#3383).
   */
  val markerLinkOpen: String = "](" + scheme

  private val separator: String = "/"
  private val groupSep: Char = '|'
  private val levelSep: Char = '~'
  private val entrySep: Char = ';'
  private val fieldSep: Char = '='
  private val listSep: Char = ','

  def encode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name())

  def decode(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8.name())

  /**
   * Prepends the structured [[DocScope]] (owner, language, imports) to every
   * entity-link marker `MarkdownGenerator` emitted with only its link target, so the
   * server can resolve each link. Runs at index time (scalameta/metals#3383).
   */
  def withDocScope(rendered: String, docScope: DocScope): String =
    if (!rendered.contains(scheme)) rendered
    else {
      def opt(o: Option[String]): String = o.map(encode).getOrElse("")
      val prefix =
        opt(docScope.owner) + separator +
          opt(docScope.alternative) + separator +
          (if (docScope.isJava) "1" else "0") + separator +
          encodeScopes(docScope.imports.levels) + separator +
          opt(docScope.docstringFile) + separator +
          (if (docScope.docIsScala3) "1" else "0") + separator
      // Inject only at a real marker target ([[markerLinkOpen]]), never at a bare
      // `scheme` in label text; anchoring on `](`+scheme keeps this producer in
      // lockstep with the server consumer (scalameta/metals#3383).
      rendered.replace(markerLinkOpen, markerLinkOpen + prefix)
    }

  /**
   * `level|level`, each `explicit~wildcard`; explicit is `name=target,target;...`,
   * wildcard is `prefix=hidden,hidden=1;...` (all leaves encoded, so delimiter-free)
   * (scalameta/metals#3383).
   */
  private def encodeScopes(levels: List[ImportLevel]): String =
    levels
      .map(level =>
        encodeExplicitPart(level.explicit) +
          levelSep + encodeWildcardPart(level.wildcards)
      )
      .mkString(groupSep.toString)

  private def encodeExplicitPart(explicit: Map[String, List[String]]): String =
    explicit.iterator
      .map { case (name, targets) =>
        s"${encode(name)}$fieldSep${targets.map(encode).mkString(listSep.toString)}"
      }
      .mkString(entrySep.toString)

  private def encodeWildcardPart(
      wildcards: List[(String, Set[String], Boolean)]
  ): String =
    wildcards
      .map { case (prefix, hidden, typeForce) =>
        s"${encode(prefix)}$fieldSep${hidden.map(encode).mkString(listSep.toString)}$fieldSep${if (typeForce) "1" else "0"}"
      }
      .mkString(entrySep.toString)

  /**
   * The fallback tiers for a link target, shared by the resolver and go-to-definition
   * so both split the leading name identically. The explicit import is always tried;
   * the wildcard/implicit scopes only for a bare type (`Name`, `Name#m`) or a single
   * member access (`util.Tool`) — the `bareScope` flag (scalameta/metals#3383).
   */
  def fallbacksForTarget(
      target: String,
      isJava: Boolean,
      fallbacksFor: (String, String, Boolean) => ImportFallbacks
  ): ImportFallbacks =
    leadingIdentifier(target, isJava) match {
      case Some(name) =>
        val rest = target.substring(name.length)
        val bare = isTypeBoundary(target, name.length)
        // A single member access (one top-level `.segment`, e.g. `util.Tool`) is an
        // object member, not a package path; dots inside backticks (`` util.`a.b` ``)
        // or a signature (`util.tool(p.q.R)`) don't count (scalameta/metals#3383).
        val singleMember =
          rest.startsWith(".") && !hasTopLevelDot(rest, 1)
        fallbacksFor(name, rest, bare || singleMember)
      case None => ImportFallbacks.empty
    }

  /**
   * The simple owner name of a member-selecting link (`Child#m`, `Future.x`) when
   * that owner is a single bare name an import could rebind — used to confine member
   * selection to the owner's own binding, so a local `Foo` isn't lost to `import p._`.
   * Type arguments are stripped first (`Widget[Int]#paint`). A bare type, a force
   * suffix, an already-qualified path (`a.b.Foo#m`) or a signature yields None
   * (scalameta/metals#3383).
   */
  def memberLinkOwner(target: String, isJava: Boolean): Option[String] =
    leadingIdentifier(target, isJava).flatMap { name =>
      val rest = stripTypeArgs(target.substring(name.length))
      val isMemberSelect =
        rest.startsWith("#") ||
          (rest.startsWith(".") && !hasTopLevelDot(rest, 1))
      if (isMemberSelect) Some(name) else None
    }

  /**
   * Removes top-level type-argument groups `[...]` so a type application resolves
   * against its base type (`Map[K, V]` → `Map`); a `[`/`]` inside backticks is kept.
   * Shared by the resolver and the owner-cap so both strip identically
   * (scalameta/metals#3383).
   */
  def stripTypeArgs(s: String): String = {
    val out = new StringBuilder
    var inBacktick = false
    var depth = 0
    var i = 0
    while (i < s.length) {
      s.charAt(i) match {
        case '`' =>
          inBacktick = !inBacktick
          if (depth == 0) out.append('`')
        case '[' if !inBacktick => depth += 1
        case ']' if !inBacktick && depth > 0 => depth -= 1
        case c => if (depth == 0) out.append(c)
      }
      i += 1
    }
    out.toString
  }

  /**
   * Whether `s` has a `.` at or after `from` that is top-level — not inside backticks
   * (`` `a.b` ``) nor a balanced `(...)`/`[...]` signature (`bar(p.q.R)`)
   * (scalameta/metals#3383).
   */
  private def hasTopLevelDot(s: String, from: Int): Boolean = {
    var inBacktick = false
    var depth = 0
    var i = from
    var found = false
    while (i < s.length && !found) {
      s.charAt(i) match {
        case '`' => inBacktick = !inBacktick
        case '(' | '[' if !inBacktick => depth += 1
        case ')' | ']' if !inBacktick && depth > 0 => depth -= 1
        case '.' if !inBacktick && depth == 0 => found = true
        case _ =>
      }
      i += 1
    }
    found
  }

  /**
   * Whether the char after the leading name marks a bare type reference — a member
   * separator `#`, a signature `(`/`[`, or a force suffix `$`/`!`, so `[[Future!]]`
   * qualifies via the wildcard scope like `[[Future]]` (scalameta/metals#3383).
   */
  private def isTypeBoundary(s: String, i: Int): Boolean =
    i >= s.length || {
      val c = s.charAt(i)
      c == '#' || c == '(' || c == '[' || c == '$' || c == '!'
    }

  /**
   * The maximal leading identifier of `s`, if it starts with one; a backtick-escaped
   * name (`` `type` ``) is returned whole so a keyword import can still be qualified
   * (scalameta/metals#3383).
   */
  private def leadingIdentifier(s: String, isJava: Boolean): Option[String] = {
    def isStart(c: Char): Boolean = c.isLetter || c == '_' || c == '$'
    def isPart(c: Char): Boolean = c.isLetterOrDigit || c == '_' || c == '$'
    if (s.isEmpty) None
    else if (s.charAt(0) == '`') {
      val end = s.indexOf('`', 1)
      if (end < 0) None else Some(s.substring(0, end + 1))
    } else if (!isStart(s.charAt(0))) None
    else {
      var j = 1
      while (j < s.length && isPart(s.charAt(j))) j += 1
      // In Scala a trailing `$` on the whole target is the value-force suffix and is
      // dropped; in Java `$` is an ordinary identifier char, so `{@link Money$}` keeps
      // it or the explicit import key `Money$` would be missed (scalameta/metals#3383).
      if (!isJava && j == s.length && j > 1 && s.charAt(j - 1) == '$') j -= 1
      Some(s.substring(0, j))
    }
  }

  /**
   * Splits a marker payload into the structured [[DocScope]] and the (always
   * present) link target.
   */
  def parsePayload(payload: String): MarkerPayload = {
    def opt(s: String): Option[String] = Some(decode(s)).filter(_.nonEmpty)
    payload.split(separator, -1) match {
      case Array(
            owner,
            alternative,
            isJava,
            scopes,
            docstringFile,
            docIsScala3,
            target
          ) =>
        MarkerPayload(
          DocScope(
            opt(owner),
            opt(alternative),
            isJava == "1",
            ImportScope(decodeScopes(scopes)),
            opt(docstringFile),
            docIsScala3 == "1"
          ),
          decode(target)
        )
      case Array(target) =>
        MarkerPayload(DocScope.empty, decode(target))
      case _ =>
        MarkerPayload(DocScope.empty, decode(payload))
    }
  }

  private def decodeScopes(s: String): List[ImportLevel] =
    if (s.isEmpty) Nil
    else
      s.split(groupSep).toList.map { level =>
        level.split(levelSep.toString, -1) match {
          case Array(explicit, wildcards) =>
            ImportLevel(
              decodeExplicitPart(explicit),
              decodeWildcardPart(wildcards)
            )
          case _ => ImportLevel.empty
        }
      }

  private def decodeExplicitPart(s: String): Map[String, List[String]] =
    s.split(entrySep)
      .iterator
      .filter(_.nonEmpty)
      .map { entry =>
        val eq = entry.indexOf(fieldSep.toInt)
        val name = decode(entry.substring(0, eq))
        val targets = entry
          .substring(eq + 1)
          .split(listSep)
          .iterator
          .filter(_.nonEmpty)
          .map(decode)
          .toList
        name -> targets
      }
      .toMap

  private def decodeWildcardPart(
      s: String
  ): List[(String, Set[String], Boolean)] =
    s.split(entrySep)
      .iterator
      .filter(_.nonEmpty)
      .map { entry =>
        entry.split(fieldSep.toString, -1) match {
          case Array(prefix, hidden, force) =>
            (
              decode(prefix),
              hidden
                .split(listSep)
                .iterator
                .filter(_.nonEmpty)
                .map(decode)
                .toSet,
              force == "1"
            )
          case _ => (decode(entry), Set.empty[String], false)
        }
      }
      .toList
}

/**
 * The fully-qualified fallback candidates for a link. `importScopes` holds one
 * `(explicit, wildcard)` pair per lexical scope that binds the name, innermost
 * first; keeping a scope's explicit and wildcard together lets the resolver apply
 * per-scope precedence (an explicit import beats a wildcard within a scope, but an
 * outer explicit and inner wildcard don't shadow each other). `implicitImports` is
 * the lowest-precedence implicit scope (`scala`, `Predef`, `java.lang`)
 * (scalameta/metals#3383).
 */
case class ImportFallbacks(
    importScopes: List[(List[String], List[String])],
    implicitImports: List[String]
)
object ImportFallbacks {
  val empty: ImportFallbacks = ImportFallbacks(Nil, Nil)
}

/** A decoded marker payload (see [[MetalsSymbolLink]]). */
case class MarkerPayload(
    docScope: DocScope,
    target: String
)
