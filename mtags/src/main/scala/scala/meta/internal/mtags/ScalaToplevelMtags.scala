package scala.meta.internal.mtags

import scala.meta.dialects
import scala.meta.inputs.Input
import scala.meta.inputs.Position
import scala.meta.internal.semanticdb.Language
import scala.meta.internal.tokenizers.LegacyScanner
import scala.meta.internal.semanticdb.SymbolInformation.Kind
import scala.meta.internal.tokenizers.LegacyToken._
import scala.meta.internal.inputs._
import scala.meta.internal.semanticdb.Scala._
import scala.meta.parsers.ParseException

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
 */
class ScalaToplevelMtags(input: Input.VirtualFile) extends MtagsIndexer {
  private val scanner = new LegacyScanner(input, dialects.Scala212)
  scanner.reader.nextChar()
  def isDone: Boolean = scanner.curr.token == EOF
  def isNewline: Boolean =
    scanner.curr.token == WHITESPACE &&
      (scanner.curr.strVal match {
        case "\n" | "\r" => true
        case _ => false
      })
  override def language: Language = Language.SCALA
  override def indexRoot(): Unit = {
    parseStats()
  }

  def parseStats(): Unit = {
    while (!isDone) {
      parseStat()
    }
  }

  def parseStat(): Unit = {
    scanner.curr.token match {
      case PACKAGE =>
        emitPackage()
      case CLASS | TRAIT | OBJECT =>
        emitMember(isPackageObject = false)

      // Ignore everything enclosed within parentheses, braces and brackets.
      case LPAREN =>
        acceptBalancedDelimeters(LPAREN, RPAREN)
      case LBRACE =>
        acceptBalancedDelimeters(LBRACE, RBRACE)
      case LBRACKET =>
        acceptBalancedDelimeters(LBRACKET, RBRACKET)

      // Ignore other tokens
      case _ =>
        scanner.nextToken()
    }
  }

  def emitPackage(): Unit = {
    require(scanner.curr.token == PACKAGE, failMessage("package"))
    val old = currentOwner
    acceptTrivia()
    scanner.curr.token match {
      case IDENTIFIER =>
        val paths = parsePath()
        paths.foreach { path =>
          pkg(path.name, path.pos)
        }
      case OBJECT =>
        emitMember(isPackageObject = true)
      case _ =>
        sys.error(failMessage("package name or package object"))
    }
    if (scanner.curr.token == LBRACE) {
      // Handle sibling packages in the same file
      // package foo1 { ... }
      // package foo2 { ... }
      var count = 1
      scanner.nextToken()
      while (!isDone && count > 0) {
        parseStat()
        scanner.curr.token match {
          case RBRACE => count -= 1
          case LBRACE => count += 1
          case _ =>
        }
      }
      currentOwner = old
    }
  }

  /** Enters a toplevel symbol such as class, trait or object */
  def emitMember(isPackageObject: Boolean): Unit = {
    val kind = scanner.curr.token
    acceptTrivia()
    val name = newIdentifier
    val old = currentOwner
    kind match {
      case CLASS =>
        tpe(name.name, name.pos, Kind.CLASS, 0)
      case TRAIT =>
        tpe(name.name, name.pos, Kind.TRAIT, 0)
      case OBJECT =>
        if (isPackageObject) {
          withOwner(symbol(Descriptor.Package(name.name))) {
            term("package", name.pos, Kind.OBJECT, 0)
          }
        } else {
          term(name.name, name.pos, Kind.OBJECT, 0)
        }
    }
    currentOwner = old
  }

  /** Returns position of the current token */
  def newPosition: Position = {
    val start = scanner.curr.offset
    val end = scanner.curr.endOffset + 1
    Position.Range(input, start, end)
  }

  /** Returns a name and position for the current identifier token */
  def newIdentifier: Identifier = {
    scanner.curr.token match {
      case IDENTIFIER | BACKQUOTED_IDENT => // OK
      case _ => fail("identifier")
    }
    val pos = newPosition
    val name = scanner.curr.name
    new Identifier(name, pos)
  }

  /** Consume token stream like "a.b.c" and return List(a, b, c) */
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

  /** Consumes the token stream until the matching closing delimiter */
  def acceptBalancedDelimeters(Open: Int, Close: Int): Unit = {
    require(scanner.curr.token == Open, failMessage("open delimeter { or ("))
    var count = 1
    while (!isDone && count > 0) {
      scanner.nextToken()
      scanner.curr.token match {
        case Open => count += 1
        case Close => count -= 1
        case _ =>
      }
    }
  }

  /** Consumes the token stream until the next non-trivia token */
  def acceptTrivia(): Unit = {
    scanner.nextToken()
    while (!isDone &&
      (scanner.curr.token match {
        case WHITESPACE | COMMENT => true
        case _ => false
      })) {
      scanner.nextToken()
    }
  }

  // =======
  // Utility
  // =======

  def debug(message: String = ""): Unit = {
    val pos = newPosition
    pprint.log(pos.formatMessage("info", message))
  }
  def fail(expected: String): Nothing = {
    throw new ParseException(newPosition, failMessage(expected))
  }
  def failMessage(expected: String): String = {
    val obtained = InverseLegacyToken.category(scanner.curr.token).toLowerCase()
    newPosition.formatMessage(
      "error",
      s"expected $expected; obtained $obtained"
    )
  }
}
