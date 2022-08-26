package scala.meta.internal.mtags

import scala.annotation.tailrec

import scala.meta.Dialect
import scala.meta.inputs.Input
import scala.meta.inputs.Position
import scala.meta.internal.inputs._
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.semanticdb.Language
import scala.meta.internal.semanticdb.Scala
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.SymbolInformation.Kind
import scala.meta.internal.tokenizers.LegacyScanner
import scala.meta.internal.tokenizers.LegacyToken._
import scala.meta.tokenizers.TokenizeException

final class Identifier(val name: String, val pos: Position) {
  override def toString: String = pos.formatMessage("info", name)
}

/**
 * Custom parser that extracts toplevel members from a Scala source file.
 *
 * Example input: {{{
 *   package com.zoo
 *   class Animal { class Dog }
 *   object Park { trait Bench }
 * }}}
 * emits the following symbols: com/zoo/Animal# and com/zoo/Park.
 * The inner classes Animal.Dog and Park.Bench are ignored.
 *
 * This class exists for performance reasons. The ScalaMtags indexer provides
 * the same functionality but it is much slower. Performance is important
 * because toplevel symbol indexing is on a critical path when users import
 * a new project.
 *
 * @param includeInnerClasses if true, emits occurrences for inner class/object/trait.
 */
class ScalaToplevelMtags(
    val input: Input.VirtualFile,
    includeInnerClasses: Boolean,
    dialect: Dialect
) extends MtagsIndexer {

  import ScalaToplevelMtags._

  override def language: Language = Language.SCALA

  override def indexRoot(): Unit = loop(0, true, Region.RootRegion, None)

  private val scanner = new LegacyScanner(input, dialect)
  scanner.reader.nextChar()
  def isDone: Boolean = scanner.curr.token == EOF

  private var sourceTopLevelAdded = false

  private def resetRegion(region: Region): Region = {
    currentOwner = region.owner
    region
  }

  private def exitIndented(region: Region, indent: Int): Region =
    region match {
      case indented: Region.Indented if indent <= indented.exitIndent =>
        exitIndented(indented.prev, indent)
      case _ => region
    }

  @tailrec
  private def loop(
      indent: Int,
      isAfterNewline: Boolean,
      region: Region,
      expectTemplate: Option[ExpectTemplate]
  ): Unit = {
    def newExpectTemplate: Some[ExpectTemplate] =
      Some(ExpectTemplate(indent, currentOwner, false, false))
    def newExpectPkgTemplate: Some[ExpectTemplate] =
      Some(ExpectTemplate(indent, currentOwner, true, false))
    def newExpectExtensionTemplate(owner: String): Some[ExpectTemplate] =
      Some(ExpectTemplate(indent, owner, false, true))
    def newExpectIgnoreBody: Some[ExpectTemplate] =
      Some(
        ExpectTemplate(
          indent = indent,
          owner = currentOwner,
          isPackageBody = false,
          isExtension = false,
          ignoreBody = true
        )
      )

    def needEmitFileOwner(region: Region): Boolean =
      !sourceTopLevelAdded && region.produceSourceToplevel
    def needToParseBody(expect: ExpectTemplate): Boolean =
      (includeInnerClasses || expect.isPackageBody) && !expect.ignoreBody
    def needToParseExtension(expect: ExpectTemplate): Boolean =
      includeInnerClasses && expect.isExtension && !expect.ignoreBody
    def nextIsNL: Boolean = {
      scanner.nextToken()
      isNewline
    }

    def needEmitMember(region: Region): Boolean =
      includeInnerClasses || region.acceptMembers

    if (!isDone) {
      val data = scanner.curr

      val currRegion =
        if (dialect.allowSignificantIndentation) {
          data.token match {
            case WHITESPACE | COMMENT => region
            case _ => exitIndented(region, indent)
          }
        } else region

      data.token match {
        case PACKAGE =>
          val isNotPackageObject = emitPackage(currRegion.owner)
          if (isNotPackageObject) {
            val nextRegion = Region.Package(currentOwner, currRegion)
            loop(
              indent,
              false,
              nextRegion,
              newExpectPkgTemplate
            )
          } else
            loop(indent, false, currRegion, newExpectTemplate)
        case IDENTIFIER
            if dialect.allowExtensionMethods && data.name == "extension" =>
          val nextOwner =
            if (
              dialect.allowToplevelStatements &&
              currRegion.produceSourceToplevel
            ) {
              val srcName = input.filename.stripSuffix(".scala")
              val name = s"$srcName$$package"
              val owner = withOwner(currRegion.owner) {
                symbol(Descriptor.Term(name))
              }

              if (needEmitFileOwner(currRegion)) {
                sourceTopLevelAdded = true
                val pos = newPosition
                withOwner(currRegion.owner) {
                  term(name, pos, Kind.OBJECT, 0)
                }
              }
              owner
            } else currentOwner
          scanner.nextToken()
          loop(
            indent,
            isAfterNewline = false,
            currRegion,
            newExpectExtensionTemplate(nextOwner)
          )
        case CLASS | TRAIT | OBJECT | ENUM if needEmitMember(currRegion) =>
          emitMember(false, currRegion.owner)
          loop(indent, isAfterNewline = false, currRegion, newExpectTemplate)
        // also covers extension methods because of `def` inside
        case DEF
            // extension group
            if (dialect.allowExtensionMethods && currRegion.isExtension) =>
          acceptTrivia()
          val name = newIdentifier
          withOwner(currRegion.owner) {
            term(
              name.name,
              name.pos,
              // hack: (exclusively) making symbol kind of extension methods to Kind.Method
              // so that we can tell it's an extension method in Indexer.scala
              // TODO: add properties that represents "extension" method to semanticdb schema
              // see: https://github.com/scalameta/scalameta/issues/2799
              Kind.METHOD,
              0
            )
          }
          loop(indent, isAfterNewline = false, region, newExpectIgnoreBody)
        // inline extension method `extension (...) def foo = ...`
        case DEF if expectTemplate.map(needToParseExtension).getOrElse(false) =>
          expectTemplate match {
            case None =>
              fail(
                "failed while reading 'def' in 'extension (...) def ...', expectTemplate should be set by reading 'extension'."
              )
            case Some(expect) =>
              acceptTrivia()
              val name = newIdentifier
              withOwner(expect.owner) {
                term(name.name, name.pos, Kind.METHOD, 0)
              }
              loop(indent, isAfterNewline = false, region, None)
          }
        case DEF | VAL | VAR | GIVEN | TYPE
            if dialect.allowToplevelStatements &&
              needEmitFileOwner(currRegion) =>
          sourceTopLevelAdded = true
          val pos = newPosition
          val srcName = input.filename.stripSuffix(".scala")
          val name = s"$srcName$$package"
          withOwner(currRegion.owner) {
            term(name, pos, Kind.OBJECT, 0)
          }
          loop(indent, isAfterNewline = false, region, expectTemplate)
        case IMPORT =>
          // skip imports becase they might have `given` kw
          acceptToStatSep()
          loop(indent, isAfterNewline = false, region, expectTemplate)
        case COMMENT =>
          // skip comment becase they might break indentation
          scanner.nextToken()
          loop(indent, isAfterNewline = false, region, expectTemplate)
        case WHITESPACE if dialect.allowSignificantIndentation =>
          if (isNewline) {
            expectTemplate match {
              // extension (x: Int)|
              //   def foo() = ...
              case Some(expect) if needToParseExtension(expect) =>
                val next =
                  expect.startIndentedRegion(currRegion, expect.isExtension)
                resetRegion(next)
                scanner.nextToken()
                loop(0, isAfterNewline = true, next, None)
              // basically for braceless def
              case Some(expect) if expect.ignoreBody =>
                val nextIndent = acceptWhileIndented(expect.indent)
                loop(nextIndent, isAfterNewline = false, currRegion, None)
              case _ =>
                scanner.nextToken()
                loop(0, isAfterNewline = true, region, expectTemplate)
            }
          } else {
            val nextIndentLevel =
              if (isAfterNewline) indent + 1 else indent
            scanner.nextToken()
            loop(nextIndentLevel, isAfterNewline, region, expectTemplate)
          }
        case COLON if dialect.allowSignificantIndentation =>
          (expectTemplate, nextIsNL) match {
            case (Some(expect), true) if needToParseBody(expect) =>
              val next = expect.startIndentedRegion(currRegion)
              resetRegion(next)
              scanner.nextToken()
              loop(0, isAfterNewline = true, next, None)
            case (Some(expect), true) =>
              val nextIndent = acceptWhileIndented(expect.indent)
              loop(nextIndent, isAfterNewline = false, currRegion, None)
            case _ =>
              scanner.nextToken()
              loop(indent, isAfterNewline = false, currRegion, expectTemplate)
          }
        case LBRACE =>
          expectTemplate match {
            case Some(expect)
                if needToParseBody(expect) || needToParseExtension(expect) =>
              val next =
                expect.startInBraceRegion(currRegion, expect.isExtension)
              resetRegion(next)
              scanner.nextToken()
              loop(indent, isAfterNewline = false, next, None)
            case _ =>
              acceptBalancedDelimeters(LBRACE, RBRACE)
              scanner.nextToken()
              loop(indent, isAfterNewline = false, currRegion, expectTemplate)
          }
        case RBRACE =>
          val nextRegion = currRegion match {
            case Region.InBrace(_, prev, _) => resetRegion(prev)
            case r => r
          }
          scanner.nextToken()
          loop(indent, isAfterNewline, nextRegion, None)
        case LBRACKET =>
          acceptBalancedDelimeters(LBRACKET, RBRACKET)
          scanner.nextToken()
          loop(indent, isAfterNewline = false, currRegion, expectTemplate)
        case LPAREN =>
          acceptBalancedDelimeters(LPAREN, RPAREN)
          scanner.nextToken()
          loop(indent, isAfterNewline = false, currRegion, expectTemplate)
        case _ =>
          val nextExpectTemplate = expectTemplate.filter(!_.isPackageBody)
          scanner.nextToken()
          loop(indent, isAfterNewline = false, currRegion, nextExpectTemplate)
      }
    } else ()
  }

  def emitPackage(owner: String): Boolean = {
    require(scanner.curr.token == PACKAGE, "package")
    if (currentOwner eq Symbols.EmptyPackage) {
      currentOwner = Symbols.RootPackage
    }
    currentOwner = owner
    acceptTrivia()
    scanner.curr.token match {
      case IDENTIFIER =>
        val paths = parsePath()
        paths.foreach { path => pkg(path.name, path.pos) }
        true
      case OBJECT =>
        emitMember(isPackageObject = true, owner)
        false
      case _ =>
        require(isOk = false, "package name or package object")
        false
    }
  }

  /**
   * Consume token stream like "a.b.c" and return List(a, b, c)
   */
  def parsePath(): List[Identifier] = {
    val buf = List.newBuilder[Identifier]
    def loop(): Unit = {
      buf += newIdentifier
      acceptTrivia()
      scanner.curr.token match {
        case DOT =>
          acceptTrivia()
          loop()
        case _ =>
      }
    }
    loop()
    buf.result()
  }

  /**
   * Enters a toplevel symbol such as class, trait or object
   */
  def emitMember(isPackageObject: Boolean, owner: String): Unit = {
    val kind = scanner.curr.token
    acceptTrivia()
    val name = newIdentifier
    currentOwner = owner
    kind match {
      case CLASS =>
        tpe(name.name, name.pos, Kind.CLASS, 0)
      case TRAIT =>
        tpe(name.name, name.pos, Kind.TRAIT, 0)
      case ENUM =>
        withOwner(owner) {
          tpe(name.name, name.pos, Kind.CLASS, 0)
        }
      case OBJECT =>
        if (isPackageObject) {
          currentOwner = symbol(Scala.Descriptor.Package(name.name))
          term("package", name.pos, Kind.OBJECT, 0)
        } else {
          term(name.name, name.pos, Kind.OBJECT, 0)
        }
    }
    scanner.nextToken()
  }

  /**
   * Consumes the token stream until the matching closing delimiter
   */
  def acceptBalancedDelimeters(Open: Int, Close: Int): Unit = {
    require(scanner.curr.token == Open, "open delimeter { or (")
    var count = 1
    while (!isDone && count > 0) {
      scanner.nextToken()
      scanner.curr.token match {
        case Open =>
          count += 1
        case Close =>
          count -= 1
        case _ =>
      }
    }
  }

  /**
   * Consumes the token stream until outdent to the same indentation level
   */
  def acceptWhileIndented(exitIndent: Int): Int = {
    @tailrec
    def loop(indent: Int, isAfterNL: Boolean): Int = {
      if (!isDone) {
        scanner.curr.token match {
          case WHITESPACE =>
            if (isNewline) { scanner.nextToken; loop(0, true) }
            else if (isAfterNL) { scanner.nextToken; loop(indent + 1, true) }
            else { scanner.nextToken(); loop(indent, false) }
          case COMMENT =>
            scanner.nextToken()
            loop(indent, false)
          case _ if indent <= exitIndent => indent
          case _ =>
            scanner.nextToken()
            loop(indent, false)
        }
      } else indent
    }
    loop(0, true)
  }

  def acceptToStatSep(): Unit = {
    scanner.nextToken()
    while (
      !isDone &&
      (scanner.curr.token match {
        case WHITESPACE if isNewline => false
        case SEMI => false
        case _ => true
      })
    ) {
      scanner.nextToken()
    }
  }

  private def acceptTrivia(): Unit = {
    scanner.nextToken()
    while (
      !isDone &&
      (scanner.curr.token match {
        case WHITESPACE | COMMENT => true
        case _ => false
      })
    ) {
      scanner.nextToken()
    }
  }

  /**
   * Returns a name and position for the current identifier token
   */
  def newIdentifier: Identifier = {
    scanner.curr.token match {
      case IDENTIFIER => // OK
      case _ => fail("identifier")
    }
    val pos = newPosition
    val name = scanner.curr.name
    new Identifier(name, pos)
  }

  private def isNewline: Boolean =
    scanner.curr.token == WHITESPACE &&
      (scanner.curr.strVal match {
        case "\n" | "\r" => true
        case _ => false
      })

  def fail(expected: String): Nothing = {
    throw new TokenizeException(newPosition, failMessage(expected))
  }

  def failMessage(expected: String): String = {
    newPosition.formatMessage(
      "error",
      s"expected $expected; obtained $currentToken"
    )
  }

  /**
   * Returns position of the current token
   */
  def newPosition: Position = {
    val start = scanner.curr.offset
    val end = scanner.curr.endOffset + 1
    Position.Range(input, start, end)
  }

  def currentToken: String =
    InverseLegacyToken.category(scanner.curr.token).toLowerCase()

  def require(isOk: Boolean, expected: String): Unit = {
    if (!isOk) {
      throw new TokenizeException(newPosition, failMessage(expected))
    }
  }
}

object ScalaToplevelMtags {

  final case class ExpectTemplate(
      indent: Int,
      owner: String,
      isPackageBody: Boolean,
      isExtension: Boolean = false,
      ignoreBody: Boolean = false
  ) {

    /**
     * In order to have a correct owner chain
     * parser treats `package $ident` as a `Package` region.
     *
     * Then in case if package body was found it needs to replace this last `Package` region
     * with `InBrace(owner = $package-name)` or `Indented`.
     */
    private def adjustRegion(r: Region): Region =
      if (isPackageBody) r.prev else r

    def startInBraceRegion(prev: Region, extension: Boolean = false): Region =
      Region.InBrace(owner, adjustRegion(prev), extension)

    def startIndentedRegion(prev: Region, extension: Boolean = false): Region =
      Region.Indented(owner, indent, adjustRegion(prev), extension)

  }

  sealed trait Region {
    def prev: Region
    def owner: String
    def acceptMembers: Boolean
    def produceSourceToplevel: Boolean
    def isExtension: Boolean = false
  }

  object Region {

    case object RootRegion extends Region { self =>
      val owner: String = Symbols.EmptyPackage
      val prev: Region = self
      val acceptMembers: Boolean = true
      val produceSourceToplevel: Boolean = true
    }

    final case class Package(owner: String, prev: Region) extends Region {
      val acceptMembers: Boolean = true
      val produceSourceToplevel: Boolean = true
    }

    final case class InBrace(
        owner: String,
        prev: Region,
        extension: Boolean = false
    ) extends Region {
      def acceptMembers: Boolean =
        owner.endsWith("/")
      val produceSourceToplevel: Boolean = false
      override def isExtension = extension
    }
    final case class Indented(
        owner: String,
        exitIndent: Int,
        prev: Region,
        extension: Boolean = false
    ) extends Region {
      def acceptMembers: Boolean =
        owner.endsWith("/")
      val produceSourceToplevel: Boolean = false
      override def isExtension = extension
    }
  }
}
