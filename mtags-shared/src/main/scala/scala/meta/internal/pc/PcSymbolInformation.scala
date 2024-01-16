package scala.meta.internal.pc

import java.{util => ju}

import scala.util.Try

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.pc.{PcSymbolInformation => IPcSymbolInformation}

case class PcSymbolInformation(
    symbol: String,
    kind: PcSymbolKind.PcSymbolKind,
    parents: List[String],
    dealisedSymbol: String,
    classOwner: Option[String],
    overridden: List[String],
    properties: List[PcSymbolProperty.PcSymbolProperty]
) extends IPcSymbolInformation {

  def overriddenList(): ju.List[String] = overridden.asJava
  def classOwnerString(): String = classOwner.getOrElse("")
  def kindString(): String = kind.toString()
  def parentsList(): ju.List[String] = parents.asJava
  def propertiesList(): ju.List[String] = properties.map(_.toString()).asJava
}

object PcSymbolInformation {
  def from(info: IPcSymbolInformation): PcSymbolInformation =
    PcSymbolInformation(
      info.symbol(),
      Try(PcSymbolKind.withName(info.kindString()))
        .getOrElse(PcSymbolKind.UNKNOWN_KIND),
      info.parentsList().asScala.toList,
      info.dealisedSymbol(),
      if (info.classOwnerString().nonEmpty) Some(info.classOwnerString())
      else None,
      info.overriddenList().asScala.toList,
      info
        .propertiesList()
        .asScala
        .toList
        .flatMap(name => Try(PcSymbolProperty.withName(name)).toOption)
    )
}

object PcSymbolKind extends Enumeration {
  type PcSymbolKind = Value
  val UNKNOWN_KIND: Value = Value(0)
  val METHOD: Value = Value(3)
  val MACRO: Value = Value(6)
  val TYPE: Value = Value(7)
  val PARAMETER: Value = Value(8)
  val TYPE_PARAMETER: Value = Value(9)
  val OBJECT: Value = Value(10)
  val PACKAGE: Value = Value(11)
  val PACKAGE_OBJECT: Value = Value(12)
  val CLASS: Value = Value(13)
  val TRAIT: Value = Value(14)
  val SELF_PARAMETER: Value = Value(17)
  val INTERFACE: Value = Value(18)
  val LOCAL: Value = Value(19)
  val FIELD: Value = Value(20)
  val CONSTRUCTOR: Value = Value(21)
}

object PcSymbolProperty extends Enumeration {
  type PcSymbolProperty = Value
  val ABSTRACT: Value = Value(4)
}
