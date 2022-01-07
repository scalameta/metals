package scala.meta.internal.parsing

import java.util.logging.Logger

import scala.annotation.tailrec
import scala.collection.compat.immutable.ArraySeq

import scala.meta.Input
import scala.meta.Position
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.{semanticdb => s}

import difflib._
import difflib.myers.Equalizer
import org.eclipse.{lsp4j => l}

/**
 * Helper to map between position between two similar strings.
 */
final class TokenEditDistance private (
    matching: Array[MatchingToken],
    empty: Option[EmptyResult]
) {
  val logger: Logger = Logger.getLogger(classOf[TokenEditDistance].getName)
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

  /**
   * Converts a range position in the original document to a range position in the revised document.
   *
   * This method behaves differently from the other `toRevised` in a few ways:
   * - it should only return `None` in the case when the sources don't tokenize.
   *   When the original token is removed in the revised document, we find instead the
   *   nearest token in the original document instead.
   */
  def toRevised(range: l.Range): Option[l.Range] = {
    if (isUnchanged) Some(range)
    else if (isNoMatch) None
    else {
      val pos = range.toMeta(originalInput)
      val matchingTokens = matching.lift

      // Perform two binary searches to find the revised start/end positions.
      // NOTE. I tried abstracting over the two searches since they are so similar
      // but it resulted in less maintainable code.

      var startFallback = false
      val startMatch = BinarySearch.array(
        matching,
        (mt: MatchingToken, i) => {
          val result = compare(mt.original.pos, pos.start)
          result match {
            case BinarySearch.Smaller =>
              matchingTokens(i + 1) match {
                case Some(next) =>
                  compare(next.original.pos, pos.start) match {
                    case BinarySearch.Greater =>
                      startFallback = true
                      // The original token is not available in the revised document
                      // so we use the nearest token instead.
                      BinarySearch.Equal
                    case _ =>
                      result
                  }
                case None =>
                  startFallback = true
                  BinarySearch.Equal
              }
            case _ =>
              result
          }
        }
      )

      var endFallback = false
      val endMatch = BinarySearch.array(
        matching,
        (mt: MatchingToken, i) => {
          // End offsets are non-inclusive so we decrement by one.
          val offset = math.max(pos.start, pos.end - 1)
          val result = compare(mt.original.pos, offset)
          result match {
            case BinarySearch.Greater =>
              matchingTokens(i - 1) match {
                case Some(next) =>
                  compare(next.original.pos, offset) match {
                    case BinarySearch.Smaller =>
                      endFallback = true
                      BinarySearch.Equal
                    case _ =>
                      result
                  }
                case None =>
                  endFallback = true
                  BinarySearch.Equal
              }
            case _ =>
              result
          }
        }
      )

      (startMatch, endMatch) match {
        case (Some(start), Some(end)) =>
          val revised =
            if (startFallback && endFallback) {
              val offset = end.revised.start
              Position.Range(revisedInput, offset - 1, offset)
            } else if (start.revised == end.revised) {
              start.revised.pos
            } else {
              val endOffset = end.revised match {
                case t if t.isLF => t.start
                case t => t.end
              }
              Position.Range(revisedInput, start.revised.start, endOffset)
            }
          Some(revised.toLSP)
        case (start, end) =>
          logger.warning(s"stale range: $start $end")
          None
      }
    }
  }

  def toRevised(pos: l.Position): Either[EmptyResult, Position] = {
    toRevised(pos.getLine, pos.getCharacter)
  }

  def toRevisedStrict(range: s.Range): Option[s.Range] = {
    if (isUnchanged) Some(range)
    else {
      (
        toRevised(range.startLine, range.startCharacter),
        toRevised(range.endLine, range.endCharacter)
      ) match {
        case (Right(start), Right(end)) =>
          Some(
            s.Range(
              start.startLine,
              start.startColumn,
              end.startLine,
              end.startColumn
            )
          )
        case _ => None
      }
    }
  }

  def toRevised(
      originalLine: Int,
      originalColumn: Int
  ): Either[EmptyResult, Position] = {
    if (isUnchanged) EmptyResult.unchanged
    else if (isNoMatch) EmptyResult.noMatch
    else toRevised(originalInput.toOffset(originalLine, originalColumn))
  }

  /**
   * Convert from offset in original string to offset in revised string
   */
  def toRevised(originalOffset: Int): Either[EmptyResult, Position] = {
    if (isUnchanged) EmptyResult.unchanged
    else if (isNoMatch) EmptyResult.noMatch
    else {
      BinarySearch
        .array[MatchingToken](
          matching,
          (mt, _) => compare(mt.original.pos, originalOffset)
        )
        .fold(EmptyResult.noMatch)(m => Right(m.revised.pos))
    }
  }

  def toOriginalStrict(range: s.Range): Option[s.Range] = {
    if (isUnchanged) Some(range)
    else {
      (
        toOriginal(range.startLine, range.startCharacter),
        toOriginal(range.endLine, range.endCharacter)
      ) match {
        case (Right(start), Right(end)) =>
          Some(
            s.Range(
              start.startLine,
              start.startColumn,
              end.endLine,
              end.endColumn
            )
          )
        case _ => None
      }
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

  /**
   * Convert from offset in revised string to offset in original string
   */
  def toOriginal(revisedOffset: Int): Either[EmptyResult, Position] = {
    if (isUnchanged) EmptyResult.unchanged
    else if (isNoMatch) EmptyResult.noMatch
    else {
      BinarySearch
        .array[MatchingToken](
          matching,
          (mt, _) => compare(mt.revised.pos, revisedOffset)
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
  private def fromTokens[A <: BaseToken](
      original: Seq[A],
      revised: Seq[A],
      equalizer: Equalizer[A]
  ): TokenEditDistance = {
    val buffer = Array.newBuilder[MatchingToken]
    buffer.sizeHint(math.max(original.size, revised.size))
    @tailrec
    def loop(
        i: Int,
        j: Int,
        ds: List[Delta[A]]
    ): Unit = {
      val isDone: Boolean =
        i >= original.size ||
          j >= revised.size
      if (isDone) ()
      else {
        val o = original(i)
        val r = revised(j)
        if (equalizer.equals(o, r)) {
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
      import scala.meta.internal.jdk.CollectionConverters._
      DiffUtils
        .diff(original.asJava, revised.asJava, equalizer)
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
      revisedInput: Input.VirtualFile,
      trees: Trees,
      doNothingWhenUnchanged: Boolean = true
  ): TokenEditDistance = {
    val isScala =
      originalInput.path.isScalaFilename &&
        revisedInput.path.isScalaFilename
    val isJava =
      originalInput.path.isJavaFilename &&
        revisedInput.path.isJavaFilename

    if (!isScala && !isJava) {
      // Ignore non-scala/java Files.
      unchanged
    } else if (originalInput.value.isEmpty() || revisedInput.value.isEmpty()) {
      noMatch
    } else if (doNothingWhenUnchanged && originalInput == revisedInput) {
      unchanged
    } else if (isJava) {
      val result = for {
        revised <- JavaTokens.tokenize(revisedInput)
        original <- JavaTokens.tokenize(originalInput)
      } yield {
        TokenEditDistance.fromTokens(original, revised, JavaTokenEqualizer)
      }
      result.getOrElse(noMatch)
    } else {
      val result = for {
        revised <- trees.tokenized(revisedInput).toOption
        original <- trees.tokenized(originalInput).toOption
      } yield {
        TokenEditDistance.fromTokens(
          ArraySeq(original.tokens: _*).map(ScalaToken.apply),
          ArraySeq(revised.tokens: _*).map(ScalaToken.apply),
          ScalaTokenEqualizer
        )
      }
      result.getOrElse(noMatch)
    }
  }

  /**
   * Compare tokens only by their text and token category.
   */
  private object ScalaTokenEqualizer extends Equalizer[ScalaToken] {
    override def equals(original: ScalaToken, revised: ScalaToken): Boolean =
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
