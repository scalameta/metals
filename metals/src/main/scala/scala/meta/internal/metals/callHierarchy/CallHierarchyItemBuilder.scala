package scala.meta.internal.metals.callHierarchy

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.Compilers
import scala.meta.internal.metals.HoverExtParams
import scala.meta.internal.metals.Icons
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.semanticdb.Scala._
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken
import scala.meta.pc.HoverSignature

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
      detail: String,
      data: CallHierarchyItemInfo,
  ): l.CallHierarchyItem = {
    val item = new l.CallHierarchyItem(name, kind, uri, range, range)
    item.setDetail(detail)
    item.setData(data)
    item
  }

  /**
   * Get the display name from the symbol information.
   * For constructor, strip the symbol to the class name,
   * otherwise return symbols's display name.
   */
  private def getName(symbol: String): String = {
    symbol.desc match {
      case Descriptor.Method("<init>", _) =>
        symbol.slice(
          symbol.lastIndexOf("/") + 1,
          symbol.lastIndexOf("#"),
        )
      case _ =>
        symbol.desc.name.value
    }
  }

  private def getDetail(
      signature: String,
      visited: Array[String],
      symbol: String,
  ): String =
    // We have to remove the last visited symbol to see if it exists previously in the list of visited symbols.
    (if (visited.dropRight(1).contains(symbol)) icons.sync + " "
     else "") + signature

  private def buildItem(
      source: AbsolutePath,
      range: l.Range,
      visited: Array[String],
      symbol: String,
      hover: Option[HoverSignature],
      kind: l.SymbolKind,
  ): l.CallHierarchyItem = {
    buildItem(
      getName(symbol),
      kind,
      source.toURI.toString(),
      range,
      getDetail(
        hover.flatMap(_.signature().asScala).getOrElse(""),
        visited,
        symbol,
      ),
      CallHierarchyItemInfo(
        Array(symbol),
        visited,
        symbol.isLocal,
        symbol.isLocal || source.isDependencySource(
          workspace
        ) || buildTargets.inverseSources(source).isEmpty,
      ),
    )

  }

  def build(
      source: AbsolutePath,
      symbol: String,
      range: l.Range,
      visited: Array[String],
      token: CancelToken,
      kind: l.SymbolKind,
  )(implicit ec: ExecutionContext): Future[l.CallHierarchyItem] = {
    for {
      hover <- compilers().hover(
        new HoverExtParams(
          source.toTextDocumentIdentifier,
          null,
          range,
        ),
        token,
      )
    } yield buildItem(
      source,
      range,
      visited,
      symbol,
      hover,
      kind,
    )

  }
}
