package scala.meta.internal.mtags

import java.nio.file.Paths
import java.util.Optional

import scala.annotation.tailrec
import scala.collection.mutable
import scala.util.control.NonFatal

import scala.meta.inputs.Input
import scala.meta.inputs.Position
import scala.meta.internal.metals.Report
import scala.meta.internal.semanticdb.Implicits._
import scala.meta.internal.semanticdb.Language
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.SymbolInformation.Kind
import scala.meta.internal.semanticdb.SymbolInformation.Property
import scala.meta.internal.{semanticdb => s}
import scala.meta.pc.reports.ReportContext

/**
 * Indexes the declarations of a Kotlin source file with a hand-written scanner.
 *
 * Symbols follow the format of `semanticdb-kotlinc`: top-level functions and
 * properties are owned by the package, objects and companion objects are
 * types (`Name#`), and a property also defines its JVM getter and setter.
 * Function bodies and initializers are skipped, but their identifiers still
 * become fuzzy references when `includeFuzzyReferences` is set.
 */
class KotlinMtags(
    val input: Input.VirtualFile,
    includeMembers: Boolean,
    includeFuzzyReferences: Boolean = false
)(implicit rc: ReportContext)
    extends MtagsIndexer {
  import KotlinMtags._

  // Upstream SemanticDB has no Kotlin language; Mtags sets KOTLIN on the document.
  override def language: Language = Language.UNKNOWN_LANGUAGE

  override def overrides(): MtagsIndexer.AllOverrides = overridden.result()
  override def toplevelMembers(): MtagsIndexer.AllToplevelMembers =
    toplevelMembersBuilder.result()
  override def allIdentifiers: Set[String] = identifiers.result()

  private val overridden = List.newBuilder[(String, List[OverriddenSymbol])]
  private val toplevelMembersBuilder = List.newBuilder[ToplevelMember]
  private val identifiers = Set.newBuilder[String]
  private val text: String = input.value
  private val lexer = new Lexer(text)

  override def indexRoot(): Unit = {
    try parseFile()
    catch {
      case NonFatal(e) =>
        rc.unsanitized()
          .create(() =>
            new Report(
              "failed-index-kotlin",
              s"""|Kotlin indexer failed with an exception.
                  |```kotlin
                  |$text
                  |```
                  |""".stripMargin,
              "Kotlin indexer failed with an exception.",
              path = Optional.of(Paths.get(input.path).toUri()),
              id = Optional.of(input.path),
              error = Some(e)
            )
          )
    }
  }

  // ---------------------------------------------------------------- tokens

  private var peeked: Token = null
  private var previous: Token = Token.BOF
  private var beforePrevious: Token = Token.BOF

  private def next(): Token = {
    val t =
      if (peeked != null) { val p = peeked; peeked = null; p }
      else readToken()
    beforePrevious = previous
    previous = t
    t
  }

  private def peek(): Token = {
    if (peeked == null) peeked = readToken()
    peeked
  }

  /** Reads the next token and records identifiers as fuzzy references. */
  private def readToken(): Token = {
    var t = lexer.next()
    // Identifiers in string templates are references only; the parser skips them.
    while (lexer.lastFromTemplate && t.isInstanceOf[Token.Ident]) {
      recordReference(t.asInstanceOf[Token.Ident])
      t = lexer.next()
    }
    t match {
      case id: Token.Ident => recordReference(id)
      case _ =>
    }
    t
  }

  private def recordReference(id: Token.Ident): Unit =
    if (includeFuzzyReferences) {
      identifiers += id.name
      val range = pos(id).toRange
      var i = 0
      while (i < ReferenceSuffixes.length) {
        visitFuzzyReferenceOccurrence(
          s.SymbolOccurrence(
            symbol = id.name + ReferenceSuffixes(i),
            role = s.SymbolOccurrence.Role.REFERENCE,
            range = Some(range)
          )
        )
        i += 1
      }
    }

  private def pos(id: Token.Ident): Position =
    Position.Range(input, id.start, id.end)

  private def isSym(t: Token, c: Char): Boolean = t match {
    case Token.Sym(`c`, _) => true
    case _ => false
  }

  private def isIdent(t: Token, name: String): Boolean = t match {
    case id: Token.Ident => id.name == name
    case _ => false
  }

  private def isKeyword(t: Token, name: String): Boolean = t match {
    case Token.Keyword(`name`, _) => true
    case _ => false
  }

  // ---------------------------------------------------------------- regions

  /** A declaration scope: the file or the body of a class-like declaration. */
  private final class Region(
      val owner: String,
      val isTopLevel: Boolean,
      val isAnnotation: Boolean = false
  ) {
    val overloads = new OverloadDisambiguator(mutable.Map.empty)
    var hasSecondaryConstructor = false
  }

  private def emit[A](region: Region)(thunk: => A): A =
    withOwner(region.owner)(thunk)

  // ---------------------------------------------------------------- parser

  private def parseFile(): Unit = {
    var packageOwner = Symbols.EmptyPackage
    @tailrec
    def header(): Unit = {
      peek() match {
        case Token.Sym('@', _) =>
          next()
          skipAnnotation()
          header()
        case Token.Keyword("package", _) =>
          next()
          readQualifiedName().foreach { id => pkg(id.name, pos(id)) }
          packageOwner = currentOwner
          header()
        case id: Token.Ident if id.name == "import" =>
          next()
          readQualifiedName()
          if (isSym(peek(), '.')) { next(); next() } // `.*`
          if (isKeyword(peek(), "as")) { next(); next() }
          header()
        case _ =>
      }
    }
    header()
    val region = new Region(packageOwner, isTopLevel = true)
    currentOwner = packageOwner
    parseMembers(region, untilRBrace = false)
  }

  /** Parses declarations until EOF, or until the closing `}` of a body. */
  private def parseMembers(region: Region, untilRBrace: Boolean): Unit = {
    val modifiers = mutable.Set.empty[String]
    var continue = true
    while (continue) {
      val t = next()
      t match {
        case Token.EOF =>
          continue = false
        case Token.Sym('}', _) if untilRBrace =>
          continue = false
        case Token.Sym('@', _) =>
          skipAnnotation()
        case Token.Sym('{', _) =>
          skipBlock()
          modifiers.clear()
        case Token.Keyword("class", _) =>
          val kind =
            if (modifiers("annotation")) Kind.INTERFACE else Kind.CLASS
          parseClassLike(region, kind, modifiers.toSet)
          modifiers.clear()
        case Token.Keyword("interface", _) =>
          parseClassLike(region, Kind.INTERFACE, modifiers.toSet)
          modifiers.clear()
        case Token.Keyword("object", _) =>
          parseClassLike(region, Kind.OBJECT, modifiers.toSet)
          modifiers.clear()
        case Token.Keyword("fun", _) =>
          if (isKeyword(peek(), "interface")) {
            next()
            parseClassLike(region, Kind.INTERFACE, modifiers.toSet)
          } else parseFunction(region)
          modifiers.clear()
        case Token.Keyword("val", _) =>
          parseProperty(region, isVar = false)
          modifiers.clear()
        case Token.Keyword("var", _) =>
          parseProperty(region, isVar = true)
          modifiers.clear()
        case Token.Keyword("typealias", _) =>
          parseTypeAlias(region)
          modifiers.clear()
        case id: Token.Ident
            if id.name == "constructor" && !region.isTopLevel =>
          parseSecondaryConstructor(region)
          modifiers.clear()
        case id: Token.Ident
            if (id.name == "init" || id.name == "get" || id.name == "set") &&
              !isSym(beforePrevious, '.') =>
          skipAccessorOrInit()
          modifiers.clear()
        case id: Token.Ident if Modifiers.contains(id.name) =>
          modifiers += id.name
        case _ =>
          modifiers.clear()
      }
    }
  }

  private def parseClassLike(
      region: Region,
      kind: Kind,
      modifiers: Set[String]
  ): Unit = {
    val isCompanion = modifiers("companion")
    val name: Option[(String, Position)] = peek() match {
      case id: Token.Ident if id.name != "constructor" =>
        next()
        Some((id.name, pos(id)))
      case _ if isCompanion =>
        val at = previous match {
          case k: Token.Keyword => Position.Range(input, k.start, k.end)
          case _ => Position.Range(input, 0, 0)
        }
        Some(("Companion", at))
      case _ => None // anonymous object, e.g. `object : Runnable { ... }`
    }
    name match {
      case None =>
        skipHeader()
        if (isSym(peek(), '{')) { next(); skipBlock() }
      case Some((typeName, namePos)) =>
        // Members of a class are parsed only with `includeMembers`, so every
        // declaration that reaches this method is emitted.
        val classSymbol = emit(region)(
          tpe(typeName, namePos, kind, classProperties(kind, modifiers))
        )
        val body = new Region(
          classSymbol,
          isTopLevel = false,
          isAnnotation = modifiers("annotation")
        )
        val emitConstructors = includeMembers && kind != Kind.INTERFACE
        def emitPrimaryConstructor(): Unit =
          emit(body)(
            ctor(body.overloads.disambiguator(Constructor), namePos, 0)
          )
        if (isSym(peek(), '<')) { next(); skipBalanced('<', '>') }
        skipConstructorModifiers()
        val hasPrimaryParameters = isSym(peek(), '(')
        if (hasPrimaryParameters) {
          if (emitConstructors) emitPrimaryConstructor()
          next()
          parsePrimaryConstructorParameters(body, includeMembers)
        }
        val parents = parseSupertypes()
        if (parents.nonEmpty)
          overridden += ((
            classSymbol,
            parents.map(UnresolvedOverriddenSymbol(_))
          ))
        skipHeader()
        if (isSym(peek(), '{')) {
          next()
          val endedBody =
            modifiers("enum") && parseEnumEntries(body, includeMembers)
          if (!endedBody) {
            if (includeMembers) parseMembers(body, untilRBrace = true)
            else skipBlock()
          }
        }
        // Without `(...)`, the class has a default primary constructor unless
        // it declares secondary constructors only.
        if (
          emitConstructors && !hasPrimaryParameters &&
          !body.hasSecondaryConstructor
        ) emitPrimaryConstructor()
        currentOwner = region.owner
    }
  }

  private def classProperties(kind: Kind, modifiers: Set[String]): Int = {
    var props = 0
    if (modifiers("abstract")) props |= Property.ABSTRACT.value
    if (modifiers("sealed")) props |= Property.SEALED.value
    if (modifiers("enum")) props |= Property.ENUM.value
    val canBeFinal = kind == Kind.CLASS || kind == Kind.OBJECT
    if (
      canBeFinal && !modifiers("open") && !modifiers("abstract") &&
      !modifiers("sealed")
    ) props |= Property.FINAL.value
    props
  }

  /**
   * Skips `private constructor`, `@Inject constructor` and similar, also when
   * they are on the next line. Modifiers that are not followed by
   * `constructor` belong to the next member and stay.
   */
  private def skipConstructorModifiers(): Unit = {
    val startsConstructor = peek() match {
      case Token.Sym('@', _) => true
      case id: Token.Ident =>
        id.name == "constructor" || Modifiers.contains(id.name)
      case _ => false
    }
    if (startsConstructor && constructorFollows(startOf(peek()))) {
      var done = false
      while (!done) {
        next() match {
          case Token.Sym('@', _) => skipAnnotation()
          case id: Token.Ident if id.name == "constructor" => done = true
          case Token.EOF => done = true
          case _ =>
        }
      }
    }
  }

  /**
   * True when the annotations and modifiers at `start` end with the
   * `constructor` keyword. Reads ahead with a second lexer, so comments and
   * strings follow the normal rules.
   */
  private def constructorFollows(start: Int): Boolean = {
    val ahead = new Lexer(text, start)
    var t = ahead.next()
    var result: Option[Boolean] = None
    while (result.isEmpty) {
      t match {
        case Token.Sym('@', _) =>
          t = ahead.next() // annotation name or use-site target
          if (t.isInstanceOf[Token.Ident]) t = ahead.next()
          while (isSym(t, '.') || isSym(t, ':')) {
            ahead.next()
            t = ahead.next()
          }
          if (isSym(t, '(')) {
            var depth = 1
            while (depth > 0 && t != Token.EOF) {
              t = ahead.next()
              if (isSym(t, '(')) depth += 1
              else if (isSym(t, ')')) depth -= 1
            }
            t = ahead.next()
          }
        case id: Token.Ident if id.name == "constructor" =>
          result = Some(true)
        case id: Token.Ident if Modifiers.contains(id.name) =>
          t = ahead.next()
        case _ =>
          result = Some(false)
      }
    }
    result.get
  }

  private def startOf(t: Token): Int = t match {
    case id: Token.Ident => id.start
    case k: Token.Keyword => k.start
    case sym: Token.Sym => sym.start
    case _ => text.length
  }

  /**
   * Reads `(val a: Int, b: String = "")` after the opening parenthesis. A
   * `val` or `var` parameter is also a property of the class.
   */
  private def parsePrimaryConstructorParameters(
      body: Region,
      shouldEmit: Boolean
  ): Unit = {
    var done = false
    while (!done) {
      var propertyKind: Option[Boolean] = None // Some(isVar)
      var name: Option[Token.Ident] = None
      var inner = true
      while (inner) {
        next() match {
          case Token.Sym('@', _) => skipAnnotation()
          case Token.Keyword("val", _) => propertyKind = Some(false)
          case Token.Keyword("var", _) => propertyKind = Some(true)
          case id: Token.Ident if isSym(peek(), ':') =>
            name = Some(id)
            inner = false
          case Token.Sym(')', _) | Token.EOF =>
            inner = false
            done = true
          case _ =>
        }
      }
      for {
        isVar <- propertyKind
        id <- name
        if shouldEmit
      } emitProperty(body, id.name, pos(id), isVar)
      if (!done) {
        // Skip the type and the default value up to `,` or `)`.
        skipUntil(stopAtClosingParen = true) match {
          case Token.Sym(')', _) | Token.EOF => done = true
          case _ =>
        }
      }
    }
  }

  /** Reads `: A, B<T>(), C by delegate` and returns the type names. */
  private def parseSupertypes(): List[String] = {
    if (!isSym(peek(), ':')) return Nil
    next()
    val parents = List.newBuilder[String]
    var continue = true
    while (continue) {
      var last: Option[String] = None
      var inType = true
      while (inType) {
        peek() match {
          case Token.Sym('@', _) =>
            next()
            skipAnnotation()
          case id: Token.Ident if id.name != "by" && id.name != "where" =>
            next()
            last = Some(id.name)
            if (isSym(peek(), '.')) next() else inType = false
          case _ => inType = false
        }
      }
      last.foreach(parents += _)
      if (isSym(peek(), '<')) { next(); skipBalanced('<', '>') }
      if (isSym(peek(), '(')) { next(); skipBalanced('(', ')') }
      if (isIdent(peek(), "by")) {
        next()
        skipUntil(stopAtClosingParen = false) match {
          case Token.Sym(',', _) =>
          case _ => continue = false
        }
      } else if (isSym(peek(), ',')) next()
      else continue = false
    }
    parents.result()
  }

  /**
   * Reads the entries of an enum class body. Returns true when the closing
   * `}` was consumed, so the body has no other members.
   */
  private def parseEnumEntries(body: Region, shouldEmit: Boolean): Boolean = {
    var result: Option[Boolean] = None
    while (result.isEmpty) {
      peek() match {
        case Token.Sym('@', _) =>
          next()
          skipAnnotation()
        case id: Token.Ident =>
          next()
          if (shouldEmit)
            emit(body)(term(id.name, pos(id), Kind.FIELD, Property.ENUM.value))
          if (isSym(peek(), '(')) { next(); skipBalanced('(', ')') }
          if (isSym(peek(), '{')) { next(); skipBlock() }
        case Token.Sym(',', _) => next()
        case Token.Sym(';', _) =>
          next()
          result = Some(false)
        case Token.Sym('}', _) | Token.EOF =>
          next()
          result = Some(true)
        case _ => result = Some(false)
      }
    }
    result.get
  }

  private def parseFunction(region: Region): Unit = {
    if (isSym(peek(), '<')) { next(); skipBalanced('<', '>') }
    val name = readReceiverAndName()
    name.foreach { id =>
      emit(region)(
        method(id.name, region.overloads.disambiguator(id.name), pos(id), 0)
      )
    }
    if (isSym(peek(), '(')) { next(); skipBalanced('(', ')') }
    skipHeader()
    skipBody()
  }

  /** Skips a `{ ... }` or `= ...` body. */
  private def skipBody(): Unit =
    peek() match {
      case Token.Sym('{', _) =>
        next()
        skipBlock()
      case Token.Sym('=', _) =>
        next()
        skipExpression()
      case _ =>
    }

  private def parseProperty(region: Region, isVar: Boolean): Unit = {
    if (isSym(peek(), '<')) { next(); skipBalanced('<', '>') }
    if (isSym(peek(), '(')) {
      // Destructuring declaration, only valid as a local.
      next()
      skipBalanced('(', ')')
    } else {
      readReceiverAndName().foreach { id =>
        emitProperty(region, id.name, pos(id), isVar)
      }
    }
    skipHeader()
    peek() match {
      case Token.Sym('=', _) =>
        next()
        skipExpression()
      case id: Token.Ident if id.name == "by" =>
        next()
        skipExpression()
      case _ =>
    }
  }

  /** A property defines `name.`, its getter and, for `var`, its setter. */
  private def emitProperty(
      region: Region,
      name: String,
      at: Position,
      isVar: Boolean
  ): Unit = {
    val props = if (isVar) Property.VAR.value else Property.VAL.value
    emit(region)(term(name, at, Kind.METHOD, props))
    // An annotation parameter is a JVM method with the parameter's name.
    val getter = if (region.isAnnotation) name else getterName(name)
    emit(region)(
      method(getter, region.overloads.disambiguator(getter), at, 0)
    )
    if (isVar) {
      val setter = setterName(name)
      emit(region)(
        method(setter, region.overloads.disambiguator(setter), at, 0)
      )
    }
  }

  private def parseTypeAlias(region: Region): Unit = {
    peek() match {
      case id: Token.Ident =>
        next()
        val symbol = emit(region)(tpe(id.name, pos(id), Kind.TYPE, 0))
        if (region.isTopLevel)
          toplevelMembersBuilder += ToplevelMember(
            symbol,
            pos(id).toRange,
            ToplevelMember.Kind.Type
          )
      case _ =>
    }
    if (isSym(peek(), '<')) { next(); skipBalanced('<', '>') }
    if (isSym(peek(), '=')) { next(); skipExpression() }
  }

  private def parseSecondaryConstructor(region: Region): Unit = {
    val at = previous match {
      case id: Token.Ident => pos(id)
      case _ => Position.Range(input, 0, 0)
    }
    region.hasSecondaryConstructor = true
    if (includeMembers)
      emit(region)(ctor(region.overloads.disambiguator(Constructor), at, 0))
    if (isSym(peek(), '(')) { next(); skipBalanced('(', ')') }
    if (isSym(peek(), ':')) {
      next()
      next() // `this` or `super`
      if (isSym(peek(), '(')) { next(); skipBalanced('(', ')') }
    }
    if (isSym(peek(), '{')) { next(); skipBlock() }
  }

  /** Skips `init { }`, `get() = ...`, `set(value) { ... }`. */
  private def skipAccessorOrInit(): Unit = {
    if (isSym(peek(), '(')) { next(); skipBalanced('(', ')') }
    if (isSym(peek(), ':')) {
      next()
      skipType()
    }
    skipBody()
  }

  /**
   * Reads `name`, `Receiver.name`, `Map<K, V>.name` or `T?.name` and returns
   * the declared name: the last identifier before `(`, `:`, `=` or a new line.
   */
  private def readReceiverAndName(): Option[Token.Ident] = {
    var last: Option[Token.Ident] = None
    var continue = true
    while (continue) {
      peek() match {
        case id: Token.Ident =>
          next()
          last = Some(id)
          if (isSym(peek(), '<')) { next(); skipBalanced('<', '>') }
          if (isSym(peek(), '?')) next()
          if (isSym(peek(), '.')) next() else continue = false
        case Token.Sym('@', _) if last.isEmpty =>
          // An annotated receiver, e.g. `@receiver:Ann String.name`.
          next()
          skipAnnotation()
        case Token.Sym('(', _) if last.isEmpty =>
          // Function type receiver, e.g. `(A.() -> Unit).name` or `(() -> Unit)?.name`.
          next()
          skipBalanced('(', ')')
          if (isSym(peek(), '?')) next()
          if (isSym(peek(), '.')) next() else continue = false
        case _ =>
          continue = false
      }
    }
    last
  }

  private def readQualifiedName(): List[Token.Ident] = {
    val parts = List.newBuilder[Token.Ident]
    var continue = true
    while (continue) {
      peek() match {
        case id: Token.Ident =>
          next()
          parts += id
          peek() match {
            case Token.Sym('.', _) =>
              if (lexer.lookaheadIsIdentifierAfterDot()) next()
              else continue = false
            case _ => continue = false
          }
        case _ => continue = false
      }
    }
    parts.result()
  }

  // ---------------------------------------------------------------- skipping

  /** Skips `@Name`, `@file:Name`, `@Name(args)` and `@[A B]`. */
  private def skipAnnotation(): Unit = {
    peek() match {
      case Token.Sym('[', _) =>
        next()
        skipBalanced('[', ']')
      case _ =>
        peek() match {
          case id: Token.Ident if UseSiteTargets.contains(id.name) =>
            next()
            if (isSym(peek(), ':')) next()
          case _ =>
        }
        if (isSym(peek(), '[')) { next(); skipBalanced('[', ']') }
        else {
          readQualifiedName()
          if (isSym(peek(), '<')) { next(); skipBalanced('<', '>') }
          if (isSym(peek(), '(') && !peek().newlineBefore) {
            next()
            skipBalanced('(', ')')
          }
        }
    }
  }

  private def skipBlock(): Unit = skipBalanced('{', '}')

  /** Skips to the matching closing bracket, after the opening one was read. */
  private def skipBalanced(open: Char, close: Char): Unit = {
    var depth = 1
    while (depth > 0) {
      next() match {
        case Token.EOF => depth = 0
        case Token.Sym(c, _) if c == close => depth -= 1
        case Token.Sym(c, _) if c == open => depth += 1
        case Token.Sym('-', _) if open == '<' && isSym(peek(), '>') =>
          next() // `->` inside a function type
        // `(...)` inside `<...>`, such as `@Ann(1 < 2)`, has its own `<`.
        case Token.Sym('(', _) if open == '<' => skipBalanced('(', ')')
        case _ =>
      }
    }
  }

  /** Skips a type such as `Map<K, V>?` or `(A) -> B`. */
  private def skipType(): Unit = {
    var continue = true
    while (continue) {
      peek() match {
        case Token.Sym('<', _) => next(); skipBalanced('<', '>')
        case Token.Sym('(', _) => next(); skipBalanced('(', ')')
        case Token.Sym('.', _) | Token.Sym('?', _) | Token.Sym('@', _) |
            Token.Sym('*', _) =>
          next()
        case Token.Sym('-', _) => next() // `->`
        case Token.Sym('>', _) if isSym(previous, '-') => next()
        case _: Token.Ident if !peek().newlineBefore || isSym(previous, '.') =>
          next()
        case Token.Keyword("in", _) => next()
        case _ => continue = false
      }
    }
  }

  /**
   * Skips the rest of a declaration header (return type, `where` clause) and
   * stops before `{`, `=`, `by`, the start of the next declaration, or `}`.
   */
  private def skipHeader(): Unit = {
    var continue = true
    // After `where` or `,` the next constraint may start on a new line.
    var expectConstraint = false
    while (continue) {
      val t = peek()
      t match {
        case Token.Sym(':', _) =>
          next()
          skipType()
        case id: Token.Ident if id.name == "where" =>
          next()
          expectConstraint = true
        case Token.Sym(',', _) =>
          next()
          expectConstraint = true
        case id: Token.Ident
            if (!t.newlineBefore || expectConstraint) && id.name != "by" =>
          next()
          skipType()
          expectConstraint = false
        case _ => continue = false
      }
    }
  }

  /**
   * Skips an expression after `=` or `by`. It ends at the start of the next
   * declaration on a new line, at `;`, or at the `}` that closes the body.
   */
  private def skipExpression(): Unit = {
    var continue = true
    while (continue) {
      val t = peek()
      t match {
        case Token.EOF => continue = false
        case Token.Sym('}', _) => continue = false
        case Token.Sym(';', _) =>
          next()
          continue = false
        case Token.Sym('(', _) => next(); skipBalanced('(', ')')
        case Token.Sym('[', _) => next(); skipBalanced('[', ']')
        case Token.Sym('{', _) => next(); skipBlock()
        case _
            if t.newlineBefore && startsDeclaration(t) &&
              !continuesExpression(previous) =>
          continue = false
        case _ => next()
      }
    }
  }

  /**
   * Skips a parameter type and default value up to `,` or `)`, or a
   * delegation expression (`: A by b`) up to `,`, the class body or the next
   * declaration. A consumed `,` or `)` is returned; anything else stays.
   */
  private def skipUntil(stopAtClosingParen: Boolean): Token = {
    var afterEquals = false
    var result: Token = null
    while (result == null) {
      val t = peek()
      t match {
        case Token.EOF => result = t
        case Token.Sym(',', _) =>
          next()
          result = t
        case Token.Sym(')', _) if stopAtClosingParen =>
          next()
          result = t
        // A class body starts, or the enclosing body ends.
        case Token.Sym('{', _) | Token.Sym('}', _) if !stopAtClosingParen =>
          result = t
        case _
            if !stopAtClosingParen && t.newlineBefore &&
              startsDeclaration(t) && !continuesExpression(previous) =>
          result = t
        case Token.Sym('(', _) => next(); skipBalanced('(', ')')
        case Token.Sym('[', _) => next(); skipBalanced('[', ']')
        case Token.Sym('{', _) => next(); skipBlock()
        case lt @ Token.Sym('<', _)
            if !afterEquals || (previous.isInstanceOf[Token.Ident] &&
              lexer.looksLikeTypeArguments(lt.start)) =>
          next()
          skipBalanced('<', '>')
        case Token.Sym('=', _) =>
          next()
          afterEquals = true
        case _ => next()
      }
    }
    result
  }

  private def startsDeclaration(t: Token): Boolean = t match {
    case Token.Keyword(k, _) => DeclarationKeywords.contains(k)
    case id: Token.Ident => DeclarationModifiers.contains(id.name)
    case Token.Sym('@', _) => true
    case _ => false
  }

  /** Tokens after which a line break does not end an expression. */
  private def continuesExpression(t: Token): Boolean = t match {
    case Token.Sym(c, _) => ContinuationSymbols.contains(c)
    case Token.Keyword(k, _) =>
      k == "else" || k == "in" || k == "is" || k == "as"
    case _ => false
  }
}

object KotlinMtags {

  private val Constructor = "<init>"

  // An identifier can be a term or a type, so it references all three forms.
  private val ReferenceSuffixes = Array("().", ".", "#")

  private val UseSiteTargets = Set(
    "file", "property", "field", "get", "set", "receiver", "param", "setparam",
    "delegate"
  )

  /** Modifiers and soft keywords that can start a member declaration. */
  val Modifiers: Set[String] = Set(
    "abstract", "actual", "annotation", "companion", "const", "crossinline",
    "data", "enum", "expect", "external", "final", "infix", "inline", "inner",
    "internal", "lateinit", "noinline", "open", "operator", "override",
    "private", "protected", "public", "reified", "sealed", "suspend", "tailrec",
    "vararg", "value"
  )

  private val DeclarationModifiers: Set[String] = Modifiers ++ Set(
    "init",
    "constructor",
    "get",
    "set"
  )

  private val DeclarationKeywords = Set(
    "class", "interface", "object", "fun", "val", "var", "typealias"
  )

  // `<`, `>`, `?` and `!` are left out: lines often end with type arguments,
  // nullable types (`String?`) or `!!`.
  private val ContinuationSymbols =
    Set('=', '.', ',', '(', '[', ':', '+', '-', '*', '/', '%', '&', '|')

  /** Hard keywords: never identifiers, so never references. */
  val HardKeywords: Set[String] = Set(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun",
    "if", "in", "interface", "is", "null", "object", "package", "return",
    "super", "this", "throw", "true", "try", "typealias", "typeof", "val",
    "var", "when", "while"
  )

  def getterName(name: String): String =
    if (isBooleanPrefix(name)) name else "get" + name.capitalize

  def setterName(name: String): String =
    if (isBooleanPrefix(name)) "set" + name.substring(2)
    else "set" + name.capitalize

  // Kotlin keeps `isOn` as the getter of a property named `isOn`.
  private def isBooleanPrefix(name: String): Boolean =
    name.length > 2 && name.startsWith("is") && !name.charAt(2).isLower

  sealed abstract class Token {
    def newlineBefore: Boolean
  }
  object Token {
    case object BOF extends Token { def newlineBefore = true }
    case object EOF extends Token { def newlineBefore = true }
    final case class Ident(name: String, start: Int, end: Int)(
        val newlineBefore: Boolean
    ) extends Token
    final case class Keyword(name: String, newlineBefore: Boolean)(
        val start: Int,
        val end: Int
    ) extends Token
    final case class Sym(char: Char, newlineBefore: Boolean)(val start: Int)
        extends Token
  }

  /**
   * Splits Kotlin source text into identifiers, keywords and symbols. String
   * and character literals and comments are skipped, but the code inside a
   * string template (`$name`, `${expr}`) is tokenized.
   */
  final class Lexer(text: String, startOffset: Int = 0) {
    private var offset = startOffset
    private var newline = true
    // One entry per open string; a code entry counts braces inside `${ }`.
    private val modes = new java.util.ArrayDeque[Mode]()

    private sealed abstract class Mode
    private final class InString(val raw: Boolean) extends Mode
    private final class InTemplate(var depth: Int) extends Mode

    // True when the last token came from a string template.
    private var fromTemplate = false
    def lastFromTemplate: Boolean = fromTemplate

    /**
     * Returns the next token. Inside `${ }` only identifiers are returned, so
     * that template code cannot change the parser state around the string.
     */
    def next(): Token = {
      var t = scanOne()
      while (fromTemplate && t != Token.EOF && !t.isInstanceOf[Token.Ident])
        t = scanOne()
      t
    }

    private def scanOne(): Token =
      if (!modes.isEmpty && modes.peek().isInstanceOf[InString])
        scanString(modes.peek().asInstanceOf[InString])
      else scanCode()

    /**
     * True when `<` at `start` opens type arguments such as
     * `emptyMap<String, Int>()`: the text up to the matching `>` has only
     * names and type punctuation.
     */
    def looksLikeTypeArguments(start: Int): Boolean = {
      var i = start
      var depth = 0
      var result: Option[Boolean] = None
      while (result.isEmpty && i < text.length) {
        val c = text.charAt(i)
        if (c == '<') depth += 1
        else if (c == '>' && text.charAt(i - 1) != '-') {
          depth -= 1
          if (depth == 0) result = Some(true)
        } else if (
          !Character.isJavaIdentifierPart(c) && TypeChars.indexOf(c) < 0
        )
          result = Some(false)
        i += 1
      }
      result.getOrElse(false)
    }

    /** True when `.` at the current position is followed by an identifier. */
    def lookaheadIsIdentifierAfterDot(): Boolean =
      // `offset` is after the peeked `.`
      new Lexer(text, offset).next().isInstanceOf[Token.Ident]

    private def ch(i: Int): Char =
      if (i < text.length) text.charAt(i) else EOFChar

    private def scanCode(): Token = {
      skipWhitespaceAndComments()
      fromTemplate = !modes.isEmpty
      if (offset >= text.length) return Token.EOF
      val nl = newline
      newline = false
      val c = text.charAt(offset)
      if (c == '"') {
        if (ch(offset + 1) == '"' && ch(offset + 2) == '"') {
          offset += 3
          modes.push(new InString(raw = true))
        } else {
          offset += 1
          modes.push(new InString(raw = false))
        }
        next()
      } else if (c == '\'') {
        val start = offset
        skipCharLiteral()
        Token.Sym('\'', nl)(start)
      } else if (c == '`') {
        val start = offset + 1
        var i = start
        while (
          i < text.length && text.charAt(i) != '`' && text.charAt(i) != '\n'
        )
          i += 1
        offset = math.min(i + 1, text.length)
        Token.Ident(text.substring(start, i), start, i)(nl)
      } else if (Character.isJavaIdentifierStart(c)) {
        val start = offset
        var i = offset + 1
        while (
          i < text.length && Character.isJavaIdentifierPart(text.charAt(i))
        )
          i += 1
        offset = i
        val name = text.substring(start, i)
        if (HardKeywords.contains(name)) Token.Keyword(name, nl)(start, i)
        else Token.Ident(name, start, i)(nl)
      } else if (Character.isDigit(c)) {
        val start = offset
        skipNumber()
        Token.Sym('0', nl)(start)
      } else {
        offset += 1
        if (!modes.isEmpty) {
          val template = modes.peek().asInstanceOf[InTemplate]
          if (c == '{') template.depth += 1
          else if (c == '}') {
            if (template.depth == 0) {
              modes.pop() // back inside the string
              return next()
            }
            template.depth -= 1
          }
        }
        Token.Sym(c, nl)(offset - 1)
      }
    }

    private def scanString(mode: InString): Token = {
      var result: Token = null
      while (result == null) {
        if (offset >= text.length) {
          modes.clear()
          result = Token.EOF
        } else {
          val c = text.charAt(offset)
          if (!mode.raw && c == '\\') offset += 2
          else if (!mode.raw && (c == '"' || c == '\n')) {
            offset += 1
            modes.pop()
            fromTemplate = !modes.isEmpty
            result = Token.Sym('"', false)(offset - 1)
          } else if (
            mode.raw && c == '"' && ch(offset + 1) == '"' && ch(
              offset + 2
            ) == '"'
          ) {
            offset += 3
            while (ch(offset) == '"') offset += 1 // `""""` ends with a quote
            modes.pop()
            fromTemplate = !modes.isEmpty
            result = Token.Sym('"', false)(offset - 1)
          } else if (c == '$' && ch(offset + 1) == '{') {
            offset += 2
            modes.push(new InTemplate(0))
            result = scanCode()
          } else if (
            c == '$' && Character.isJavaIdentifierStart(ch(offset + 1))
          ) {
            val start = offset + 1
            var i = start + 1
            while (
              i < text.length && Character.isJavaIdentifierPart(text.charAt(i))
            )
              i += 1
            offset = i
            val name = text.substring(start, i)
            fromTemplate = true
            result =
              if (HardKeywords.contains(name))
                Token.Keyword(name, false)(start, i)
              else Token.Ident(name, start, i)(false)
          } else offset += 1
        }
      }
      result
    }

    private def skipCharLiteral(): Unit = {
      offset += 1
      if (ch(offset) == '\\') offset += 2 else offset += 1
      while (
        offset < text.length && text.charAt(offset) != '\'' &&
        text.charAt(offset) != '\n'
      ) offset += 1
      if (ch(offset) == '\'') offset += 1
    }

    private def skipNumber(): Unit = {
      while (
        offset < text.length && {
          val c = text.charAt(offset)
          Character.isLetterOrDigit(c) || c == '_' ||
          (c == '.' && Character.isDigit(ch(offset + 1)))
        }
      ) offset += 1
    }

    private def skipWhitespaceAndComments(): Unit = {
      var continue = true
      while (continue && offset < text.length) {
        val c = text.charAt(offset)
        if (c == '\n') { newline = true; offset += 1 }
        else if (Character.isWhitespace(c)) offset += 1
        else if (c == '/' && ch(offset + 1) == '/') {
          while (offset < text.length && text.charAt(offset) != '\n')
            offset += 1
        } else if (c == '/' && ch(offset + 1) == '*') {
          // Kotlin block comments nest.
          var depth = 1
          offset += 2
          while (depth > 0 && offset < text.length) {
            if (text.charAt(offset) == '/' && ch(offset + 1) == '*') {
              depth += 1; offset += 2
            } else if (text.charAt(offset) == '*' && ch(offset + 1) == '/') {
              depth -= 1; offset += 2
            } else {
              if (text.charAt(offset) == '\n') newline = true
              offset += 1
            }
          }
        } else if (c == '#' && offset == 0 && ch(1) == '!') {
          while (offset < text.length && text.charAt(offset) != '\n')
            offset += 1
        } else continue = false
      }
    }
  }

  private final val EOFChar = '\u001a'
  // Characters that can appear in type arguments besides names.
  private val TypeChars = " \t\r\n,.?*()-@`<>"
}
