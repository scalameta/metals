package scala.meta.internal.metals.docstrings.query

import java.{util => ju}

import scala.meta.internal.docstrings._
import scala.meta.internal.docstrings.printers.PlaintextGenerator
import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.pc

object QuerySymbolDocstringCreator {
  def createDocstring(
      sinfo: SymbolInformation,
      comment: Comment,
      docstring: String
  ): Option[pc.SymbolDocumentation] = {
    if (docstring == "") return None
    else
      Some(
        SymbolDocumentation(
          sinfo.symbol,
          PlaintextGenerator.toText(comment.body),
          comment.valueParams.map { case (name, desc) =>
            name -> PlaintextGenerator.toText(desc)
          }.toList,
          comment.result.map(PlaintextGenerator.toText(_)),
          comment.example.map(PlaintextGenerator.toText(_))
        )
      )
  }
}

/**
 * Documentation for a symbol.
 */
case class SymbolDocumentation(
    override val symbol: String,
    description: String,
    params: List[(String, String)], // name -> description
    returnValue: Option[String],
    examples: List[String]
) extends scala.meta.pc.SymbolDocumentation {
  override def displayName(): String = symbol.fqcn
  override def docstring(): String = description
  override def defaultValue(): String = ""
  override def parameters(): ju.List[pc.SymbolDocumentation] =
    params.map { param =>
      new scala.meta.pc.SymbolDocumentation {
        override def symbol(): String = param._1
        override def displayName(): String = param._1
        override def docstring(): String = param._2
        override def defaultValue(): String = ""
        override def parameters(): ju.List[pc.SymbolDocumentation] = Nil.asJava
        override def typeParameters(): ju.List[pc.SymbolDocumentation] =
          Nil.asJava
      }
    }.asJava
  override def typeParameters(): ju.List[pc.SymbolDocumentation] = Nil.asJava

}
