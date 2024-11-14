package scala.meta.internal.metals

import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken

import com.google.gson.JsonArray
import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.InlayHintLabelPart
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.{lsp4j => l}

final class InlayHintResolveProvider(
    definitionProvider: DefinitionProvider,
    compilers: Compilers,
)(implicit ec: ExecutionContextExecutorService, rc: ReportContext) {
  def resolve(
      inlayHint: InlayHint,
      path: AbsolutePath,
      token: CancelToken,
  ): Future[InlayHint] = {
    scala.util.Try {
      val data = inlayHint.getData().asInstanceOf[JsonArray]
      getLabelParts(inlayHint).zip(parseData(data))
    }.toEither match {
      case Right(labelParts) =>
        resolve(inlayHint, labelParts, path, token)
      case Left(error) =>
        scribe.warn(s"Failed to resolve inlay hint: $error")
        rc.unsanitized.create(report(inlayHint, path, error), ifVerbose = true)
        Future.successful(inlayHint)
    }
  }

  private def resolve(
      inlayHint: InlayHint,
      labelParts: List[(InlayHintLabelPart, Either[String, l.Position])],
      path: AbsolutePath,
      token: CancelToken,
  ): Future[InlayHint] = {
    val resolveLabelParts = labelParts.map {
      case (labelPart, Left(symbol)) =>
        getSymbol(symbol, path)
          .map(loc => resolveLabelPart(labelPart, loc, token))
          .getOrElse(Future.successful(labelPart))
      case (labelPart, Right(pos)) =>
        val location =
          new l.Location(path.toURI.toString(), new l.Range(pos, pos))
        resolveLabelPart(labelPart, location, token)
    }
    Future.sequence(resolveLabelParts).map { labelParts =>
      inlayHint.setLabel(labelParts.asJava)
      inlayHint
    }
  }

  private def resolveLabelPart(
      labelPart: InlayHintLabelPart,
      location: l.Location,
      token: CancelToken,
  ): Future[InlayHintLabelPart] = {
    labelPart.setCommand(ServerCommands.GotoPosition.toLsp(location))
    val hoverParams = HoverExtParams(
      new TextDocumentIdentifier(location.getUri()),
      location.getRange().getStart(),
    )
    compilers.hover(hoverParams, token).map { hover =>
      hover
        .foreach(h =>
          labelPart.setTooltip(
            h.toLsp().getContents().getRight()
          )
        )
      labelPart
    }
  }

  private def getLabelParts(inlayHint: InlayHint) =
    inlayHint.getLabel().asScala match {
      case Left(text) =>
        val label = new InlayHintLabelPart()
        label.setValue(text)
        List(label)
      case Right(labelParts) => labelParts.asScala.toList
    }

  val symbol = new JsonParser.Of[String]
  val range = new JsonParser.Of[l.Position]

  private def parseData(
      data: JsonArray
  ): List[Either[String, l.Position]] = {
    data.asScala.map {
      case range.Jsonized(data) => Right(data)
      case symbol.Jsonized(data) => Left(data)
      case _ => Left("")
    }.toList
  }

  private def getSymbol(symbol: String, path: AbsolutePath) = {
    definitionProvider
      .fromSymbol(symbol, Some(path))
      .asScala
      .headOption
  }

  private def report(
      inlayHint: InlayHint,
      path: AbsolutePath,
      error: Throwable,
  ) = {
    val pos = inlayHint.getPosition()
    Report(
      "inlayHint-resolve",
      s"""|pos: $pos
          |
          |inlayHint: $inlayHint
          |""".stripMargin,
      s"failed to resolve inlayHint in $path",
      id = Some(s"$path::${pos.getLine()}:${pos.getCharacter()}"),
      path = Some(path.toURI),
      error = Some(error),
    )
  }

}
