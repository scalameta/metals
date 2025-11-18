package scala.meta.internal.metals.formatting

import java.util.concurrent.TimeoutException
import java.{util => ju}

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.util.control.NonFatal

import scala.meta.inputs.Input
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.FormattingProvider
import scala.meta.internal.metals.Messages.MissingScalafmtConf
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.parsing.TokenEditDistance
import scala.meta.internal.parsing.Trees

import org.eclipse.{lsp4j => l}

/**
 * Formats the requested range with Scalafmt.
 *
 * Scalafmt does not natively support range formatting, but we can format the
 * entire file, run Myer's diff at the token level and extract the formatted
 * tokens of the requested range.
 */
class ScalafmtRangeFormatter(
    userConfig: () => UserConfiguration,
    scalafmtProvider: FormattingProvider,
    buffers: Buffers,
    trees: Trees,
) extends RangeFormatter {

  override def contribute(
      params: RangeFormatterParams
  ): Option[List[l.TextEdit]] = {
    if (!userConfig().rangeFormattingProviders.isScalafmt) {
      return None
    }
    val fullFormat: ju.List[l.TextEdit] =
      try
        Await.result(
          scalafmtProvider
            .format(
              params.path,
              params.projectRoot,
              params.token,
              // Users who are selectively formatting ranges are understandably
              // conservative with even adding a `.scalafmt.conf` file.  They
              // can always customize the location with
              // `metals.scalafmtConfigPath`. They can also disable Scalafmt
              // range formatting with `metals.rangeFormattingProviders = []`.
              Some(MissingScalafmtConf.runDefaults),
            ),
          // This may download Scalafmt from the internet so it's OK to give it
          // some time.  However, this should not prompt the user to create a
          // `.scalafmt.conf` file because we force it to create a fallback in
          // `.metals/scalafmt.conf` if it's missing.
          20.seconds,
        )
      catch {
        case _: TimeoutException =>
          scribe.error(
            s"rangeformat: timeout formatting ${params.path} with scalafmt."
          )
          return None
        case NonFatal(e) =>
          scribe.error(
            s"rangeformat: error formatting ${params.path} with scalafmt.",
            e,
          )
          return None
      }

    if (fullFormat.size() != 1) {
      scribe.warn(
        s"rangeformat: Expected 1 text edit for ${params.path}, got ${fullFormat.size()}"
      )
      return None
    }
    val formattedFullFile = fullFormat.get(0).getNewText()
    val original = params.path.toInputFromBuffers(buffers)
    val revised = Input.VirtualFile(original.path, formattedFullFile)
    val d = TokenEditDistance.apply(original, revised, trees) match {
      case Right(d) => d
      case _ => return None
    }

    // Expand the selection to the start and end of the lines so that it fixes
    // indentation even if you're only selecting a word inside the line.
    var startOffset = original.lineToOffset(params.range.getStart.getLine())
    val requestIsAtStartOfLine = params.range.getStart.getCharacter() == 0
    if (requestIsAtStartOfLine) {
      // Include the leading newline so that we insert the correct
      // indentation.  This happens easily if you paste a block of code at 0
      // indent, then we want to fix the indentation.
      startOffset = Math.max(0, startOffset - 1)
    }
    val endOffset = original.lineToOffset(params.range.getEnd.getLine() + 1)
    val originalStart = original.toOffsetPosition(startOffset)
    val originalEnd = original.toOffsetPosition(endOffset)
    for {
      revisedStart <- d.toRevised(startOffset).toOption
      revisedEnd <- d.toRevised(endOffset).toOption
    } yield {
      val slice =
        formattedFullFile.substring(revisedStart.start, revisedEnd.start)
      List(
        new l.TextEdit(
          new l.Range(
            new l.Position(originalStart.startLine, originalStart.startColumn),
            new l.Position(originalEnd.endLine, originalEnd.endColumn),
          ),
          slice,
        )
      )
    }
  }
}
