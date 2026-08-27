package scala.meta.internal.metals.mbt

import scala.collection.mutable
import scala.jdk.CollectionConverters._

import scala.meta.internal.pc.PcSymbolInformation
import scala.meta.pc.PcSymbolKind
import scala.meta.pc.PcSymbolProperty

import com.google.turbine.`type`.AnnoInfo
import com.google.turbine.binder.bound.TypeBoundClass
import com.google.turbine.binder.sym.ClassSymbol
import com.google.turbine.model.TurbineFlag
import com.google.turbine.model.TurbineTyKind

object TurbineSymbolInfo {

  /**
   * SemanticDB class symbol for a JVMS binary name.
   * `com/foo/Bar` -> `com/foo/Bar#`, `com/foo/Outer$Inner` -> `com/foo/Outer#Inner#`.
   */
  def classSymbolToSemanticdb(binaryName: String): String = {
    val slash = binaryName.lastIndexOf('/')
    val pkg = if (slash < 0) "" else binaryName.substring(0, slash + 1)
    val simple =
      if (slash < 0) binaryName else binaryName.substring(slash + 1)
    pkg + simple.replace('$', '#') + "#"
  }

  def fromBoundClass(
      symbol: String,
      cls: TypeBoundClass,
  ): PcSymbolInformation = {
    val parentSemanticdb =
      directParents(cls).map(s => classSymbolToSemanticdb(s.binaryName()))
    PcSymbolInformation(
      symbol = symbol,
      kind = pcKind(cls),
      parents = parentSemanticdb,
      dealiasedSymbol = symbol,
      classOwner =
        Option(cls.owner()).map(o => classSymbolToSemanticdb(o.binaryName())),
      overriddenSymbols = Nil,
      alternativeSymbols = Nil,
      properties =
        if ((cls.access() & TurbineFlag.ACC_ABSTRACT) != 0)
          List(PcSymbolProperty.ABSTRACT)
        else Nil,
      recursiveParents = Nil,
      annotations = annotationSymbols(
        cls.annotations().asScala
      ),
      memberDefsAnnotations = annotationSymbols(
        cls.methods().asScala.flatMap(_.annotations().asScala)
      ),
      typeParameters = cls
        .typeParameters()
        .keySet()
        .asScala
        .map(name => s"$symbol[$name]")
        .toList,
    )
  }

  private def pcKind(cls: TypeBoundClass): PcSymbolKind =
    cls.kind() match {
      case TurbineTyKind.INTERFACE | TurbineTyKind.ANNOTATION =>
        PcSymbolKind.INTERFACE
      case TurbineTyKind.CLASS | TurbineTyKind.ENUM | TurbineTyKind.RECORD =>
        PcSymbolKind.CLASS
    }

  private def annotationSymbols(
      annotations: Iterable[AnnoInfo]
  ): List[String] =
    annotations
      .map(anno => classSymbolToSemanticdb(anno.sym().binaryName()))
      .toList
      .distinct

  private def directParents(cls: TypeBoundClass): List[ClassSymbol] = {
    val result = mutable.ArrayBuffer.empty[ClassSymbol]
    Option(cls.superclass()).foreach(result += _)
    cls.interfaces().asScala.foreach(result += _)
    result.toList
  }
}
