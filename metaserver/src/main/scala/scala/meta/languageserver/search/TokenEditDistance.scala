package scala.meta.languageserver.search

import scala.meta._
import scala.meta.languageserver.ScalametaEnrichments._
import com.typesafe.scalalogging.LazyLogging
import difflib._
import difflib.myers.Equalizer

/** Helper to map between position between two similar strings. */
class TokenEditDistance private (matching: Array[MatchingToken]) {

  /** Convert from offset in original string to offset in revised string */
  def toRevisedPosition(originalOffset: Int): Option[Token] = {
    BinarySearch
      .array[MatchingToken](
        matching,
        mt => compare(mt.original.pos, originalOffset)
      )
      .map(_.revised)
  }

  /** Convert from offset in revised string to offset in original string */
  def toOriginalOffset(revisedOffset: Int): Option[Token] = {
    BinarySearch
      .array[MatchingToken](
        matching,
        mt => compare(mt.revised.pos, revisedOffset)
      )
      .map(_.original)
  }

  private def compare(
      pos: Position,
      offset: Int
  ): BinarySearch.ComparisonResult =
    if (pos.contains(offset)) BinarySearch.Equal
    else if (pos.end <= offset) BinarySearch.Smaller
    else BinarySearch.Greater

}

object TokenEditDistance extends LazyLogging {

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
    buffer.sizeHint(original.length)
    def loop(
        i: Int,
        j: Int,
        ds: List[Delta[Token]]
    ): Unit = {
      def increment(): Unit = loop(i + 1, j + 1, ds)
      val isDone: Boolean =
        i >= original.length ||
          j >= revised.length
      if (isDone) ()
      else {
        val o = original(i)
        val r = revised(j)
        if (TokenEqualizer.equals(o, r)) {
          buffer += MatchingToken(o, r)
          increment()
        } else {
          ds match {
            case Nil => increment()
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
