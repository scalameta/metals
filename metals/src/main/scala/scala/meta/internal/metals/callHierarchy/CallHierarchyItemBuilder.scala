package scala.meta.internal.metals.callHierarchy

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.Compilers
import scala.meta.internal.metals.HoverExtParams
import scala.meta.internal.metals.Icons
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.{semanticdb => s}
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken

import org.eclipse.{lsp4j => l}

private[callHierarchy] final class CallHierarchyItemBuilder(
    workspace: AbsolutePath,
    icons: Icons,
    compilers: () => Compilers,
    buildTargets: BuildTargets,
) {
  private def buildItem(
      name: String,
      kind: l.SymbolKind,
      uri: String,
      range: l.Range,
      selectionRange: l.Range,
      detail: String,
      data: CallHierarchyItemInfo,
  ): l.CallHierarchyItem = {
    val item = new l.CallHierarchyItem(name, kind, uri, range, selectionRange)
    item.setDetail(detail)
    item.setData(data)
    item
  }

  // When we search the call hierarchy of a class, we also need to search the symbol of the primary constructor
  private def getContructorRelatedSymbol(
      info: s.SymbolInformation
  ): Array[String] =
    info.signature match {
      case s.ClassSignature(_, _, _, declarations) =>
        val constructor = declarations
          .flatMap(
            _.symlinks.find(_.endsWith("`<init>`()."))
          )
        // for case class apply is like a constructor
        val applySymbol = Option.when(info.isCase)(
          info.symbol.replace("#", ".apply().")
        )
        Set(Some(info.symbol), constructor, applySymbol).flatten.toArray
      case _ =>
        Array(info.symbol)
    }

  /**
   * Get the display name from the symbol information.
   * For constructor, strip the symbol to the class name,
   * otherwise return symbols's display name.
   */
  private def getName(info: s.SymbolInformation): String =
    if (info.isConstructor && info.displayName == "<init>")
      info.symbol.slice(
        info.symbol.lastIndexOf("/") + 1,
        info.symbol.lastIndexOf("#"),
      )
    else info.displayName

  // Regular expression to capture the group inside hovers that indicate the symbol signature.
  private val symbolSignatureExtractor =
    """Symbol signature\*\*:\n```scala\n(.*)\n```""".r

  private def getDetail(
      signature: String,
      visited: Array[String],
      symbol: String,
  ): String =
    // We have to remove the last visited symbol to see if it exists previously in the list of visited symbols.
    (if (visited.dropRight(1).contains(symbol)) icons.sync + " "
     else "") + signature

  private def getSignatureFromHover(hover: Option[l.Hover]): Option[String] =
    for {
      hover <- hover
      hoverContent <- hover.getContents().asScala.toOption
      `match` <- symbolSignatureExtractor.findFirstMatchIn(
        hoverContent.getValue
      )
    } yield Option(`match`.group(1)).getOrElse("") // `group` may return null

  private def buildItem(
      symbolInformations: Seq[s.SymbolInformation],
      source: AbsolutePath,
      range: l.Range,
      visited: Array[String],
      symbol: String,
      selectionRange: s.Range,
      hover: Option[l.Hover],
  ): Option[l.CallHierarchyItem] = {
    symbolInformations
      .find(_.symbol == symbol)
      .map(info => {
        buildItem(
          getName(info),
          info.kind.toLsp,
          source.toURI.toString(),
          range,
          selectionRange.toLsp,
          getDetail(
            getSignatureFromHover(hover).getOrElse(""),
            visited,
            symbol,
          ),
          CallHierarchyItemInfo(
            getContructorRelatedSymbol(info),
            visited,
            symbol.isLocal,
            symbol.isLocal || source.isDependencySource(
              workspace
            ) || buildTargets.inverseSources(source).isEmpty,
          ),
        )
      })
  }

  def build(
      source: AbsolutePath,
      doc: TextDocument,
      occurence: SymbolOccurrence,
      range: l.Range,
      visited: Array[String],
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[Option[l.CallHierarchyItem]] = {
    occurence match {
      case SymbolOccurrence(Some(selectionRange), symbol, _) =>
        for {
          hover <- compilers().hover(
            new HoverExtParams(
              source.toTextDocumentIdentifier,
              null,
              selectionRange.toLsp,
            ),
            token,
          )
        } yield buildItem(
          doc.symbols,
          source,
          range,
          visited,
          symbol,
          selectionRange,
          hover,
        )
      case _ => Future.successful(None)
    }
  }
}
