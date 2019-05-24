package scala.meta.internal.pc

import org.eclipse.{lsp4j => l}
import scala.tools.nsc.reporters.StoreReporter

trait Keywords { this: MetalsGlobal =>

  def keywords(
      pos: Position,
      editRange: l.Range,
      latestEnclosing: List[Tree]
  ): List[Member] = {
    getIdentifierName(latestEnclosing, pos) match {
      case None => Nil
      case Some(name) =>
        val isExpression = this.isExpression(latestEnclosing)
        val isBlock = this.isBlock(latestEnclosing)
        val isDefinition = this.isDefinition(latestEnclosing, name, pos)
        val isMethodBody = this.isMethodBody(latestEnclosing)
        val isTemplate = this.isTemplate(latestEnclosing)
        val isPackage = this.isPackage(latestEnclosing)
        Keyword.all.collect {
          case kw
              if kw.matchesPosition(
                name,
                isExpression = isExpression,
                isBlock = isBlock,
                isDefinition = isDefinition,
                isMethodBody = isMethodBody,
                isTemplate = isTemplate,
                isPackage = isPackage
              ) =>
            mkTextEditMember(kw, editRange)
        }
    }
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
      case _ => false
    }

  private def isTemplate(enclosing: List[Tree]): Boolean =
    enclosing match {
      case Ident(_) :: Template(_, _, _) :: _ => true
      case Ident(_) :: ValOrDefDef(_, _, _, _) :: _ => true
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
        val isExpectedStartOfDefinition = reporter match {
          case s: StoreReporter =>
            s.infos.exists { info =>
              info.pos.focus == point &&
              info.msg == "expected start of definition"
            }
          case _ =>
            false
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

}
