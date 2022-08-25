package scala.meta.internal.metals.callHierarchy

import scala.meta.internal.{semanticdb => s}
import scala.meta.io.AbsolutePath
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.semanticdb.Scala._

import org.eclipse.{lsp4j => l}
import scala.meta.pc.CancelToken
import scala.concurrent.ExecutionContext
import scala.meta.internal.metals.HoverExtParams
import scala.concurrent.Future
import scala.meta.internal.metals.Icons
import scala.meta.internal.metals.Compilers
import scala.meta.internal.metals.BuildTargets

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

  private def getClassContructorSymbol(
      info: s.SymbolInformation
  ): Option[String] =
    if (info.isClass)
      info.signature
        .asInstanceOf[s.ClassSignature]
        .declarations
        .flatMap(
          _.symlinks.find(_.endsWith("`<init>`()."))
        )
    else None

  /** Get the name of the class of a constructor */
  private def getName(info: s.SymbolInformation): String =
    if (info.isConstructor && info.displayName == "<init>")
      info.symbol.slice(
        info.symbol.lastIndexOf("/") + 1,
        info.symbol.lastIndexOf("#"),
      )
    else info.displayName

  private def getDetail(
      signature: String,
      visited: Array[String],
      symbol: String,
  ): String =
    (if (visited.dropRight(1).contains(symbol)) icons.sync + " "
     else "") + signature

  private def getSignatureFromHover(hover: Option[l.Hover]): Option[String] =
    (for {
      hover <- hover
      hoverContent <- hover.getContents().asScala.toOption
      `match` <- """Symbol signature\*\*:\n```scala\n(.*)\n```""".r
        .findFirstMatchIn(hoverContent.getValue)
    } yield `match`.group(1))

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
          info.kind.toLSP,
          source.toURI.toString(),
          range,
          selectionRange.toLSP,
          getDetail(
            getSignatureFromHover(hover).getOrElse(""),
            visited,
            symbol,
          ),
          CallHierarchyItemInfo(
            // When we search the call hierarchy of a class, we also need to search the symbol of the primary constructor
            (Set(symbol) ++ getClassContructorSymbol(info)).toArray,
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
              selectionRange.toLSP,
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
