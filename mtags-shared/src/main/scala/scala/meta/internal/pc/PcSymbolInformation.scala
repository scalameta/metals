package scala.meta.internal.pc

import java.{util => ju}

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.pc
import scala.meta.pc.PcSymbolProperty
import scala.meta.pc.{PcSymbolInformation => IPcSymbolInformation}

case class PcSymbolInformation(
    symbol: String,
    kind: pc.PcSymbolKind,
    parents: List[String],
    dealiasedSymbol: String,
    classOwner: Option[String],
    overriddenSymbols: List[String],
    properties: List[PcSymbolProperty]
) {
  def asJava: PcSymbolInformationJava =
    PcSymbolInformationJava(
      symbol,
      kind,
      parents.asJava,
      dealiasedSymbol,
      classOwner.getOrElse(""),
      overriddenSymbols.asJava,
      properties.asJava
    )
}

case class PcSymbolInformationJava(
    symbol: String,
    kind: pc.PcSymbolKind,
    parents: ju.List[String],
    dealiasedSymbol: String,
    classOwner: String,
    overriddenSymbols: ju.List[String],
    properties: ju.List[PcSymbolProperty]
) extends IPcSymbolInformation

object PcSymbolInformation {
  def from(info: IPcSymbolInformation): PcSymbolInformation =
    PcSymbolInformation(
      info.symbol(),
      info.kind(),
      info.parents().asScala.toList,
      info.dealiasedSymbol(),
      if (info.classOwner().nonEmpty) Some(info.classOwner())
      else None,
      info.overriddenSymbols().asScala.toList,
      info.properties().asScala.toList
    )
}
