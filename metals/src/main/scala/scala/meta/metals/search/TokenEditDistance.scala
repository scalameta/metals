package scala.meta.metals.search

import scala.annotation.tailrec
import scala.meta._
import scala.meta.metals.ScalametaEnrichments._
import scala.meta.metals.{index => i}
import scala.meta.metals.MetalsLogger
import difflib._
import difflib.myers.Equalizer
import org.langmeta.languageserver.InputEnrichments._

sealed trait EmptyResult
object EmptyResult {
  case object Unchanged extends EmptyResult
  case object NoMatch extends EmptyResult
  def unchanged: Either[EmptyResult, Position] = Left(Unchanged)
  def noMatch: Either[EmptyResult, Position] = Left(NoMatch)
}

/** Helper to map between position between two similar strings. */
final class TokenEditDistance private (matching: Array[MatchingToken]) {
  private val isEmpty: Boolean = matching.length == 0

  private val ThisUri: String = originalInput match {
    case Input.VirtualFile(uri, _) => uri
    case _ => originalInput.syntax
  }
  private def originalInput: Input =
    if (isEmpty) Input.None
    else matching(0).original.input

  private def revisedInput: Input =
    if (isEmpty) Input.None
    else matching(0).revised.input

  def toRevised(
      originalLine: Int,
      originalColumn: Int
  ): Either[EmptyResult, Position] = {
    toRevised(originalInput.toOffset(originalLine, originalColumn))
  }

  /** Convert from offset in original string to offset in revised string */
  def toRevised(originalOffset: Int): Either[EmptyResult, Position] = {
    if (isEmpty) EmptyResult.unchanged
    else {
      BinarySearch
        .array[MatchingToken](
          matching,
          mt => compare(mt.original.pos, originalOffset)
        )
        .fold(EmptyResult.noMatch)(m => Right(m.revised.pos))
    }
  }

  private object RevisedRange {
    def unapply(range: i.Range): Option[i.Range] =
      toRevised(range.startLine, range.startColumn) match {
        case Left(EmptyResult.NoMatch) => None
        case Left(EmptyResult.Unchanged) => Some(range)
        case Right(newPos) => Some(newPos.toIndexRange)
      }
  }

  /** Convert the reference positions to match the revised input. */
  def toRevisedReferences(data: i.SymbolData): i.SymbolData = {
    val referencesAdjusted = data.references.get(ThisUri) match {
      case Some(i.Ranges(ranges)) =>
        val newRanges = ranges.collect { case RevisedRange(range) => range }
        val newData = data.copy(
          references = data.references + (ThisUri -> i.Ranges(newRanges))
        )
        newData
      case _ => data
    }
    toRevisedDefinition(referencesAdjusted)
  }

  /** Convert the definition position to match the revised input. */
  def toRevisedDefinition(data: i.SymbolData): i.SymbolData = {
    data.definition match {
      case Some(i.Position(ThisUri, Some(range))) =>
        toRevised(range.startLine, range.startColumn) match {
          case Left(EmptyResult.NoMatch) => data.copy(definition = None)
          case Left(EmptyResult.Unchanged) => data
          case Right(newPos) =>
            val newData = data.copy(
              definition = Some(
                i.Position(
                  ThisUri,
                  Some(newPos.toIndexRange)
                )
              )
            )
            newData
        }
      case _ => data
    }
  }

  def toOriginal(
      revisedLine: Int,
      revisedColumn: Int
  ): Either[EmptyResult, Position] = {
    toOriginal(revisedInput.toOffset(revisedLine, revisedColumn))
  }

  /** Convert from offset in revised string to offset in original string */
  def toOriginal(revisedOffset: Int): Either[EmptyResult, Position] = {
    if (isEmpty) EmptyResult.unchanged
    else {
      BinarySearch
        .array[MatchingToken](
          matching,
          mt => compare(mt.revised.pos, revisedOffset)
        )
        .fold(EmptyResult.noMatch)(m => Right(m.original.pos))
    }
  }

  private def compare(
      pos: Position,
      offset: Int
  ): BinarySearch.ComparisonResult =
    if (pos.contains(offset)) BinarySearch.Equal
    else if (pos.end <= offset) BinarySearch.Smaller
    else BinarySearch.Greater

}

object TokenEditDistance extends MetalsLogger {

  lazy val empty: TokenEditDistance = new TokenEditDistance(Array.empty)

  /**
   * Build utility to map offsets between two slightly different strings.
   *
   * @param original The original snapshot of a string, for example the latest
   *                 semanticdb snapshot.
   * @param revised The current snapshot of a string, for example open buffer
   *                in an editor.
   */
  def apply(original: Tokens, revised: Tokens): TokenEditDistance = {
    val buffer = Array.newBuilder[MatchingToken]
    buffer.sizeHint(math.max(original.length, revised.length))
    @tailrec
    def loop(
        i: Int,
        j: Int,
        ds: List[Delta[Token]]
    ): Unit = {
      val isDone: Boolean =
        i >= original.length ||
          j >= revised.length
      if (isDone) ()
      else {
        val o = original(i)
        val r = revised(j)
        if (TokenEqualizer.equals(o, r)) {
          buffer += MatchingToken(o, r)
          loop(i + 1, j + 1, ds)
        } else {
          ds match {
            case Nil =>
              loop(i + 1, j + 1, ds)
            case delta :: tail =>
              loop(
                i + delta.getOriginal.size(),
                j + delta.getRevised.size(),
                tail
              )
          }
        }
      }
    }
    val deltas = {
      import scala.collection.JavaConverters._
      DiffUtils
        .diff(original.asJava, revised.asJava, TokenEqualizer)
        .getDeltas
        .iterator()
        .asScala
        .toList
    }
    loop(0, 0, deltas)
    new TokenEditDistance(buffer.result())
  }

  def apply(
      originalInput: Input,
      revisedInput: Input
  ): Option[TokenEditDistance] = {
    for {
      revised <- revisedInput.tokenize.toOption
      original <- {
        if (originalInput == revisedInput) Some(revised)
        else originalInput.tokenize.toOption
      }
    } yield apply(original, revised)
  }

  def apply(original: String, revised: String): Option[TokenEditDistance] =
    apply(Input.String(original), Input.String(revised))

  /** Compare tokens only by their text and token category. */
  private object TokenEqualizer extends Equalizer[Token] {
    override def equals(original: Token, revised: Token): Boolean =
      original.productPrefix == revised.productPrefix &&
        original.pos.text == revised.pos.text
  }

}
