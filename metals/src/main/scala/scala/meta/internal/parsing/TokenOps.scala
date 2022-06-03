package scala.meta.internal.parsing

import scala.meta.given
import scala.meta.inputs.Input
import scala.meta.inputs.Position
import scala.meta.tokens.Token

import difflib.myers.Equalizer

trait TokenOps[T] {
  def pos(token: T): Position
  def input(token: T): Input
  def start(token: T): Int
  def end(token: T): Int
  def isLF(token: T): Boolean
  def show(token: T): String
  def equalizer: Equalizer[T]
}

object TokenOps {

  implicit val scalaTokenOps: TokenOps[Token] = new TokenOps[Token] {
    override def pos(token: Token): Position = token.pos
    override def input(token: Token): Input = token.input
    override def start(token: Token): Int = token.start
    override def end(token: Token): Int = token.end
    override def isLF(token: Token): Boolean = token.isInstanceOf[Token.LF]
    override def show(token: Token): String = token.structure
    override def equalizer: Equalizer[Token] = ScalaTokenEqualizer
  }

  implicit val javaTokenOps: TokenOps[JavaToken] = new TokenOps[JavaToken] {
    override def pos(token: JavaToken): Position = token.pos
    override def input(token: JavaToken): Input = token.input
    override def start(token: JavaToken): Int = token.start
    override def end(token: JavaToken): Int = token.end
    override def isLF(token: JavaToken): Boolean = token.isLF
    override def show(token: JavaToken): String =
      s"[${token.id} (${token.text})"

    override def equalizer: Equalizer[JavaToken] = JavaTokenEqualizer
  }

  object syntax {

    implicit class TokenSyntax[T](val token: T) extends AnyVal {
      def pos(implicit t: TokenOps[T]): Position =
        t.pos(token)
      def input(implicit t: TokenOps[T]): Input =
        t.input(token)
      def start(implicit t: TokenOps[T]): Int = t.start(token)
      def end(implicit t: TokenOps[T]): Int = t.end(token)
      def isLF(implicit t: TokenOps[T]): Boolean = t.isLF(token)
    }
  }

  private object ScalaTokenEqualizer extends Equalizer[Token] {
    override def equals(original: Token, revised: Token): Boolean =
      original.productPrefix == revised.productPrefix &&
        original.pos.text == revised.pos.text

  }

  /**
   * Compare tokens only by their text and token category.
   */
  private object JavaTokenEqualizer extends Equalizer[JavaToken] {
    override def equals(original: JavaToken, revised: JavaToken): Boolean =
      original.id == revised.id &&
        original.text == revised.text

  }
}
