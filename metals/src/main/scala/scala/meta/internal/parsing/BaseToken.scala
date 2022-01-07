package scala.meta.internal.parsing

import scala.meta.inputs.Input
import scala.meta.inputs.Position
import scala.meta.tokens.Token

sealed trait BaseToken {
  def pos: Position
  def input: Input
  def structure: String
  def start: Int
  def end: Int
  def isLF: Boolean
}

case class ScalaToken(token: Token) extends BaseToken {

  override def pos: Position = token.pos

  override def input: Input = token.input

  override def structure: String = token.structure

  override def start: Int = token.start

  override def end: Int = token.end

  override def isLF: Boolean = token.is[Token.LF]

}

case class JavaToken(
    id: Int,
    text: String,
    start: Int,
    end: Int,
    input: Input,
    isLF: Boolean = false
) extends BaseToken {

  override def pos: Position = Position.Range(input, start, end)

  override def structure: String = s"[$id]"

}

case class MatchingToken(val original: BaseToken, val revised: BaseToken) {
  override def toString: String =
    s"${original.structure} <-> ${revised.structure}"
}
