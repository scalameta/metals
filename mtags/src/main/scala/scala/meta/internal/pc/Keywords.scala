package scala.meta.internal.pc

import org.eclipse.{lsp4j => l}

import scala.collection.mutable

trait Keywords { this: MetalsGlobal =>

  private[this] def blockDefinitions: List[String] =
    List("def", "val", "var", "lazy val", "type")

  private[this] def toplevelDefinitions: List[String] =
    List("case class", "class", "trait", "object")

  private[this] def definitionModifiers =
    List("final", "private", "protected", "sealed trait",
      "sealed abstract class", "abstract")

  // Categories

  private[this] def expressionKeywords: List[String] =
    List("super", "this", "if", "for", "while", "do", "true", "false", "new",
      "null", "try", "throw")

  private[this] def blockKeywords: List[String] =
    List("implicit") ++ expressionKeywords ++ blockDefinitions

  private[this] def templateKeywords: List[String] =
    List("implicit", "final") ++ expressionKeywords ++ blockDefinitions ++ toplevelDefinitions ++ definitionModifiers

  private[this] def toplevelKeywords: List[String] =
    List("package", "import") ++ toplevelDefinitions ++ definitionModifiers

  private[this] def defKeywords: List[String] = List("return")

  def keywords(
      pos: Position,
      editRange: l.Range,
      latestEnclosing: List[Tree]
  ): List[Member] = {
    getIdentifierName(latestEnclosing, pos).fold(List[Member]()) { name =>
      val keywords = mutable.Set.empty[String]

      if (isExpression(latestEnclosing)) keywords ++= expressionKeywords

      if (isStatement(latestEnclosing)) keywords ++= templateKeywords

      if (isToplevel(latestEnclosing)) keywords ++= toplevelKeywords

      if (isBlock(latestEnclosing)) keywords ++= blockKeywords

      if (isMethodBody(latestEnclosing)) keywords ++= defKeywords

      createMembers(keywords.toList, editRange, name)
    }
  }

  private[this] def createMembers(
      keywords: List[String],
      editRange: l.Range,
      name: Name
  ): List[Member] = {
    keywords.collect {
      case kw if kw.startsWith(name.toString.stripSuffix(CURSOR)) =>
        mkTextEditMember(kw, editRange)
    }
  }

  private[this] def getIdentifierName(
      enclosing: List[Tree],
      pos: Position
  ): Option[Name] = {
    enclosing match {
      case Ident(name) :: _ => Some(name)
      case PackageDef(ref, _) :: _ if !ref.pos.includes(pos) =>
        var i = pos.point
        val chars = pos.source.content

        while (i >= 0 && !chars(i).isWhitespace) {
          i -= 1
        }

        if (i < 0) None
        else Some(TermName(new String(chars, i + 1, pos.point - i - 1)))
      case _ => None
    }
  }

  private[this] def mkTextEditMember(
      keyword: String,
      range: l.Range
  ): TextEditMember = {
    val newText =
      if (needsTrailingWhitespace(keyword)) keyword + " " else keyword

    new TextEditMember(
      keyword,
      new l.TextEdit(range, newText),
      NoSymbol.newErrorSymbol(TermName(keyword)).setInfo(NoType),
      label = Some(keyword)
    )
  }

  private[this] def isExpression(enclosing: List[Tree]): Boolean =
    enclosing match {
      case Ident(_) :: Template(_, _, _) :: _ => true
      case Ident(_) :: ValOrDefDef(_, _, _, _) :: _ => true
      case Ident(_) :: Apply(_, _) :: _ => true
      case _ => false
    }

  private[this] def isStatement(enclosing: List[Tree]): Boolean =
    enclosing match {
      case Ident(_) :: Template(_, _, _) :: _ => true
      case Ident(_) :: ValOrDefDef(_, _, _, _) :: _ => true
      case _ => false
    }

  private[this] def isToplevel(enclosing: List[Tree]): Boolean =
    enclosing match {
      case PackageDef(_, _) :: _ => true
      case _ => false
    }

  private[this] def isMethodBody(enclosing: List[Tree]): Boolean =
    enclosing match {
      case Ident(_) :: DefDef(_, _, _, _, _, _) :: _ => true
      case _ => false
    }

  private[this] def isBlock(enclosing: List[Tree]): Boolean =
    enclosing match {
      case Ident(_) :: Block(_, _) :: _ => true
      case _ => false
    }

  private[this] def needsTrailingWhitespace(keyword: String): Boolean =
    !Set("this", "super", "true", "false", "null").contains(keyword)
}
