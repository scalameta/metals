package scala.meta.internal.pc

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.meta.pc
import scala.meta.pc.SymbolDocumentation

trait Signatures { this: MetalsGlobal =>

  class ShortenedNames(history: mutable.Map[Name, Symbol] = mutable.Map.empty) {
    def tryShortenName(name: Option[Name], sym: Symbol): Boolean =
      name match {
        case Some(n) =>
          history.get(n) match {
            case Some(other) =>
              if (other == sym) true
              else false
            case _ =>
              history(n) = sym
              true
          }
        case _ =>
          false
      }
  }

  class SignaturePrinter(
      gsym: Symbol,
      shortenedNames: ShortenedNames,
      gtpe: Type,
      includeDocs: Boolean
  ) {
    private val info: Option[SymbolDocumentation] =
      if (includeDocs) {
        symbolDocumentation(gsym)
      } else {
        None
      }
    private val infoParamsA: Seq[pc.SymbolDocumentation] = info match {
      case Some(value) =>
        value.typeParameters().asScala ++
          value.parameters().asScala
      case None =>
        IndexedSeq.empty
    }
    private val infoParams =
      infoParamsA.lift
    private val returnType =
      metalsToLongString(gtpe.finalResultType, shortenedNames)

    def methodDocstring: String = {
      if (isDocs) info.fold("")(_.docstring())
      else ""
    }
    def isTypeParameters: Boolean = gtpe.typeParams.nonEmpty
    def implicitParams: Option[List[Symbol]] =
      gtpe.paramss.lastOption.filter(_.headOption.exists(_.isImplicit))
    val implicitEvidencesByTypeParam
        : collection.Map[Symbol, ListBuffer[String]] = {
      val result = mutable.Map.empty[Symbol, ListBuffer[String]]
      for {
        param <- implicitParams.getOrElse(Nil).iterator
        if param.name.startsWith(termNames.EVIDENCE_PARAM_PREFIX)
        TypeRef(
          _,
          sym,
          TypeRef(NoPrefix, tparam, Nil) :: Nil
        ) <- List(param.info)
      } {
        val buf = result.getOrElseUpdate(tparam, ListBuffer.empty)
        buf += sym.name.toString
      }
      result
    }
    def isImplicit: Boolean = implicitParams.isDefined
    def mparamss: List[List[Symbol]] =
      gtpe.typeParams match {
        case Nil => gtpe.paramss
        case tparams => tparams :: gtpe.paramss
      }
    def defaultMethodSignature: String = {
      var i = 0
      val paramss = gtpe.typeParams match {
        case Nil => gtpe.paramss
        case tparams => tparams :: gtpe.paramss
      }
      val params = paramss.iterator.flatMap { params =>
        val labels = params.flatMap { param =>
          if (param.name.startsWith(termNames.EVIDENCE_PARAM_PREFIX)) {
            Nil
          } else {
            val result = paramLabel(param, i)
            i += 1
            result :: Nil
          }
        }
        if (labels.isEmpty && params.nonEmpty) Nil
        else labels.iterator :: Nil
      }
      methodSignature(params, name = "")
    }

    def methodSignature(
        paramLabels: Iterator[Iterator[String]],
        name: String = gsym.nameString
    ): String = {
      paramLabels
        .zip(mparamss.iterator)
        .map {
          case (params, syms) =>
            paramsKind(syms) match {
              case Params.TypeParameterKind =>
                params.mkString("[", ", ", "]")
              case Params.NormalKind =>
                params.mkString("(", ", ", ")")
              case Params.ImplicitKind =>
                params.mkString("(implicit ", ", ", ")")
            }
        }
        .mkString(name, "", s": ${returnType}")
    }
    def paramsKind(syms: List[Symbol]): Params.Kind = {
      syms match {
        case head :: _ =>
          if (head.isType) Params.TypeParameterKind
          else if (head.isImplicit) Params.ImplicitKind
          else Params.NormalKind
        case Nil => Params.NormalKind
      }
    }
    def paramDocstring(paramIndex: Int): String = {
      if (isDocs) infoParams(paramIndex).fold("")(_.docstring())
      else ""
    }
    def paramLabel(param: Symbol, index: Int): String = {
      val paramTypeString = metalsToLongString(param.info, shortenedNames)
      val name = infoParams(index) match {
        case Some(value) if param.name.startsWith("x$") =>
          value.displayName()
        case _ => param.nameString
      }
      if (param.isTypeParameter) {
        val contextBounds =
          implicitEvidencesByTypeParam.getOrElse(param, Nil) match {
            case Nil => ""
            case head :: Nil => s":$head"
            case many => many.mkString(": ", ": ", "")
          }
        s"$name$paramTypeString$contextBounds"
      } else {
        val default =
          if (param.isParamWithDefault) {
            val defaultValue = infoParams(index).map(_.defaultValue()) match {
              case Some(value) if !value.isEmpty => value
              case _ => "{}"
            }
            s" = $defaultValue"
          } else {
            ""
          }
        s"$name: ${paramTypeString}$default"
      }
    }
  }
}
