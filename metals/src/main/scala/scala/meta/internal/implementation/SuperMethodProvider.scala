package scala.meta.internal.implementation

import scala.meta.internal.metals.CodeLensProvider.LensGoSuperCache
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.ClassSignature
import scala.meta.internal.semanticdb.MethodSignature
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.TypeRef
import scala.meta.internal.implementation.SuperMethodProvider._
import scala.meta.internal.semanticdb.TypeSignature

class SuperMethodProvider() {

  def findSuperForMethodOrField(
      methodSymbolInformation: SymbolInformation,
      documentWithPath: TextDocumentWithPath,
      symbolRole: SymbolOccurrence.Role,
      findSymbol: String => Option[SymbolInformation],
      cache: LensGoSuperCache
  ): Option[String] = {
    if (symbolRole.isDefinition && (methodSymbolInformation.isMethod || methodSymbolInformation.isField)) {
      findSuperForMethodOrFieldChecked(
        methodSymbolInformation,
        documentWithPath,
        cache,
        findSymbol
      )
    } else {
      None
    }
  }

  private def getSuperClasses(
      symbolInformation: SymbolInformation,
      findSymbol: String => Option[SymbolInformation],
      skip: scala.collection.mutable.Set[SymbolInformation],
      asSeenFrom: Map[String, String]
  ): List[(SymbolInformation, Map[String, String])] = {
    if (skip.exists(_.symbol == symbolInformation.symbol)) {
      List.empty
    } else {
      skip += symbolInformation
      symbolInformation.signature match {
        case classSignature: ClassSignature =>
          val parents = classSignature.parents
            .collect { case x: TypeRef => x }
            .filterNot(typeRef => stopSymbols.contains(typeRef.symbol))
            .filterNot(typeRef => skip.exists(_.symbol == typeRef.symbol))
          val parentsHierarchy = parents
            .flatMap(p => findSymbol(p.symbol).map((_, p)))
            .map {
              case (si, p) =>
                val parentASF = AsSeenFrom.calculateAsSeenFrom(
                  p,
                  classSignature.typeParameters
                )
                val currentASF =
                  AsSeenFrom.translateAsSeenFrom(asSeenFrom, parentASF)
                getSuperClasses(si, findSymbol, skip, currentASF)
            }
            .toList
            .reverse
            .flatten
          val outASF =
            AsSeenFrom.toRealNames(classSignature, true, Some(asSeenFrom))
          (symbolInformation, outASF) +: parentsHierarchy
        case sig: TypeSignature =>
          val upperBound = sig.upperBound.asInstanceOf[TypeRef]
          findSymbol(upperBound.symbol)
            .filterNot(s => skip.exists(_.symbol == s.symbol))
            .map(si => {
//              val classSig = si.signature.asInstanceOf[ClassSignature]
              val parentASF =
                AsSeenFrom.calculateAsSeenFrom(upperBound, sig.typeParameters)
              val currentASF =
                AsSeenFrom.translateAsSeenFrom(asSeenFrom, parentASF)
              getSuperClasses(si, findSymbol, skip, currentASF)
            })
            .getOrElse(List.empty)
        case _ =>
          List.empty
      }
    }
  }

  private def calculateClassSuperHierarchyWithCache(
      classSymbolInformation: SymbolInformation,
      cache: LensGoSuperCache,
      findSymbol: String => Option[SymbolInformation]
  ): List[(SymbolInformation, Map[String, String])] = {
    cache.get(classSymbolInformation.symbol) match {
      case Some(value) => value
      case None =>
        val value =
          calculateClassSuperHierarchy(classSymbolInformation, findSymbol)
        cache(classSymbolInformation.symbol) = value
        value
    }

  }

  private def calculateClassSuperHierarchy(
      classSymbolInformation: SymbolInformation,
      findSymbol: String => Option[SymbolInformation]
  ): List[(SymbolInformation, Map[String, String])] = {
    getSuperClasses(
      classSymbolInformation,
      findSymbol,
      scala.collection.mutable.Set[SymbolInformation](),
      Map()
    )
  }

  private def findSuperForMethodOrFieldChecked(
      msi: SymbolInformation,
      documentWithPath: TextDocumentWithPath,
      cache: LensGoSuperCache,
      findSymbol: String => Option[SymbolInformation]
  ): Option[String] = {
    val classSymbolInformation =
      findClassInfo(
        msi.symbol,
        msi.symbol.owner,
        documentWithPath.textDocument,
        findSymbol
      )
    val methodInfo = msi.signature.asInstanceOf[MethodSignature]

    val result = for {
      si <- classSymbolInformation.toIterable
      (superClass, asSeenFrom) <- calculateClassSuperHierarchyWithCache(
        si,
        cache,
        findSymbol
      )
      if superClass.signature.isInstanceOf[ClassSignature]
      classSig = superClass.signature.asInstanceOf[ClassSignature]
      methodSlink <- classSig.getDeclarations.symlinks
      mSymbolInformation <- findSymbol(methodSlink).toIterable
      if mSymbolInformation.isMethod
      methodSignature = mSymbolInformation.signature
        .asInstanceOf[MethodSignature]
      if checkSignaturesEqual(
        mSymbolInformation,
        methodSignature,
        msi,
        methodInfo,
        asSeenFrom,
        findSymbol
      )
    } yield mSymbolInformation.symbol
    result.headOption
  }

}

object SuperMethodProvider {

  final val stopSymbols = Set(
    "scala/AnyRef#",
    "scala/Serializable#",
    "java/io/Serializable#",
    "scala/AnyVal#"
  )

  def findClassInfo(
      symbol: String,
      owner: String,
      textDocument: TextDocument,
      findSymbol: String => Option[SymbolInformation]
  ): Option[SymbolInformation] = {
    if (owner.nonEmpty) {
      findSymbol(owner)
    } else {
      textDocument.symbols.find { sym =>
        sym.signature match {
          case sig: ClassSignature =>
            sig.declarations.exists(_.symlinks.contains(symbol))
          case _ => false
        }
      }
    }
  }

  def checkSignaturesEqual(
      parentSymbol: SymbolInformation,
      parentSignature: MethodSignature,
      childSymbol: SymbolInformation,
      childSignature: MethodSignature,
      asSeenFrom: Map[String, String],
      findSymbol: String => Option[SymbolInformation]
  ): Boolean = {
    parentSymbol.symbol != childSymbol.symbol &&
    parentSymbol.displayName == childSymbol.displayName &&
    MethodImplementation.checkSignaturesEqual(
      parentSignature,
      childSignature,
      asSeenFrom,
      findSymbol
    )
  }
}
