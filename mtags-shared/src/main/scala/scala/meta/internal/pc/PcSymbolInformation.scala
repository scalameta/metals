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
    alternativeSymbols: List[String],
    properties: List[PcSymbolProperty],
    recursiveParents: List[String],
    annotations: List[String],
    memberDefsAnnotations: List[String],
    typeParameters: List[String] = Nil
) {
  def asJava: PcSymbolInformationJava =
    PcSymbolInformationJava(
      symbol,
      kind,
      parents.asJava,
      dealiasedSymbol,
      classOwner.getOrElse(""),
      overriddenSymbols.asJava,
      alternativeSymbols.asJava,
      properties.asJava,
      recursiveParents.asJava,
      annotations.asJava,
      memberDefsAnnotations.asJava,
      typeParameters.asJava
    )
}

case class PcSymbolInformationJava(
    symbol: String,
    kind: pc.PcSymbolKind,
    parents: ju.List[String],
    dealiasedSymbol: String,
    classOwner: String,
    overriddenSymbols: ju.List[String],
    alternativeSymbols: ju.List[String],
    properties: ju.List[PcSymbolProperty],
    override val recursiveParents: ju.List[String],
    override val annotations: ju.List[String],
    override val memberDefsAnnotations: ju.List[String],
    override val typeParameters: ju.List[String]
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
      info.alternativeSymbols().asScala.toList,
      info.properties().asScala.toList,
      info.recursiveParents().asScala.toList,
      info.annotations().asScala.toList,
      info.memberDefsAnnotations().asScala.toList,
      info.typeParameters().asScala.toList
    )
}
