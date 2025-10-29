package scala.meta.internal.mtags

import java.nio.file.Paths
import java.util.Optional

import scala.annotation.tailrec

import scala.meta.Dialect
import scala.meta.inputs.Input
import scala.meta.inputs.Position
import scala.meta.internal.inputs._
import scala.meta.internal.metals.Report
import scala.meta.internal.mtags.ScalametaCommonEnrichments._
import scala.meta.internal.semanticdb
import scala.meta.internal.semanticdb.Implicits._
import scala.meta.internal.semanticdb.Language
import scala.meta.internal.semanticdb.Scala
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.semanticdb.SymbolInformation.Kind
import scala.meta.internal.tokenizers.LegacyScanner
import scala.meta.internal.tokenizers.LegacyToken
import scala.meta.internal.tokenizers.LegacyToken._
import scala.meta.internal.tokenizers.LegacyTokenData
import scala.meta.pc.reports.ReportContext
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

  private val scanner = new LegacyScanner(input, dialect)
  scanner.initialize()
  private var curr: LegacyTokenData = scanner.nextToken()

  override def overrides(): List[(String, List[OverriddenSymbol])] =
    overridden.result()

  override def toplevelMembers(): List[ToplevelMember] =
    toplevelMembersBuilder.result()

  private val overridden = List.newBuilder[(String, List[OverriddenSymbol])]
  private val toplevelMembersBuilder = List.newBuilder[ToplevelMember]

  private def addOverridden(symbols: List[OverriddenSymbol]) =
    overridden += ((currentOwner, symbols))

  private def addToplevelMembers(members: List[ToplevelMember]) =
    toplevelMembersBuilder ++= members

  import ScalaToplevelMtags._

  private val identifiers = Set.newBuilder[String]
  override def allIdentifiers: Set[String] = identifiers.result()

  implicit class XtensionScanner(scanner: LegacyScanner) {
    def mtagsNextToken(): Any = {
      curr = scanner.nextTokenOrEof()
      if (collectIdentifiers)
        curr.token match {
          case IDENTIFIER => identifiers += curr.strVal
          case _ =>
        }
    }
  }

  override def language: Language = Language.SCALA

  override def indexRoot(): Unit =
    loop(Indent.init, new Region.RootRegion, None)

  def isDone: Boolean = curr.token == EOF || curr.token == PASTEOF

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
      indent: Indent,
      region: Region,
      expectTemplate: Option[ExpectTemplate],
      prevWasDot: Boolean = false
  ): Unit = {
    def newExpectTemplate(isImplicit: Boolean = false): Some[ExpectTemplate] =
      Some(
        ExpectTemplate(
          indent.indent,
          currentOwner,
          false,
          false,
          isImplicit = isImplicit
        )
      )
    def newExpectCaseClassTemplate(): Some[ExpectTemplate] =
      Some(
        ExpectTemplate(
          indent.indent,
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
          indent.indent,
          currentOwner,
          false,
          false,
          isClassConstructor = true,
          isImplicit = isImplicit
        )
      )
    def newExpectPkgTemplate: Some[ExpectTemplate] =
      Some(ExpectTemplate(indent.indent, currentOwner, true, false))
    def newExpectExtensionTemplate(owner: String): Some[ExpectTemplate] =
      Some(ExpectTemplate(indent.indent, owner, false, true))
    def newExpectImplicitTemplate: Some[ExpectTemplate] =
      Some(
        ExpectTemplate(
          indent.indent,
          currentOwner,
          false,
          false,
          isImplicit = true
        )
      )
    def newExpectIgnoreBody: Some[ExpectTemplate] =
      Some(
        ExpectTemplate(
          indent = indent.indent,
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
      val data = curr
      val currRegion =
        if (dialect.allowSignificantIndentation) {
          data.token match {
            case token if isWhitespace(token) => region
            case COMMENT => region
            case _ =>
              resetRegion(exitIndented(region, indent.indent))
          }
        } else region
      data.token match {
        case PACKAGE =>
          val isNotPackageObject = emitPackage(currRegion.owner)
          if (isNotPackageObject) {
            val nextRegion = new Region.Package(currentOwner, currRegion)
            loop(indent.notAfterNewline, nextRegion, newExpectPkgTemplate)
          } else
            loop(indent.notAfterNewline, currRegion, newExpectTemplate())
        case IDENTIFIER
            if dialect.allowExtensionMethods && data.strVal == "extension" =>
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
            indent.notAfterNewline,
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
              term(name, pos, Kind.PACKAGE_OBJECT, 0)
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
            indent.notAfterNewline,
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
          loop(
            parseMemberDefinitionLhs(DEF, indent),
            currRegion,
            newExpectIgnoreBody
          )
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
              loop(indent.notAfterNewline, currRegion, None)
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
            indent.notAfterNewline,
            currRegion.withTermOwner(owner),
            expectTemplate
          )
        case t @ (DEF | VAL | VAR | GIVEN)
            if expectTemplate.map(!_.isExtension).getOrElse(true) =>
          val isImplicit =
            if (isInParenthesis(region))
              expectTemplate.exists(
                _.isImplicit
              )
            else region.isImplicit
          withOwner(currRegion.termOwner) {
            emitTerm(currRegion, isImplicit, needEmitTermMember())
          }
          val newIndent = parseMemberDefinitionLhs(t, indent)
          loop(
            newIndent,
            currRegion,
            if (isInParenthesis(region)) expectTemplate else newExpectIgnoreBody
          )
        case TYPE if expectTemplate.map(!_.isExtension).getOrElse(true) =>
          if (needEmitMember(currRegion) && !prevWasDot) {
            withOwner(currRegion.termOwner) {
              emitType(
                needEmitTermMember()
              )
            }
          } else scanner.mtagsNextToken()
          loop(indent.notAfterNewline, currRegion, newExpectIgnoreBody)
        case IMPORT | EXPORT =>
          // skip imports because they might have `given` kw
          acceptToStatSep()
          loop(indent.notAfterNewline, currRegion, expectTemplate)
        case COMMENT =>
          // skip comment because they might break indentation
          scanner.mtagsNextToken()
          loop(indent.notAfterNewline, currRegion, expectTemplate)
        case EQUALS
            if expectTemplate.exists(
              _.ignoreBody
            ) && dialect.allowSignificantIndentation =>
          val nextIndent =
            acceptWhileIndented(expectTemplate.get.indent, isInsideBody = false)
          loop(
            nextIndent,
            currRegion,
            None
          )
        case token
            if isWhitespace(token) && dialect.allowSignificantIndentation =>
          if (isNewline) {
            expectTemplate match {
              // extension (x: Int)|
              //   def foo() = ...
              case Some(expect) if needToParseExtension(expect) =>
                val next =
                  expect.startIndentedRegion(currRegion, expect.isExtension)
                resetRegion(next)
                scanner.mtagsNextToken()
                loop(Indent.init, next, None)
              case _ =>
                scanner.mtagsNextToken()
                loop(
                  Indent.init,
                  currRegion,
                  expectTemplate
                )
            }
          } else {
            val nextIndentLevel =
              if (indent.isAfterNewline) indent.indent + 1 else indent.indent
            scanner.mtagsNextToken()
            loop(
              Indent(nextIndentLevel, indent.isAfterNewline),
              currRegion,
              expectTemplate
            )
          }
        case MATCH | THEN | ELSE | DO | WHILE | TRY | FINALLY | THROW | RETURN |
            YIELD | FOR if dialect.allowSignificantIndentation =>
          if (nextIsNL()) {
            loop(
              acceptWhileIndented(indent.indent),
              currRegion,
              None
            )
          } else {
            loop(
              indent.notAfterNewline,
              currRegion,
              expectTemplate
            )
          }
        case COLON if dialect.allowSignificantIndentation =>
          (expectTemplate, nextIsNL()) match {
            case (Some(expect), true) if needToParseBody(expect) =>
              if (expect.isImplicit) {
                toplevelMembersBuilder += ToplevelMember(
                  currentOwner,
                  semanticdb.Range(0, 0, 0, 0),
                  )
                ToplevelMember
                    /** EndMarker */
                    .Kind.ImplicitClass.Kind.ImplicitClass
                )
              }
              val next = expect.startIndentedRegion(
                currRegion,
                isImplicitClass = expect.isImplicit
              )
              resetRegion(next)
              loop(maybeFindAndEmitSelfType(Indent.init), next, None)
            case (Some(expect), true) =>
              val nextIndent = acceptWhileIndented(expect.indent)
              loop(
                nextIndent,
                currRegion,
                None
              )
            case (_, true) =>
              val nextIndent = acceptWhileIndented(indent.indent)
              loop(
                nextIndent,
                currRegion,
                expectTemplate
              )
            case _ =>
              scanner.mtagsNextToken()
              loop(
                indent.notAfterNewline,
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
                loop(indent.notAfterNewline, currRegion, expectTemplate)
              } else {
                if (expect.isImplicit) {
                  toplevelMembersBuilder += ToplevelMember(
                    currentOwner,
                    semanticdb.Range(0, 0, 0, 0),
                    ToplevelMember.Kind.ImplicitClass
                  )
                }
                val next =
                  expect.startInBraceRegion(
                    currRegion,
                    expect.isExtension,
                    expect.isImplicit
                  )
                resetRegion(next)
                loop(
                  maybeFindAndEmitSelfType(indent.notAfterNewline),
                  next,
                  None
                )
              }
            case _ =>
              acceptBalancedDelimeters(LBRACE, RBRACE)
              scanner.mtagsNextToken()
              loop(indent.notAfterNewline, currRegion, None)
          }
        case RBRACE =>
          val nextRegion = currRegion match {
            case Region.InBrace(_, prev, _, _, _) => resetRegion(prev)
            case r => r
          }
          scanner.mtagsNextToken()
          loop(indent, nextRegion, None)
        case LBRACKET =>
          acceptBalancedDelimeters(LBRACKET, RBRACKET)
          scanner.mtagsNextToken()
          loop(indent.notAfterNewline, currRegion, expectTemplate)
        case LPAREN =>
          expectTemplate match {
            case Some(expect)
                if expect.isClassConstructor && includeInnerClasses => {
              scanner.mtagsNextToken()
              loop(
                indent.notAfterNewline,
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
              loop(indent.notAfterNewline, currRegion, expectTemplate)
            }
          }
        case RPAREN if (currRegion match {
              case _: Region.InParenCaseClass => true
              case _: Region.InParenClass => true
              case _ => false
            }) =>
          scanner.mtagsNextToken()
          loop(
            indent.notAfterNewline,
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
            indent.notAfterNewline,
            currRegion.changeCaseClassState(true),
            nextExpectTemplate
          )
        case EXTENDS =>
          val (overridden, newIndent) = findOverridden(List.empty, indent)
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
            newIndent,
            currRegion,
            expectTemplate
          )
        case IDENTIFIER if currRegion.emitIdentifier && includeMembers =>
          withOwner(currRegion.owner) {
            term(
              curr.strVal,
              newPosition,
              Kind.METHOD,
              SymbolInformation.Property.VAL.value
            )
          }
          loop(
            indent.notAfterNewline,
            currRegion.changeCaseClassState(false),
            expectTemplate
          )
        case CASE =>
          val nextIsNewLine = nextIsNL()
          val isAfterNewline =
            emitEnumCases(region, nextIsNewLine)
          loop(
            Indent(indent.indent, isAfterNewline),
            currRegion,
            if (curr.token == CLASS) newExpectCaseClassTemplate()
            else newExpectClassTemplate()
          )
        case IMPLICIT =>
          scanner.mtagsNextToken()
          loop(
            indent,
            currRegion,
            newExpectImplicitTemplate,
            prevWasDot
          )
        case t =>
          val nextExpectTemplate = expectTemplate.filter(!_.isPackageBody)
          scanner.mtagsNextToken()
          loop(
            indent.notAfterNewline,
            currRegion,
            nextExpectTemplate,
            t == DOT
          )
      }
    } else ()
  }

  private def maybeFindAndEmitSelfType(
      indent0: Indent
  ): Indent = {
    var toEmit: List[String] = Nil

    @tailrec
    def collectType(indent0: Indent): Indent = {
      val indent1 = indent0.withOptIndent(acceptTrivia())
      if (curr.token == IDENTIFIER) {
        identOrSelectName().map(name => toEmit = name :: toEmit)
        val indent2 =
          acceptAllAfterOverriddenIdentifier(indent1, alreadyMoved = true)
        curr.token match {
          case WITH => collectType(indent2)
          case IDENTIFIER if curr.strVal == "&" =>
            collectType(indent2)
          case IDENTIFIER if curr.strVal == "|" =>
            toEmit = Nil
            indent2
          case _ => indent2
        }
      } else indent1
    }

    var indent = indent0.withOptIndent(acceptTrivia())
    if (
      (curr.token == IDENTIFIER || curr.token == THIS) && curr.strVal != "extension"
    ) {
      indent = indent.withOptIndent(acceptTrivia())
      if (curr.token == COLON) {
        indent = collectType(indent)
        if (curr.token == ARROW && toEmit.nonEmpty) {
          overridden += ((
            currentOwner,
            toEmit.map(UnresolvedOverriddenSymbol(_))
          ))
        }
      }
    }
    indent
  }

  def emitPackage(owner: String): Boolean = {
    require(curr.token == PACKAGE, "package")
    if (currentOwner eq Symbols.EmptyPackage) {
      currentOwner = Symbols.RootPackage
    }
    currentOwner = owner
    acceptTrivia()
    curr.token match {
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
      curr.token match {
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
  private def acceptAllAfterOverriddenIdentifier(
      indent: Indent,
      alreadyMoved: Boolean = false
  ): Indent = {
    val newIndent =
      if (alreadyMoved) indent else indent.withOptIndent(acceptTrivia())
    curr.token match {
      case LPAREN =>
        acceptBalancedDelimeters(LPAREN, RPAREN)
        acceptAllAfterOverriddenIdentifier(newIndent.notAfterNewline)
      case LBRACKET =>
        acceptBalancedDelimeters(LBRACKET, RBRACKET)
        acceptAllAfterOverriddenIdentifier(newIndent.notAfterNewline)
      case _ => newIndent
    }

  }

  private def parseMemberDefinitionLhs(
      token: LegacyToken,
      indent0: Indent
  ): Indent =
    token match {
      case DEF =>
        val ident1 = parseMethodArgs(indent0)
        parseTypeAnnotation(ident1)
      case _ => parseTypeAnnotation(indent0)
    }

  private def parseTypeAnnotation(indent: Indent) = {
    curr.token match {
      case COLON =>
        val newIdent = indent.withOptIndent(acceptTrivia())
        curr.token match {
          case IDENTIFIER =>
            acceptAllAfterOverriddenIdentifier(newIdent)
          case _ => newIdent
        }
      case _ => indent
    }
  }

  @tailrec
  private def parseMethodArgs(indent0: Indent): Indent = {
    val indent = indent0.withOptIndent(acceptTrivia())
    curr.token match {
      case LBRACKET =>
        acceptBalancedDelimeters(LBRACKET, RBRACKET)
        parseMethodArgs(indent)
      case LPAREN =>
        acceptBalancedDelimeters(LPAREN, RPAREN)
        parseMethodArgs(indent)
      case _ => indent
    }
  }

  @tailrec
  private def findOverridden(
      acc0: List[Identifier],
      indent: Indent
  ): (List[Identifier], Indent) = {
    val newIndent = indent.withOptIndent(acceptTrivia())
    curr.token match {
      case IDENTIFIER =>
        @tailrec
        def getIdentifier(indent: Indent): (Option[Identifier], Indent) = {
          val currentIdentifier = newIdentifier
          val newIndent = acceptAllAfterOverriddenIdentifier(indent)
          curr.token match {
            case DOT =>
              getIdentifier(newIndent.withOptIndent(acceptTrivia()))
            case _ => (currentIdentifier, newIndent)
          }
        }
        val (identifier, currIdent) = getIdentifier(newIndent)
        val acc = identifier.toList ++ acc0
        curr.token match {
          case WITH => findOverridden(acc, currIdent)
          case COMMA => findOverridden(acc, currIdent)
          case _ => (acc, currIdent)
        }
      case LBRACE =>
        acceptBalancedDelimeters(LBRACE, RBRACE)
        val currIdent = newIndent.withOptIndent(acceptTrivia())
        curr.token match {
          case WITH => findOverridden(acc0, currIdent)
          case _ => (acc0, currIdent)
        }
      case _ => (acc0, newIndent)
    }
  }

  /**
   * Enters a toplevel symbol such as class, trait or object
   */
  def emitMember(isPackageObject: Boolean, owner: String): Unit = {
    val kind = curr.token
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
            term("package", name.pos, Kind.PACKAGE_OBJECT, 0)
          } else {
            term(name.name, name.pos, Kind.OBJECT, 0)
          }
      }
    }
    scanner.mtagsNextToken()
  }

  def emitType(
      emitTermMember: Boolean
  ): Option[Unit] = {
    acceptTrivia()
    newIdentifier
      .map { ident =>
        val typeSymbol = symbol(Descriptor.Type(ident.name))
        if (owner.endsWith("/package.") || owner.endsWith("$package.")) {
          addToplevelMembers(
            List(
              ToplevelMember(
                typeSymbol,
                ident.pos.toRange,
                ToplevelMember.Kind.Type
              )
            )
          )
        }
        if (emitTermMember) {
          tpe(ident.name, ident.pos, Kind.TYPE, 0)
        }
        @tailrec
        def loop(
            name: Option[String],
            isAfterEq: Boolean = false
        ): Option[String] = {
          curr.token match {
            case SEMI | RBRACE => name
            case _ if isNewline | isDone => name
            case EQUALS =>
              scanner.mtagsNextToken()
              loop(name, isAfterEq = true)
            case TYPELAMBDAARROW =>
              scanner.mtagsNextToken()
              loop(name, isAfterEq)
            case token if isWhitespace(token) =>
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
                if isAfterEq && curr.strVal != "|" && curr.strVal != "&" =>
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
  def emitTerm(
      region: Region,
      isParentImplicit: Boolean,
      shouldEmit: Boolean
  ): Unit = {
    val extensionProperty = if (isParentImplicit) EXTENSION else 0
    val kind = curr.token
    acceptTrivia()
    kind match {
      case VAL =>
        valIdentifiers.foreach(name => {
          if (shouldEmit)
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
          if (shouldEmit)
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
          if (shouldEmit)
            method(
              name.name,
              region.overloads.disambiguator(name.name),
              name.pos,
              extensionProperty
            )
        )
      case GIVEN =>
        newGivenIdentifier.foreach { name =>
          if (shouldEmit)
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
    curr.token match {
      case IDENTIFIER =>
        val pos = newPosition
        val name = curr.strVal
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
        curr.token match {
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
    require(curr.token == Open, "open delimeter { or (")
    var count = 1
    while (!isDone && count > 0) {
      scanner.mtagsNextToken()
      curr.token match {
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
  def acceptWhileIndented(
      exitIndent: Int,
      isInsideBody: Boolean = true
  ): Indent = {
    @tailrec
    def loop(indent: Int, isAfterNL: Boolean, isInsideBody: Boolean): Int = {
      if (!isDone) {
        curr.token match {
          case _ if isNewline =>
            scanner.mtagsNextToken(); loop(0, true, isInsideBody)
          case token if isWhitespace(token) && isAfterNL =>
            scanner.mtagsNextToken()
            loop(indent + 1, true, isInsideBody)
          case token if isWhitespace(token) =>
            scanner.mtagsNextToken()
            loop(indent, false, isInsideBody)
          case COMMENT =>
            scanner.mtagsNextToken()
            loop(indent, false, isInsideBody)
          case _ if isInsideBody && indent <= exitIndent => indent
          case LBRACE if !isInsideBody =>
            acceptBalancedDelimeters(LBRACE, RBRACE)
            scanner.mtagsNextToken()
            exitIndent
          case EQUALS =>
            scanner.mtagsNextToken()
            loop(indent, false, isInsideBody)
          case _ if !isInsideBody =>
            acceptToStatSep()
            loop(indent, false, isInsideBody = true)
          case _ =>
            scanner.mtagsNextToken()
            loop(indent, false, isInsideBody)
        }
      } else indent
    }
    Indent(loop(0, true, isInsideBody), false)
  }

  def acceptToStatSep(): Unit = {
    scanner.mtagsNextToken()
    while (
      !isDone &&
      (curr.token match {
        case SEMI => false
        case LBRACE =>
          acceptBalancedDelimeters(LBRACE, RBRACE)
          true
        case _ => !isNewline
      })
    ) {
      scanner.mtagsNextToken()
    }
  }

  private def acceptTrivia(): Option[Int] = {
    var includedNewline = isNewline
    var indent = 0
    scanner.mtagsNextToken()
    while (
      !isDone &&
      (curr.token match {
        case COMMENT => true
        case token => isWhitespace(token)
      })
    ) {
      if (isNewline) {
        includedNewline = true
        indent = 0
      } else if (isWhitespace(curr.token)) {
        indent += 1
      }
      scanner.mtagsNextToken()
    }
    if (includedNewline) Some(indent) else None
  }

  private def isWhitespace(token: Int): Boolean = {
    token >= WHITESPACE_BEG && token < COMMENT
  }

  private def nextIsNL(): Boolean = {
    scanner.mtagsNextToken()
    curr.token match {
      case token if isWhitespace(token) =>
        isNewline || nextIsNL()
      case COMMENT =>
        nextIsNL()
      case _ => false
    }
  }

  /**
   * Returns a name and position for the current identifier token
   */
  def newIdentifier: Option[Identifier] = {
    curr.token match {
      case IDENTIFIER =>
        val pos = newPosition
        val name = curr.strVal
        Some(new Identifier(name, pos))
      case _ =>
        reportError("identifier")
        None
    }
  }

  private def getName: Option[String] = {
    if (isWhitespace(curr.token)) None
    else Some(curr.strVal)
  }

  @tailrec
  private def identOrSelectName(
      current: Option[String] = getName
  ): Option[String] = {
    nextIsNL()
    curr.token match {
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
    curr.token match {
      case IDENTIFIER =>
        Some(new Identifier(curr.strVal, newPosition))
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
      curr.token match {
        case LPAREN =>
          acceptBalancedDelimeters(LPAREN, RPAREN)
          consumeParams()
        case LBRACKET =>
          acceptBalancedDelimeters(LBRACKET, RBRACKET)
          consumeParams()
        case _ =>
      }
    }

    curr.token match {
      case IDENTIFIER =>
        val identifier = newIdentifier
        consumeParams()
        curr.token match {
          case COLON => identifier
          case _ => None
        }
      case _ => None
    }
  }

  def valIdentifiers: List[Identifier] = {
    var resultList: List[Identifier] = Nil
    var isUnapply = false
    while (curr.token != EQUALS && curr.token != COLON && curr.token != EOF) {
      curr.token match {
        case IDENTIFIER => {
          val pos = newPosition
          val name = curr.strVal
          resultList = new Identifier(name, pos) :: resultList
        }
        case COMMA => {}
        case token if isWhitespace(token) => {}
        case _ => { isUnapply = true }
      }
      scanner.mtagsNextToken()
    }
    if (isUnapply) resultList.filterNot(_.name.charAt(0).isUpper)
    else resultList
  }

  private def isNewline: Boolean =
    curr.token >= WHITESPACE_LF && curr.token < WHITESPACE_END

  def reportError(expected: String): Unit = {
    rc.incognito()
      .create(() =>
        Report(
          "scala-toplevel-mtags",
          failMessage(expected),
          s"expected $expected; obtained $currentToken",
          id = Optional.of(s"""${input.path}:${newPosition}"""),
          path = Optional.of(Paths.get(input.path).toUri())
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
    val start = curr.offset
    val end = curr.endOffset
    Position.Range(input, start, end)
  }

  def currentToken: String =
    InverseLegacyToken.category(curr.token).toLowerCase()

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

  case class Indent(indent: Int, isAfterNewline: Boolean) {
    def notAfterNewline: Indent = copy(isAfterNewline = false)
    def withOptIndent(optIndent: Option[Int]): Indent =
      copy(
        indent = optIndent.getOrElse(indent),
        isAfterNewline = optIndent.isDefined
      )
  }

  object Indent {
    def init: Indent = Indent(0, true)
  }
}
