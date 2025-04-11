package scala.meta.internal.pc

import java.{util => ju}

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.pc.InspectResult
import scala.meta.pc.InspectResultParamsList
import scala.meta.pc.SymbolDocumentation

import org.eclipse.lsp4j.SymbolKind

final case class InspectResultImpl(
    override val symbol: String,
    override val kind: SymbolKind,
    override val resultType: String,
    override val visibility: String,
    paramss0: List[InspectResultParamsListImp] = Nil,
    members0: List[InspectResultImpl] = Nil,
    docstring0: Option[SymbolDocumentation] = None
) extends InspectResult {
  override def paramss(): ju.List[InspectResultParamsList] =
    (paramss0: List[InspectResultParamsList]).asJava
  override def members(): ju.List[InspectResult] =
    (members0: List[InspectResult]).asJava
  override def docstring(): ju.Optional[SymbolDocumentation] =
    docstring0.map(ju.Optional.of(_)).getOrElse(ju.Optional.empty())
}

final case class InspectResultParamsListImp(
    params0: List[String] = Nil,
    isType0: Boolean,
    override val implicitOrUsingKeyword: String
) extends InspectResultParamsList {
  def isType(): java.lang.Boolean =
    if (isType0) java.lang.Boolean.TRUE else java.lang.Boolean.FALSE
  def params(): ju.List[String] = params0.asJava
}
