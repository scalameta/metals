package scala.meta.internal.metals

import scala.annotation.tailrec
import scala.meta._
import difflib._
import difflib.myers.Equalizer
import scala.meta.Token
import scala.meta.internal.mtags.MtagsEnrichments._

sealed trait EmptyResult
object EmptyResult {
  case object Unchanged extends EmptyResult
  case object NoMatch extends EmptyResult
  def unchanged: Either[EmptyResult, Position] = Left(Unchanged)
  def noMatch: Either[EmptyResult, Position] = Left(NoMatch)
}

/** A pair of tokens that align with each other across two different files */
case class MatchingToken(original: Token, revised: Token) {
  override def toString: String =
    s"${original.structure} <-> ${revised.structure}"
}

/** Helper to map between position between two similar strings. */
final class TokenEditDistance private (
    matching: Array[MatchingToken],
    empty: Option[EmptyResult]
) {
  private val isUnchanged: Boolean =
    empty.contains(EmptyResult.Unchanged)
  private val isNoMatch: Boolean =
    matching.isEmpty || empty.contains(EmptyResult.NoMatch)
  override def toString: String = {
    val value =
      if (isUnchanged) "unchanged"
      else if (isNoMatch) "no-match"
      else s"${matching.length} tokens"
    s"TokenEditDistance($value)"
  }

  private def originalInput: Input =
    if (empty.isDefined) Input.None
    else matching(0).original.input

  private def revisedInput: Input =
    if (empty.isDefined) Input.None
    else matching(0).revised.input

  def toRevised(
      originalLine: Int,
      originalColumn: Int
  ): Either[EmptyResult, Position] = {
    if (isUnchanged) EmptyResult.unchanged
    else if (isNoMatch) EmptyResult.noMatch
    else toRevised(originalInput.toOffset(originalLine, originalColumn))
  }

  /** Convert from offset in original string to offset in revised string */
  def toRevised(originalOffset: Int): Either[EmptyResult, Position] = {
    if (isUnchanged) EmptyResult.unchanged
    else if (isNoMatch) EmptyResult.noMatch
    else {
      BinarySearch
        .array[MatchingToken](
          matching,
          mt => compare(mt.original.pos, originalOffset)
        )
        .fold(EmptyResult.noMatch)(m => Right(m.revised.pos))
    }
  }

  def toOriginal(
      revisedLine: Int,
      revisedColumn: Int
  ): Either[EmptyResult, Position] = {
    if (isUnchanged) EmptyResult.unchanged
    else if (isNoMatch) EmptyResult.noMatch
    else toOriginal(revisedInput.toOffset(revisedLine, revisedColumn))
  }

  /** Convert from offset in revised string to offset in original string */
  def toOriginal(revisedOffset: Int): Either[EmptyResult, Position] = {
    if (isUnchanged) EmptyResult.unchanged
    else if (isNoMatch) EmptyResult.noMatch
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

  implicit class XtensionPositionRangeLSP(pos: Position) {
    def contains(offset: Int): Boolean =
      if (pos.start == pos.end) pos.end == offset
      else {
        pos.start <= offset &&
        pos.end > offset
      }
  }

}

object TokenEditDistance {

  lazy val unchanged: TokenEditDistance =
    new TokenEditDistance(Array.empty, empty = Some(EmptyResult.Unchanged))
  lazy val noMatch: TokenEditDistance =
    new TokenEditDistance(Array.empty, empty = Some(EmptyResult.NoMatch))

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
    new TokenEditDistance(buffer.result(), empty = None)
  }

  def apply(
      originalInput: Input.VirtualFile,
      revisedInput: Input.VirtualFile
  ): TokenEditDistance = {
    val isScala =
      originalInput.path.endsWith(".scala") &&
        revisedInput.path.endsWith(".scala")
    if (!isScala) {
      // Ignore non-scala Files.
      unchanged
    } else {
      val result = for {
        revised <- revisedInput.tokenize.toOption
        original <- {
          if (originalInput == revisedInput) Some(revised)
          else originalInput.tokenize.toOption
        }
      } yield {
        if (revised == original) unchanged
        else TokenEditDistance(original, revised)
      }
      result.getOrElse(noMatch)
    }
  }

  /** Compare tokens only by their text and token category. */
  private object TokenEqualizer extends Equalizer[Token] {
    override def equals(original: Token, revised: Token): Boolean =
      original.productPrefix == revised.productPrefix &&
        original.pos.text == revised.pos.text
  }

}
