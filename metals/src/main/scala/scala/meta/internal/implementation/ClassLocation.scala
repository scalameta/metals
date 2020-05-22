package scala.meta.internal.implementation

import java.nio.file.Path

import scala.util.Success
import scala.util.Try

import scala.meta.internal.semanticdb.ClassSignature
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.Scope
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.semanticdb.TypeRef

private[implementation] case class ClassLocation(
    symbol: String,
    file: Option[Path],
    private val asSeenFrom: Option[Map[String, String]]
) {

  def asSeenFromMap: Map[String, String] = asSeenFrom.getOrElse(Map.empty)

  def translateAsSeenFrom(other: ClassLocation): ClassLocation = {
    val newASF = other.asSeenFrom match {
      case None => this.asSeenFrom
      case Some(parentASF) =>
        asSeenFrom match {
          case None => Some(parentASF)
          case Some(childASF) =>
            Some(AsSeenFrom.translateAsSeenFrom(childASF, parentASF))
        }
    }
    this.copy(asSeenFrom = newASF)
  }

  // Translate position based names to real names in the class
  def toRealNames(
      classInfo: SymbolInformation,
      translateKey: Boolean
  ): ClassLocation = {
    classInfo.signature match {
      case clsSig: ClassSignature =>
        val newASF = AsSeenFrom.toRealNames(clsSig, translateKey, asSeenFrom)
        ClassLocation(symbol, file, newASF.toMap)
      case other => this
    }
  }
}

private[implementation] object ClassLocation {

  def apply(
      symbol: String,
      file: Option[Path],
      asSeenFrom: Map[String, String]
  ): ClassLocation = {
    if (asSeenFrom.isEmpty) {
      ClassLocation(symbol, file, asSeenFrom = None)
    } else {
      ClassLocation(symbol, file, asSeenFrom = Some(asSeenFrom))
    }
  }

  def apply(
      symbol: String,
      file: Option[Path],
      typeRef: TypeRef,
      typeParameters: Option[Scope]
  ): ClassLocation = {
    val asSeenFrom = AsSeenFrom.calculateAsSeenFrom(typeRef, typeParameters)
    ClassLocation(symbol, file, asSeenFrom)
  }
}
object AsSeenFrom {

  def translateAsSeenFrom(
      childASF: Map[String, String],
      parentASF: Map[String, String]
  ): Map[String, String] = {
    parentASF.map {
      case (key, value) => key -> childASF.getOrElse(value, value)
    }
  }

  def calculateAsSeenFrom(
      parentType: TypeRef,
      typeParameters: Option[Scope]
  ): Map[String, String] = {
    parentType.typeArguments.zipWithIndex.flatMap {
      case (arg: TypeRef, ind) =>
        // create mapping dependent on order - this way we don't need parent information here
        typeParameters match {
          case Some(sc) =>
            val indInClass = sc.symlinks.indexOf(arg.symbol)
            if (indInClass >= 0)
              Some(s"$ind" -> s"$indInClass")
            else
              Some(s"$ind" -> arg.symbol.desc.name.toString())
          case None => None
        }

      case other => None
    }.toMap
  }

  // Translate position based names to real names in the class
  def toRealNames(
      parentClassSig: ClassSignature,
      childClassSig: ClassSignature,
      asSeenFrom: Option[Map[String, String]]
  ): Map[String, String] = {
    val mappedKeys = toRealNames(parentClassSig, true, asSeenFrom)
    toRealNames(childClassSig, false, Some(mappedKeys))
  }

  def toRealNames(
      classSig: ClassSignature,
      translateKey: Boolean,
      asSeenFrom: Option[Map[String, String]]
  ): Map[String, String] = {
    val newASF = for {
      typeScope <- classSig.typeParameters.toList
      asf <- asSeenFrom.toList
      (key, value) <- asf
    } yield {
      val translated = if (translateKey) key else value
      Try(translated.toInt) match {
        case Success(ind) if typeScope.symlinks.size > ind =>
          if (translateKey)
            typeScope.symlinks(ind).desc.name.toString() -> value
          else
            key -> typeScope.symlinks(ind).desc.name.toString()
        case _ =>
          key -> value
      }
    }
    newASF.toMap
  }

}
