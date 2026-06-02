package scala.meta.internal.parsing

import java.net.URLDecoder
import java.util

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.DocumentLink
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

final class DocumentLinksProvider(
    buffers: Buffers
) {
  def getLinks(
      filePath: AbsolutePath
  ): util.List[DocumentLink] = {
    val urlRegex = raw"https?:\/\/\S+".r
    buffers
      .get(filePath)
      .fold[util.List[DocumentLink]](util.Collections.emptyList()) { content =>
        val linksIterator = for {
          (line, lineNo) <- content.linesIterator.zipWithIndex
          matching <- urlRegex.findAllMatchIn(line)
        } yield {
          scribe.debug(
            s"DocumentLinksProvider: found link in $filePath " +
              s"at line $lineNo, ${matching.start}-${matching.end}: ${matching.matched}"
          )
          val link = new DocumentLink()
          link.setTarget(matching.matched)
          link.setTooltip(URLDecoder.decode(matching.matched, "UTF-8"))
          val startPos = new Position(lineNo, matching.start)
          val endPos = new Position(lineNo, matching.end)
          link.setRange(new Range(startPos, endPos))
          link
        }
        linksIterator.toSeq.asJava
      }

  }
}
