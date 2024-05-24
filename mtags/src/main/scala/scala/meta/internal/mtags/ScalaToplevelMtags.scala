package scala.meta.internal.mtags

import scala.annotation.tailrec

import scala.meta.Dialect
import scala.meta.inputs.Input
import scala.meta.inputs.Position
import scala.meta.internal.inputs._
import scala.meta.internal.metals.Report
import scala.meta.internal.metals.ReportContext
import scala.meta.internal.mtags.ScalametaCommonEnrichments._
import scala.meta.internal.semanticdb.Language
import scala.meta.internal.semanticdb.Scala
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.SymbolInformation
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
    includeMembers: Boolean,
    dialect: Dialect,
    collectIdentifiers: Boolean = false
)(implicit rc: ReportContext)
    extends MtagsIndexer {

  override def overrides(): List[(String, List[OverriddenSymbol])] =
    overridden.result

  private val overridden = List.newBuilder[(String, List[OverriddenSymbol])]

  private def addOverridden(symbols: List[OverriddenSymbol]) =
    overridden += ((currentOwner, symbols))

  import ScalaToplevelMtags._

  private val identifiers = Set.newBuilder[String]
  override def allIdentifiers: Set[String] = identifiers.result()

  implicit class XtensionScanner(scanner: LegacyScanner) {
    def mtagsNextToken(): Unit = {
      scanner.nextToken()
      if (collectIdentifiers)
        scanner.curr.token match {
          case IDENTIFIER => identifiers += scanner.curr.name
          case _ =>
        }
    }
  }

  override def language: Language = Language.SCALA

  override def indexRoot(): Unit =
    loop(0, true, new Region.RootRegion, None)

  private val scanner = new LegacyScanner(input, dialect)
  scanner.reader.nextChar()
  def isDone: Boolean = scanner.curr.token == EOF

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

  private def isInParenthesis(region: Region): Boolean =
    region match {
      case (_: Region.InParenCaseClass) | (_: Region.InParenClass) =>
        true
      case _ => false
    }

  @tailrec
  private def loop(
      indent: Int,
      isAfterNewline: Boolean,
      region: Region,
      expectTemplate: Option[ExpectTemplate],
      prevWasDot: Boolean = false
  ): Unit = {
    def newExpectTemplate(isImplicit: Boolean = false): Some[ExpectTemplate] =
      Some(
        ExpectTemplate(
          indent,
          currentOwner,
          false,
          false,
          isImplicit = isImplicit
        )
      )
    def newExpectCaseClassTemplate(): Some[ExpectTemplate] =
      Some(
        ExpectTemplate(
          indent,
          currentOwner,
          false,
          false,
          isClassConstructor = true,
          isCaseClassConstructor = true
        )
      )
    def newExpectClassTemplate(
        isImplicit: Boolean = false
    ): Some[ExpectTemplate] =
      Some(
        ExpectTemplate(
          indent,
          currentOwner,
          false,
          false,
          isClassConstructor = true,
          isImplicit = isImplicit
        )
      )
    def newExpectPkgTemplate: Some[ExpectTemplate] =
      Some(ExpectTemplate(indent, currentOwner, true, false))
    def newExpectExtensionTemplate(owner: String): Some[ExpectTemplate] =
      Some(ExpectTemplate(indent, owner, false, true))
    def newExpectImplicitTemplate: Some[ExpectTemplate] =
      Some(
        ExpectTemplate(indent, currentOwner, false, false, isImplicit = true)
      )
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
      region.produceSourceToplevel
    def needToParseBody(expect: ExpectTemplate): Boolean =
      (includeInnerClasses || expect.isPackageBody) && !expect.ignoreBody
    def needToParseExtension(expect: ExpectTemplate): Boolean =
      includeInnerClasses && expect.isExtension && !expect.ignoreBody

    def needEmitMember(region: Region): Boolean =
      includeInnerClasses || region.acceptMembers

    def needEmitTermMember(): Boolean =
      includeMembers && !prevWasDot

    def srcName = input.filename.stripSuffix(".scala")

    if (!isDone) {
      val data = scanner.curr
      val currRegion =
        if (dialect.allowSignificantIndentation) {
          data.token match {
            case WHITESPACE | COMMENT => region
            case _ =>
              resetRegion(exitIndented(region, indent))
          }
        } else region
      data.token match {
        case PACKAGE =>
          val isNotPackageObject = emitPackage(currRegion.owner)
          if (isNotPackageObject) {
            val nextRegion = new Region.Package(currentOwner, currRegion)
            loop(indent, false, nextRegion, newExpectPkgTemplate)
          } else
            loop(indent, false, currRegion, newExpectTemplate())
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
                val pos = newPosition
                withOwner(currRegion.owner) {
                  term(name, pos, Kind.OBJECT, 0)
                }
              }
              owner
            } else currRegion.termOwner
          scanner.mtagsNextToken()
          loop(
            indent,
            isAfterNewline = false,
            currRegion.withTermOwner(nextOwner),
            newExpectExtensionTemplate(nextOwner)
          )
        case CLASS | TRAIT | OBJECT | ENUM if needEmitMember(currRegion) =>
          /* Scala 3 allows for toplevel implicit classes, but generates
           * artificial package object. Scala 2 doesn't allow for it.
           */
          val needsToGenerateFileClass =
            dialect.allowExtensionMethods && currRegion.produceSourceToplevel &&
              expectTemplate.exists(_.isImplicit)
          val owner = if (needsToGenerateFileClass) {
            val name = s"$srcName$$package"
            val pos = newPosition
            val owner = withOwner(currRegion.owner) {
              term(name, pos, Kind.OBJECT, 0)
            }
            owner
          } else if (expectTemplate.exists(_.isImplicit)) {
            currRegion.termOwner
          } else { currRegion.owner }
          emitMember(isPackageObject = false, owner)
          val template = expectTemplate match {
            case Some(expect) if expect.isCaseClassConstructor =>
              newExpectCaseClassTemplate()
            case Some(expect) =>
              newExpectClassTemplate(expect.isImplicit)
            case _ =>
              newExpectClassTemplate(isImplicit = false)
          }
          loop(
            indent,
            isAfterNewline = false,
            if (needsToGenerateFileClass) currRegion.withTermOwner(owner)
            else currRegion,
            template
          )
        // also covers extension methods because of `def` inside
        case DEF
            // extension group
            if (includeMembers && (dialect.allowExtensionMethods && currRegion.isExtension || currRegion.isImplicit)) =>
          acceptTrivia()
          newIdentifier.foreach { name =>
            withOwner(currRegion.owner) {
              method(
                name.name,
                region.overloads.disambiguator(name.name),
                name.pos,
                EXTENSION
              )
            }
          }
          loop(indent, isAfterNewline = false, currRegion, newExpectIgnoreBody)
        // inline extension method `extension (...) def foo = ...`
        case DEF
            if includeMembers && expectTemplate
              .map(needToParseExtension)
              .getOrElse(false) =>
          expectTemplate match {
            case None =>
              reportError(
                "failed while reading 'def' in 'extension (...) def ...', expectTemplate should be set by reading 'extension'."
              )
            case Some(expect) =>
              acceptTrivia()
              newIdentifier.foreach { name =>
                withOwner(expect.owner) {
                  method(
                    name.name,
                    region.overloads.disambiguator(name.name),
                    name.pos,
                    EXTENSION
                  )
                }
              }
              loop(indent, isAfterNewline = false, currRegion, None)
          }
        case DEF | VAL | VAR | GIVEN | TYPE
            if dialect.allowToplevelStatements &&
              needEmitFileOwner(currRegion) =>
          val pos = newPosition
          val name = s"$srcName$$package"
          val owner = withOwner(currRegion.owner) {
            term(name, pos, Kind.OBJECT, 0)
          }
          loop(
            indent,
            isAfterNewline = false,
            currRegion.withTermOwner(owner),
            expectTemplate
          )
        case DEF | VAL | VAR | GIVEN
            if expectTemplate.map(!_.isExtension).getOrElse(true) =>
          val isImplicit =
            if (isInParenthesis(region))
              expectTemplate.exists(
                _.isImplicit
              )
            else region.isImplicit
          if (needEmitTermMember()) {
            withOwner(currRegion.termOwner) {
              emitTerm(currRegion, isImplicit)
            }
          } else scanner.mtagsNextToken()
          loop(
            indent,
            isAfterNewline = false,
            currRegion,
            if (isInParenthesis(region)) expectTemplate else newExpectIgnoreBody
          )
        case TYPE if expectTemplate.map(!_.isExtension).getOrElse(true) =>
          if (needEmitMember(currRegion) && !prevWasDot) {
            withOwner(currRegion.termOwner) {
              emitType(needEmitTermMember())
            }
          } else scanner.mtagsNextToken()
          loop(indent, isAfterNewline = false, currRegion, newExpectIgnoreBody)
        case IMPORT | EXPORT =>
          // skip imports because they might have `given` kw
          acceptToStatSep()
          loop(indent, isAfterNewline = false, currRegion, expectTemplate)
        case COMMENT =>
          // skip comment because they might break indentation
          scanner.mtagsNextToken()
          loop(indent, isAfterNewline = false, currRegion, expectTemplate)
        case WHITESPACE if dialect.allowSignificantIndentation =>
          if (isNewline) {
            expectTemplate match {
              // extension (x: Int)|
              //   def foo() = ...
              case Some(expect) if needToParseExtension(expect) =>
                val next =
                  expect.startIndentedRegion(currRegion, expect.isExtension)
                resetRegion(next)
                scanner.mtagsNextToken()
                loop(0, isAfterNewline = true, next, None)
              // basically for braceless def
              case Some(expect) if expect.ignoreBody =>
                val nextIndent = acceptWhileIndented(expect.indent)
                loop(
                  nextIndent,
                  isAfterNewline = false,
                  currRegion,
                  None
                )
              case _ =>
                scanner.mtagsNextToken()
                loop(
                  0,
                  isAfterNewline = true,
                  currRegion,
                  expectTemplate
                )
            }
          } else {
            val nextIndentLevel =
              if (isAfterNewline) indent + 1 else indent
            scanner.mtagsNextToken()
            loop(
              nextIndentLevel,
              isAfterNewline,
              currRegion,
              expectTemplate
            )
          }
        case MATCH | THEN | ELSE | DO | WHILE | TRY | FINALLY | THROW | RETURN |
            YIELD | FOR if dialect.allowSignificantIndentation =>
          if (nextIsNL()) {
            val nextIndent = acceptWhileIndented(indent)
            loop(
              nextIndent,
              isAfterNewline = false,
              currRegion,
              None
            )
          } else {
            loop(
              indent,
              isAfterNewline = false,
              currRegion,
              expectTemplate
            )
          }
        case COLON if dialect.allowSignificantIndentation =>
          (expectTemplate, nextIsNL()) match {
            case (Some(expect), true) if needToParseBody(expect) =>
              val next = expect.startIndentedRegion(
                currRegion,
                isImplicitClass = expect.isImplicit
              )
              resetRegion(next)
              scanner.mtagsNextToken()
              loop(0, isAfterNewline = true, next, None)
            case (Some(expect), true) =>
              val nextIndent = acceptWhileIndented(expect.indent)
              loop(
                nextIndent,
                isAfterNewline = false,
                currRegion,
                None
              )
            case (_, true) =>
              val nextIndent = acceptWhileIndented(indent)
              loop(
                nextIndent,
                isAfterNewline = false,
                currRegion,
                expectTemplate
              )
            case _ =>
              scanner.mtagsNextToken()
              loop(
                indent,
                isAfterNewline = false,
                currRegion,
                expectTemplate
              )
          }
        case LBRACE =>
          expectTemplate match {
            case Some(expect)
                if needToParseBody(expect) || needToParseExtension(expect) =>
              if (isInParenthesis(region)) {
                // inside of a class constructor
                // e.g. class A(val foo: Foo { type T = Int })
                //                           ^
                acceptBalancedDelimeters(LBRACE, RBRACE)
                scanner.mtagsNextToken()
                loop(indent, isAfterNewline = false, currRegion, expectTemplate)
              } else {
                val next =
                  expect.startInBraceRegion(
                    currRegion,
                    expect.isExtension,
                    expect.isImplicit
                  )
                resetRegion(next)
                scanner.mtagsNextToken()
                loop(indent, isAfterNewline = false, next, None)
              }
            case _ =>
              acceptBalancedDelimeters(LBRACE, RBRACE)
              scanner.mtagsNextToken()
              loop(indent, isAfterNewline = false, currRegion, None)
          }
        case RBRACE =>
          val nextRegion = currRegion match {
            case Region.InBrace(_, prev, _, _, _) => resetRegion(prev)
            case r => r
          }
          scanner.mtagsNextToken()
          loop(indent, isAfterNewline, nextRegion, None)
        case LBRACKET =>
          acceptBalancedDelimeters(LBRACKET, RBRACKET)
          scanner.mtagsNextToken()
          loop(indent, isAfterNewline = false, currRegion, expectTemplate)
        case LPAREN =>
          expectTemplate match {
            case Some(expect)
                if expect.isClassConstructor && includeInnerClasses => {
              scanner.mtagsNextToken()
              loop(
                indent,
                isAfterNewline = false,
                expect.startInParenRegion(
                  currRegion,
                  expect.isCaseClassConstructor
                ),
                expectTemplate
              )
            }
            case _ => {
              acceptBalancedDelimeters(LPAREN, RPAREN)
              scanner.mtagsNextToken()
              loop(indent, isAfterNewline = false, currRegion, expectTemplate)
            }
          }
        case RPAREN if (currRegion match {
              case _: Region.InParenCaseClass => true
              case _: Region.InParenClass => true
              case _ => false
            }) =>
          scanner.mtagsNextToken()
          loop(
            indent,
            isAfterNewline = false,
            currRegion.prev,
            newExpectTemplate(
              // we still need the information if the current template is implicit
              expectTemplate.exists(_.isImplicit)
            )
          )
        case COMMA =>
          val nextExpectTemplate = expectTemplate.filter(!_.isPackageBody)
          scanner.mtagsNextToken()
          loop(
            indent,
            isAfterNewline = false,
            currRegion.changeCaseClassState(true),
            nextExpectTemplate
          )
        case EXTENDS =>
          val (overridden, maybeNewIndent) = findOverridden(List.empty)
          expectTemplate.map(tmpl =>
            withOwner(tmpl.owner) {
              addOverridden(
                overridden.reverse
                  .map(_.name)
                  .distinct
                  .map(UnresolvedOverriddenSymbol(_))
              )
            }
          )
          loop(
            maybeNewIndent.getOrElse(indent),
            isAfterNewline = maybeNewIndent.isDefined,
            currRegion,
            expectTemplate
          )
        case IDENTIFIER if currRegion.emitIdentifier && includeMembers =>
          withOwner(currRegion.owner) {
            term(
              scanner.curr.name,
              newPosition,
              Kind.METHOD,
              SymbolInformation.Property.VAL.value
            )
          }
          loop(
            indent,
            isAfterNewline = false,
            currRegion.changeCaseClassState(false),
            expectTemplate
          )
        case CASE =>
          val nextIsNewLine = nextIsNL()
          val isAfterNewline =
            emitEnumCases(region, nextIsNewLine)
          loop(
            indent,
            isAfterNewline,
            currRegion,
            if (scanner.curr.token == CLASS) newExpectCaseClassTemplate()
            else newExpectClassTemplate()
          )
        case IMPLICIT =>
          scanner.mtagsNextToken()
          loop(
            indent,
            isAfterNewline,
            currRegion,
            newExpectImplicitTemplate,
            prevWasDot
          )
        case t =>
          val nextExpectTemplate = expectTemplate.filter(!_.isPackageBody)
          scanner.mtagsNextToken()
          loop(
            indent,
            isAfterNewline = false,
            currRegion,
            nextExpectTemplate,
            t == DOT
          )
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
      newIdentifier.foreach(buf += _)
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

  @tailrec
  private def acceptAllAfterOverriddenIdentifier(): Option[Int] = {
    val maybeNewIndent = acceptTrivia()
    scanner.curr.token match {
      case LPAREN =>
        acceptBalancedDelimeters(LPAREN, RPAREN)
        acceptAllAfterOverriddenIdentifier()
      case LBRACKET =>
        acceptBalancedDelimeters(LBRACKET, RBRACKET)
        acceptAllAfterOverriddenIdentifier()
      case _ => maybeNewIndent
    }

  }

  @tailrec
  private def findOverridden(
      acc0: List[Identifier]
  ): (List[Identifier], Option[Int]) = {
    val maybeNewIndent0 = acceptTrivia()
    scanner.curr.token match {
      case IDENTIFIER =>
        @tailrec
        def getIdentifier(): (Option[Identifier], Option[Int]) = {
          val currentIdentifier = newIdentifier
          val maybeNewIndent = acceptAllAfterOverriddenIdentifier()
          scanner.curr.token match {
            case DOT =>
              scanner.mtagsNextToken()
              getIdentifier()
            case _ => (currentIdentifier, maybeNewIndent)
          }
        }
        val (identifier, maybeNewIndent) = getIdentifier()
        val acc = identifier.toList ++ acc0
        scanner.curr.token match {
          case WITH => findOverridden(acc)
          case COMMA => findOverridden(acc)
          case _ => (acc, maybeNewIndent)
        }
      case LBRACE =>
        acceptBalancedDelimeters(LBRACE, RBRACE)
        val maybeNewIndent = acceptTrivia()
        scanner.curr.token match {
          case WITH => findOverridden(acc0)
          case _ => (acc0, maybeNewIndent)
        }
      case _ => (acc0, maybeNewIndent0)
    }
  }

  /**
   * Enters a toplevel symbol such as class, trait or object
   */
  def emitMember(isPackageObject: Boolean, owner: String): Unit = {
    val kind = scanner.curr.token
    acceptTrivia()
    val maybeName = newIdentifier
    currentOwner = owner
    maybeName.foreach { name =>
      kind match {
        case CLASS | ENUM =>
          tpe(name.name, name.pos, Kind.CLASS, 0)
        case TRAIT =>
          tpe(name.name, name.pos, Kind.TRAIT, 0)
        case OBJECT =>
          if (isPackageObject) {
            currentOwner = symbol(Scala.Descriptor.Package(name.name))
            term("package", name.pos, Kind.OBJECT, 0)
          } else {
            term(name.name, name.pos, Kind.OBJECT, 0)
          }
      }
    }
    scanner.mtagsNextToken()
  }

  def emitType(emitTermMember: Boolean): Option[Unit] = {
    acceptTrivia()
    newIdentifier
      .map { ident =>
        val typeSymbol = symbol(Descriptor.Type(ident.name))
        if (emitTermMember) {
          tpe(ident.name, ident.pos, Kind.TYPE, 0)
        }
        @tailrec
        def loop(
            name: Option[String],
            isAfterEq: Boolean = false
        ): Option[String] = {
          scanner.curr.token match {
            case SEMI => name
            case _ if isNewline | isDone => name
            case EQUALS =>
              scanner.mtagsNextToken()
              loop(name, isAfterEq = true)
            case TYPELAMBDAARROW | WHITESPACE =>
              scanner.mtagsNextToken()
              loop(name, isAfterEq)
            case LBRACKET =>
              acceptBalancedDelimeters(LBRACKET, RBRACKET)
              scanner.mtagsNextToken()
              loop(name, isAfterEq)
            case LBRACE =>
              acceptBalancedDelimeters(LBRACE, RBRACE)
              scanner.mtagsNextToken()
              loop(name, isAfterEq)
            case IDENTIFIER
                if isAfterEq && scanner.curr.name != "|" && scanner.curr.name != "&" =>
              loop(identOrSelectName(), isAfterEq)
            case _ if isAfterEq => None
            case _ =>
              scanner.mtagsNextToken()
              loop(name)
          }
        }

        loop(name = None).foreach { rhsName =>
          overridden += ((
            typeSymbol,
            List(UnresolvedOverriddenSymbol(rhsName))
          ))
        }
      }
  }

  /**
   * Enters a global element (def/val/var/given)
   */
  def emitTerm(region: Region, isParentImplicit: Boolean): Unit = {
    val extensionProperty = if (isParentImplicit) EXTENSION else 0
    val kind = scanner.curr.token
    acceptTrivia()
    kind match {
      case VAL =>
        valIdentifiers.foreach(name => {
          term(
            name.name,
            name.pos,
            Kind.METHOD,
            SymbolInformation.Property.VAL.value | extensionProperty
          )
          resetRegion(region)
        })
      case VAR =>
        valIdentifiers.foreach(name => {
          method(
            name.name,
            "()",
            name.pos,
            SymbolInformation.Property.VAR.value | extensionProperty
          )
          resetRegion(region)
        })
      case DEF =>
        methodIdentifier.foreach(name =>
          method(
            name.name,
            region.overloads.disambiguator(name.name),
            name.pos,
            extensionProperty
          )
        )
      case GIVEN =>
        newGivenIdentifier.foreach { name =>
          method(
            name.name,
            region.overloads.disambiguator(name.name),
            name.pos,
            SymbolInformation.Property.GIVEN.value
          )
        }
    }

  }

  @tailrec
  private def emitEnumCases(
      region: Region,
      nextIsNewLine: Boolean
  ): Boolean = {
    def ownerCompanionObject =
      if (currentOwner.endsWith("#"))
        s"${currentOwner.stripSuffix("#")}."
      else currentOwner
    scanner.curr.token match {
      case IDENTIFIER =>
        val pos = newPosition
        val name = scanner.curr.name
        def emitEnumCaseObject() = {
          currentOwner = ownerCompanionObject
          term(
            name,
            pos,
            Kind.METHOD,
            SymbolInformation.Property.VAL.value
          )
        }
        def emitOverridden() = addOverridden(
          List(ResolvedOverriddenSymbol(region.owner))
        )
        val nextIsNewLine0 = nextIsNL()
        scanner.curr.token match {
          case COMMA =>
            emitEnumCaseObject()
            emitOverridden()
            resetRegion(region)
            val nextIsNewLine1 = nextIsNL()
            emitEnumCases(region, nextIsNewLine1)
          case LPAREN | LBRACKET =>
            currentOwner = ownerCompanionObject
            tpe(
              name,
              pos,
              Kind.CLASS,
              SymbolInformation.Property.VAL.value
            )
            false
          case tok =>
            emitEnumCaseObject()
            if (tok != EXTENDS) {
              emitOverridden()
            }
            nextIsNewLine0
        }
      case _ => nextIsNewLine
    }
  }

  /**
   * Consumes the token stream until the matching closing delimiter
   */
  def acceptBalancedDelimeters(Open: Int, Close: Int): Unit = {
    require(scanner.curr.token == Open, "open delimeter { or (")
    var count = 1
    while (!isDone && count > 0) {
      scanner.mtagsNextToken()
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
            if (isNewline) { scanner.mtagsNextToken(); loop(0, true) }
            else if (isAfterNL) {
              scanner.mtagsNextToken(); loop(indent + 1, true)
            } else { scanner.mtagsNextToken(); loop(indent, false) }
          case COMMENT =>
            scanner.mtagsNextToken()
            loop(indent, false)
          case _ if indent <= exitIndent => indent
          case _ =>
            scanner.mtagsNextToken()
            loop(indent, false)
        }
      } else indent
    }
    loop(0, true)
  }

  def acceptToStatSep(): Unit = {
    scanner.mtagsNextToken()
    while (
      !isDone &&
      (scanner.curr.token match {
        case WHITESPACE if isNewline => false
        case SEMI => false
        case _ => true
      })
    ) {
      scanner.mtagsNextToken()
    }
  }

  private def acceptTrivia(): Option[Int] = {
    var includedNewline = false
    var indent = 0
    scanner.mtagsNextToken()
    while (
      !isDone &&
      (scanner.curr.token match {
        case WHITESPACE | COMMENT => true
        case _ => false
      })
    ) {
      if (isNewline) {
        includedNewline = true
        indent = 0
      } else if (scanner.curr.token == WHITESPACE) {
        indent += 1
      }
      scanner.mtagsNextToken()
    }
    if (includedNewline) Some(indent) else None
  }

  private def nextIsNL(): Boolean = {
    scanner.mtagsNextToken()
    scanner.curr.token match {
      case WHITESPACE if isNewline => true
      case WHITESPACE =>
        nextIsNL()
      case COMMENT =>
        nextIsNL()
      case _ => false
    }
  }

  /**
   * Returns a name and position for the current identifier token
   */
  def newIdentifier: Option[Identifier] = {
    scanner.curr.token match {
      case IDENTIFIER =>
        val pos = newPosition
        val name = scanner.curr.name
        Some(new Identifier(name, pos))
      case _ =>
        reportError("identifier")
        None
    }
  }

  private def getName: Option[String] =
    scanner.curr.token match {
      case WHITESPACE => None
      case _ => Some(scanner.curr.name)
    }

  @tailrec
  private def identOrSelectName(
      current: Option[String] = getName
  ): Option[String] = {
    nextIsNL()
    scanner.curr.token match {
      case DOT =>
        nextIsNL()
        val newIdent = getName
        if (newIdent.exists(_ == "type")) {
          scanner.mtagsNextToken()
          current
        } else identOrSelectName(newIdent)
      case _ => current
    }
  }

  /**
   * Returns a name and position for the current identifier token
   */
  def methodIdentifier: Option[Identifier] = {
    scanner.curr.token match {
      case IDENTIFIER =>
        Some(new Identifier(scanner.curr.name, newPosition))
      case THIS =>
        None
      case _ =>
        reportError("identifier")
        None
    }
  }

  def newGivenIdentifier: Option[Identifier] = {
    @tailrec
    def consumeParams(): Unit = {
      acceptTrivia()
      scanner.curr.token match {
        case LPAREN =>
          acceptBalancedDelimeters(LPAREN, RPAREN)
          consumeParams()
        case LBRACKET =>
          acceptBalancedDelimeters(LBRACKET, RBRACKET)
          consumeParams()
        case _ =>
      }
    }

    scanner.curr.token match {
      case IDENTIFIER =>
        val identifier = newIdentifier
        consumeParams()
        scanner.curr.token match {
          case COLON => identifier
          case _ => None
        }
      case _ => None
    }
  }

  def valIdentifiers: List[Identifier] = {
    var resultList: List[Identifier] = Nil
    var isUnapply = false
    while (
      scanner.curr.token != EQUALS && scanner.curr.token != COLON && scanner.curr.token != EOF
    ) {
      scanner.curr.token match {
        case IDENTIFIER => {
          val pos = newPosition
          val name = scanner.curr.name
          resultList = new Identifier(name, pos) :: resultList
        }
        case WHITESPACE | COMMA => {}
        case _ => { isUnapply = true }
      }
      scanner.mtagsNextToken()
    }
    if (isUnapply) resultList.filterNot(_.name.charAt(0).isUpper)
    else resultList
  }

  private def isNewline: Boolean =
    scanner.curr.token == WHITESPACE &&
      (scanner.curr.strVal match {
        case "\n" | "\r" => true
        case _ => false
      })

  def reportError(expected: String): Unit = {
    rc.incognito.create(
      Report(
        "scala-toplevel-mtags",
        failMessage(expected),
        s"expected $expected; obtained $currentToken",
        id = Some(s"""${input.path}:${newPosition}"""),
        path = Some(input.path)
      )
    )
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

  final case class CaseClassState(state: Int, openParen: Int = 0) {
    def openParenthesis(): CaseClassState = CaseClassState(state, openParen + 1)
    def closeParenthesis(): CaseClassState =
      CaseClassState(state, openParen - 1)
  }

  final case class ExpectTemplate(
      indent: Int,
      owner: String,
      isPackageBody: Boolean,
      isExtension: Boolean = false,
      ignoreBody: Boolean = false,
      isCaseClassConstructor: Boolean = false,
      isClassConstructor: Boolean = false,
      isImplicit: Boolean = false
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

    def startInBraceRegion(
        prev: Region,
        extension: Boolean = false,
        isImplicitClass: Boolean = false
    ): Region =
      new Region.InBrace(owner, adjustRegion(prev), extension, isImplicitClass)

    def startInParenRegion(prev: Region, isCaseClass: Boolean): Region =
      if (isCaseClass) Region.InParenCaseClass(owner, adjustRegion(prev), true)
      else Region.InParenClass(owner, adjustRegion(prev))

    def startIndentedRegion(
        prev: Region,
        extension: Boolean = false,
        isImplicitClass: Boolean = false
    ): Region =
      new Region.Indented(
        owner,
        indent,
        adjustRegion(prev),
        extension,
        isImplicitClass: Boolean
      )

  }

  sealed trait Region {
    def prev: Region
    def owner: String
    def acceptMembers: Boolean
    def produceSourceToplevel: Boolean = termOwner.isPackage
    def isExtension: Boolean = false
    def isImplicit: Boolean = false
    val overloads: OverloadDisambiguator = new OverloadDisambiguator()
    def termOwner: String =
      owner // toplevel terms are wrapped into an artificial Object
    val withTermOwner: String => Region = _ => this
    def emitIdentifier: Boolean = false
    val changeCaseClassState: Boolean => Region = _ => this
  }

  object Region {

    final case class RootRegion(override val termOwner: String) extends Region {
      self =>
      def this() = this(Symbols.EmptyPackage)
      val owner: String = Symbols.EmptyPackage
      val prev: Region = self
      val acceptMembers: Boolean = true
      override val withTermOwner: String => RootRegion = termOwner =>
        RootRegion(termOwner)
    }

    final case class Package(
        owner: String,
        prev: Region,
        override val termOwner: String
    ) extends Region {
      def this(owner: String, prev: Region) = this(owner, prev, owner)
      val acceptMembers: Boolean = true
      override val withTermOwner: String => Package = termOwner =>
        Package(owner, prev, termOwner)
    }

    final case class InBrace(
        owner: String,
        prev: Region,
        extension: Boolean = false,
        override val termOwner: String,
        override val isImplicit: Boolean
    ) extends Region {
      def this(
          owner: String,
          prev: Region,
          extension: Boolean,
          isImplicit: Boolean
      ) = this(owner, prev, extension, owner, isImplicit)
      def acceptMembers: Boolean =
        owner.endsWith("/")

      override def isExtension = extension

      override val withTermOwner: String => InBrace = termOwner =>
        InBrace(owner, prev, extension, termOwner, isImplicit)
    }
    final case class Indented(
        owner: String,
        exitIndent: Int,
        prev: Region,
        extension: Boolean = false,
        override val termOwner: String,
        override val isImplicit: Boolean
    ) extends Region {
      def this(
          owner: String,
          exitIndent: Int,
          prev: Region,
          extension: Boolean,
          isImplicit: Boolean
      ) = this(owner, exitIndent, prev, extension, owner, isImplicit)
      def acceptMembers: Boolean =
        owner.endsWith("/")
      override def isExtension = extension
      override val withTermOwner: String => Indented = termOwner =>
        Indented(owner, exitIndent, prev, extension, termOwner, isImplicit)
    }

    final case class InParenClass(
        owner: String,
        prev: Region
    ) extends Region {
      def acceptMembers: Boolean = false
      override val produceSourceToplevel: Boolean = false
      override val emitIdentifier: Boolean = false
    }

    final case class InParenCaseClass(
        owner: String,
        prev: Region,
        override val emitIdentifier: Boolean
    ) extends Region {
      def acceptMembers: Boolean = false
      override val produceSourceToplevel: Boolean = false
      override val changeCaseClassState: Boolean => Region = ei =>
        this.copy(emitIdentifier = ei)
    }
  }
}
