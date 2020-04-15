package scala.meta.internal.metals

import scala.meta.Position

object EmptyResult {
  case object Unchanged extends EmptyResult
  case object NoMatch extends EmptyResult
  def unchanged: Either[EmptyResult, Position] = Left(Unchanged)
  def noMatch: Either[EmptyResult, Position] = Left(NoMatch)
}

sealed trait EmptyResult
