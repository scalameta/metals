package scala.meta.internal.implementation

import scala.collection.{mutable => m}

import scala.meta.internal.metals.codelenses.SuperMethodCodeLens.LensGoSuperCache
import scala.meta.internal.semanticdb.ClassSignature
import scala.meta.internal.semanticdb.MethodSignature
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.TypeRef
import scala.meta.internal.semanticdb.TypeSignature

object SuperMethodProvider {

  def findSuperForMethodOrField(
      methodSymbolInformation: SymbolInformation,
      documentWithPath: TextDocumentWithPath,
      symbolRole: SymbolOccurrence.Role,
      findSymbol: String => Option[SymbolInformation],
      cache: LensGoSuperCache
  ): Option[String] = {
    for {
      methodSignature <- isDefinitionOfMethodField(
        symbolRole,
        methodSymbolInformation
      )
      superSymbol <- findSuperForMethodOrFieldChecked(
        methodSymbolInformation,
        methodSignature,
        documentWithPath,
        cache,
        findSymbol
      )
    } yield superSymbol
  }

  def getSuperMethodHierarchy(
      methodSymbolInformation: SymbolInformation,
      documentWithPath: TextDocumentWithPath,
      symbolRole: SymbolOccurrence.Role,
      findSymbol: String => Option[SymbolInformation]
  ): Option[List[SymbolInformation]] = {
    for {
      methodSignature <- isDefinitionOfMethodField(
        symbolRole,
        methodSymbolInformation
      )
      superSymbolsInformation <- getSuperMethodHierarchyChecked(
        methodSymbolInformation,
        methodSignature,
        documentWithPath,
        findSymbol
      )

    } yield superSymbolsInformation
  }

  private def getSuperMethodHierarchyChecked(
      msi: SymbolInformation,
      methodInfo: MethodSignature,
      documentWithPath: TextDocumentWithPath,
      findSymbol: String => Option[SymbolInformation]
  ): Option[List[SymbolInformation]] = {
    val classSymbolInformationMaybe =
      findClassInfo(
        msi.symbol,
        msi.symbol.owner,
        documentWithPath.textDocument,
        findSymbol
      )
    classSymbolInformationMaybe.map(classSymbolInformation => {
      calculateClassSuperHierarchy(classSymbolInformation, findSymbol)
        .flatMap(matchMethodInClass(_, msi, methodInfo, findSymbol))
    })
  }

  private def matchMethodInClass(
      superClass: ClassHierarchyItem,
      baseMethodSymbolInformation: SymbolInformation,
      baseMethodInfo: MethodSignature,
      findSymbol: String => Option[SymbolInformation]
  ): Option[SymbolInformation] = {
    superClass.symbolInformation.signature match {
      case classSig: ClassSignature =>
        classSig.getDeclarations.symlinks
          .map(methodSymbolLink =>
            for {
              mSymbolInformation <- findSymbol(methodSymbolLink)
              methodSignature <- Option(mSymbolInformation.signature).collect {
                case m: MethodSignature => m
              }
              if checkSignaturesEqual(
                mSymbolInformation,
                methodSignature,
                baseMethodSymbolInformation,
                baseMethodInfo,
                superClass.asSeenFrom,
                findSymbol
              )
            } yield mSymbolInformation
          )
          .collectFirst { case Some(value) => value }
      case _ =>
        None
    }

  }

  private def getSuperClasses(
      symbolInformation: SymbolInformation,
      findSymbol: String => Option[SymbolInformation],
      skipSymbols: m.Set[String],
      asSeenFrom: Map[String, String]
  ): List[ClassHierarchyItem] = {
    if (skipSymbols.contains(symbolInformation.symbol)) {
      List.empty
    } else {
      skipSymbols += symbolInformation.symbol
      symbolInformation.signature match {
        case classSignature: ClassSignature =>
          val parents = classSignature.parents
            .collect { case x: TypeRef => x }
            .filterNot(typeRef =>
              stopSymbols.contains(typeRef.symbol) || skipSymbols
                .contains(typeRef.symbol)
            )
          val parentsHierarchy = parents
            .flatMap(p => findSymbol(p.symbol).map((_, p)))
            .map {
              case (si, p) =>
                val parentASF = AsSeenFrom.calculateAsSeenFrom(
                  p,
                  classSignature.typeParameters
                )
                getSuperClasses(si, findSymbol, skipSymbols, parentASF)
            }
            .toList
            .reverse
            .flatten
          ClassHierarchyItem(symbolInformation, asSeenFrom) +: parentsHierarchy
        case sig: TypeSignature =>
          val upperBound = sig.upperBound.asInstanceOf[TypeRef]
          findSymbol(upperBound.symbol)
            .filterNot(s => skipSymbols.contains(s.symbol))
            .map(si => {
              val parentASF =
                AsSeenFrom.calculateAsSeenFrom(upperBound, sig.typeParameters)
              getSuperClasses(si, findSymbol, skipSymbols, parentASF)
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
  ): List[ClassHierarchyItem] = {
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
  ): List[ClassHierarchyItem] = {
    getSuperClasses(
      classSymbolInformation,
      findSymbol,
      m.Set[String](),
      Map()
    )
  }

  private def findSuperForMethodOrFieldChecked(
      msi: SymbolInformation,
      methodInfo: MethodSignature,
      documentWithPath: TextDocumentWithPath,
      cache: LensGoSuperCache,
      findSymbol: String => Option[SymbolInformation]
  ): Option[String] = {
    val classSymbolInformationOption =
      findClassInfo(
        msi.symbol,
        msi.symbol.owner,
        documentWithPath.textDocument,
        findSymbol
      )
    val methodName = msi.displayName
    val result = for {
      classSymbolInformation <- classSymbolInformationOption.toIterable
      bottomClassSig =
        classSymbolInformation.signature
          .asInstanceOf[ClassSignature]
      ClassHierarchyItem(superClass, asSeenFrom) <-
        calculateClassSuperHierarchyWithCache(
          classSymbolInformation,
          cache,
          findSymbol
        )
      classSig <- List(superClass.signature).collect {
        case cs: ClassSignature => cs
      }
      methodSymbolLink <- classSig.getDeclarations.symlinks
      if msi.isLocal || methodSymbolLink.contains(methodName)
      mSymbolInformation <- findSymbol(methodSymbolLink).toIterable
      methodSignature <- Option(mSymbolInformation.signature).collect {
        case m: MethodSignature => m
      }
      translatedASF = AsSeenFrom.toRealNames(
        classSig,
        bottomClassSig,
        Some(asSeenFrom)
      )
      if checkSignaturesEqual(
        mSymbolInformation,
        methodSignature,
        msi,
        methodInfo,
        translatedASF,
        findSymbol
      )
    } yield mSymbolInformation.symbol
    result.headOption
  }

  private def isDefinitionOfMethodField(
      symbolRole: SymbolOccurrence.Role,
      symbolInformation: SymbolInformation
  ): Option[MethodSignature] =
    symbolInformation.signature match {
      case methodSignature: MethodSignature
          if symbolRole.isDefinition && (symbolInformation.isMethod || symbolInformation.isField) =>
        Some(methodSignature)
      case _ =>
        None
    }

  final val stopSymbols: Set[String] = Set(
    "scala/AnyRef#",
    "scala/Serializable#",
    "java/io/Serializable#",
    "scala/AnyVal#",
    "scala/Any#"
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
