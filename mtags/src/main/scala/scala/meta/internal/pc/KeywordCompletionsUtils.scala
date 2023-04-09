package scala.meta.internal.pc

import scala.meta.XtensionClassifiable
import scala.meta.tokens.Token // for token.is

object KeywordCompletionsUtils {
  def canBeExtended(leadingReverseTokens: Array[Token]): Boolean = {
    leadingReverseTokens
      .filterNot(t => t.is[Token.Whitespace] || t.is[Token.EOF])
      .take(3)
      .toList match {
      // (class|trait|object) classname ext@@
      case (_: Token.Ident) :: (_: Token.Ident) :: kw :: Nil =>
        if (
          kw.is[Token.KwClass] || kw.is[Token.KwTrait] || kw
            .is[Token.KwObject] || kw.is[Token.KwEnum]
        ) true
        else false
      // ... classname() ext@@
      case (_: Token.Ident) :: (_: Token.RightParen) :: _ => true
      // ... classname[T] ext@@
      case (_: Token.Ident) :: (_: Token.RightBracket) :: _ => true
      case _ => false
    }
  }

  def canDerive(leadingReverseTokens: Array[Token]): Boolean = {
    leadingReverseTokens
      .filterNot(t => t.is[Token.Whitespace] || t.is[Token.EOF])
      .take(4)
      .toList match {
      // ... extends A(,|with) B der@@
      case (_: Token.Ident) :: (_: Token.Ident) :: sep :: _
          if sep.is[Token.KwWith] || sep.is[Token.Comma] =>
        true
      // ... extends A der@@
      case (_: Token.Ident) :: (_: Token.Ident) :: (_: Token.KwExtends) :: _ =>
        true
      case _ => false
    }
  }

  def hasExtend(leadingReverseTokens: Array[Token]): Boolean = {
    leadingReverseTokens
      .filterNot(t => t.is[Token.Whitespace] || t.is[Token.EOF])
      .take(4)
      .toList match {
      // ... extends A wit@@
      case (_: Token.Ident) :: (_: Token.Ident) :: (_: Token.KwExtends) :: _ =>
        true
      case _ => false
    }
  }
}
