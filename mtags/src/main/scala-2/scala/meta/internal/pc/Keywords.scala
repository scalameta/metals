package scala.meta.internal.pc

import scala.tools.nsc.reporters.StoreReporter

import scala.meta._
import scala.meta.internal.mtags.MtagsEnrichments._

import org.eclipse.{lsp4j => l}

trait Keywords { this: MetalsGlobal =>

  def keywords(
      pos: Position,
      editRange: l.Range,
      latestEnclosing: List[Tree],
      completion: CompletionPosition,
      text: String,
      isAmmoniteScript: Boolean
  ): List[Member] = {

    lazy val notInComment = checkIfNotInComment(pos, text, latestEnclosing)

    lazy val reverseTokens: Array[Token] = {
      // Try not to tokenize the whole file
      // Maybe we should re-use the tokenize result with `notInComment`
      val lineStart =
        if (pos.line > 0) pos.source.lineToOffset(pos.line - 1) else 0
      text.substring(lineStart, pos.start).tokenize.toOption match {
        case Some(toks) => toks.tokens.reverse
        case None => Array.empty[Token]
      }
    }

    getIdentifierName(latestEnclosing, pos) match {
      case None =>
        completion match {
          // This whole block is meant to catch top level completions, however
          // it's also valid to have a scaladoc comment at the top level, so we
          // explicitly check that we don't have a scaladocCompletion before we
          // grab the top level completions since it's safe to assume if someone
          // has already typed /* then they are going for the scaladoc, not the
          // other stuff.
          case _: ScaladocCompletion => List.empty
          case _ if notInComment =>
            Keyword.all.collect {
              case kw if kw.isPackage => mkTextEditMember(kw, editRange)
            }
          case _ => List.empty
        }
      case Some(name) if notInComment =>
        val isExpression = this.isExpression(latestEnclosing)
        val isBlock = this.isBlock(latestEnclosing)
        val isDefinition = this.isDefinition(latestEnclosing, name, pos)
        val isMethodBody = this.isMethodBody(latestEnclosing)
        val isTemplate = this.isTemplate(latestEnclosing)
        val isPackage = this.isPackage(latestEnclosing)
        val isParam = this.isParam(latestEnclosing)
        val isSelect = this.isSelect(latestEnclosing)
        val isImport = this.isImport(latestEnclosing)
        Keyword.all.collect {
          case kw
              if kw.matchesPosition(
                name,
                isExpression = isExpression,
                isBlock = isBlock,
                isDefinition = isDefinition,
                isMethodBody = isMethodBody,
                isTemplate = isTemplate,
                isPackage = isPackage,
                isParam = isParam,
                isScala3 = false,
                isSelect = isSelect,
                isImport = isImport,
                allowToplevel = isAmmoniteScript,
                canBeExtended =
                  KeywordCompletionsUtils.canBeExtended(reverseTokens),
                canDerive = KeywordCompletionsUtils.canDerive(reverseTokens),
                hasExtend = KeywordCompletionsUtils.hasExtend(reverseTokens)
              ) =>
            mkTextEditMember(kw, editRange)
        }
      case _ => List.empty
    }
  }

  private def checkIfNotInComment(
      pos: Position,
      text: String,
      enclosing: List[Tree]
  ): Boolean = {
    val (treeStart, treeEnd) = enclosing.headOption
      .map(t => (t.pos.start, t.pos.end))
      .getOrElse((0, text.size))
    val offset = pos.start
    text.mkString.checkIfNotInComment(treeStart, treeEnd, offset)
  }

  private def getIdentifierName(
      enclosing: List[Tree],
      pos: Position
  ): Option[String] = {
    enclosing match {
      case Ident(name) :: _ => Some(name.toString().stripSuffix(CURSOR))
      case _ => getLeadingIdentifierText(pos)
    }
  }

  private def getLeadingIdentifierText(pos: Position): Option[String] = {
    var i = pos.point
    val chars = pos.source.content
    while (i >= 0 && !chars(i).isWhitespace) {
      i -= 1
    }
    if (i < 0) None
    else Some(new String(chars, i + 1, pos.point - i - 1))
  }

  private def mkTextEditMember(
      keyword: Keyword,
      range: l.Range
  ): TextEditMember = {
    new TextEditMember(
      keyword.name,
      new l.TextEdit(range, keyword.insertText),
      NoSymbol.newErrorSymbol(TermName(keyword.name)).setInfo(NoType),
      label = Some(keyword.name),
      commitCharacter = keyword.commitCharacter
    )
  }

  private def isPackage(enclosing: List[Tree]): Boolean =
    enclosing match {
      case PackageDef(_, _) :: _ => true
      case Nil => true
      case _ => false
    }

  private def isParam(enclosing: List[Tree]): Boolean =
    enclosing match {
      case (_: DefDef) :: _ => true
      case _ => false
    }

  private def isTemplate(enclosing: List[Tree]): Boolean =
    enclosing match {
      case Ident(_) :: Template(_, _, _) :: _ => true
      case Ident(_) :: ValOrDefDef(_, _, _, _) :: _ => true
      case Template(_, _, _) :: _ => true
      case _ => false
    }

  private def isMethodBody(enclosing: List[Tree]): Boolean =
    enclosing match {
      case Ident(_) :: DefDef(_, _, _, _, _, _) :: _ => true
      case _ => false
    }

  private def isDefinition(
      enclosing: List[Tree],
      name: String,
      pos: Position
  ): Boolean = {
    enclosing match {
      case (_: Ident) :: _ => false
      case _ =>
        // NOTE(olafur) in positions like "implicit obje@@" the parser discards the entire
        // statement and `enclosing` is not helpful. In these situations we fallback to the
        // diagnostics reported by the parser to see if it expected a definition here.
        // This is admittedly not a great solution, but it's the best I can think of at this point.
        val point = pos.withPoint(pos.point - name.length())
        val isExpectedStartOfDefinition = storeReporter(reporter) match {
          case Some(s: StoreReporter) =>
            s.infos.exists { info =>
              info.pos.focus == point &&
              info.msg == "expected start of definition"
            }
          case _ => false
        }
        isExpectedStartOfDefinition
    }
  }

  private def isBlock(enclosing: List[Tree]): Boolean =
    enclosing match {
      case Ident(_) :: Block(_, _) :: _ => true
      case _ => false
    }

  private def isExpression(enclosing: List[Tree]): Boolean =
    enclosing match {
      case Ident(_) :: Template(_, _, _) :: _ => true
      case Ident(_) :: ValOrDefDef(_, _, _, _) :: _ => true
      case Ident(_) :: (_: TermTreeApi) :: _ => true
      case _ => false
    }

  private def isSelect(enclosing: List[Tree]): Boolean =
    enclosing match {
      case (_: Ident) :: (_: Select) :: _ => true
      case (_: Apply) :: (_: Select) :: _ => true
      case _ => false
    }

  private def isImport(enclosing: List[Tree]): Boolean =
    enclosing match {
      case (_: Import) :: _ => true
      case _ => false
    }

}
