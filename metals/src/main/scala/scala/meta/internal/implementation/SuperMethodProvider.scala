package scala.meta.internal.implementation

import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.ClassSignature
import scala.meta.internal.semanticdb.MethodSignature
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.TypeRef
import scala.meta.internal.semanticdb.TypeSignature
import scala.collection.{mutable => m}
import scala.meta.internal.metals.codelenses.SuperMethodLensesProvider.LensGoSuperCache

object SuperMethodProvider {

  def findSuperForMethodOrField(
      methodSymbolInformation: SymbolInformation,
      documentWithPath: TextDocumentWithPath,
      symbolRole: SymbolOccurrence.Role,
      findSymbol: String => Option[SymbolInformation],
      cache: LensGoSuperCache
  ): Option[String] = {
    if (isDefinitionOfMethodField(symbolRole, methodSymbolInformation)) {
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

  def getSuperMethodHierarchy(
      methodSymbolInformation: SymbolInformation,
      documentWithPath: TextDocumentWithPath,
      symbolRole: SymbolOccurrence.Role,
      findSymbol: String => Option[SymbolInformation]
  ): Option[List[SymbolInformation]] = {
    if (isDefinitionOfMethodField(symbolRole, methodSymbolInformation)) {
      getSuperMethodHierarchyChecked(
        methodSymbolInformation,
        documentWithPath,
        findSymbol
      )
    } else {
      None
    }
  }

  private def getSuperMethodHierarchyChecked(
      msi: SymbolInformation,
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
    val methodInfo = msi.signature.asInstanceOf[MethodSignature]

    classSymbolInformationMaybe.map(classSymbolInformation => {
      calculateClassSuperHierarchy(classSymbolInformation, findSymbol)
        .flatMap(matchMethodInClass(_, msi, methodInfo, findSymbol))
    })
  }

  private def matchMethodInClass(
      superClass: SymbolWithAsSeenFrom,
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
              if mSymbolInformation.isMethod
              methodSignature = mSymbolInformation.signature
                .asInstanceOf[MethodSignature]
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
  ): List[SymbolWithAsSeenFrom] = {
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
          SymbolWithAsSeenFrom(symbolInformation, asSeenFrom) +: parentsHierarchy
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
  ): List[SymbolWithAsSeenFrom] = {
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
  ): List[SymbolWithAsSeenFrom] = {
    getSuperClasses(
      classSymbolInformation,
      findSymbol,
      m.Set[String](),
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
      bottomClassSig = si.signature.asInstanceOf[ClassSignature]
      SymbolWithAsSeenFrom(superClass, asSeenFrom) <- calculateClassSuperHierarchyWithCache(
        si,
        cache,
        findSymbol
      )
      if superClass.signature.isInstanceOf[ClassSignature]
      classSig = superClass.signature.asInstanceOf[ClassSignature]
      methodSymbolLink <- classSig.getDeclarations.symlinks
      mSymbolInformation <- findSymbol(methodSymbolLink).toIterable
      if mSymbolInformation.isMethod
      methodSignature = mSymbolInformation.signature
        .asInstanceOf[MethodSignature]
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
  ): Boolean =
    symbolRole.isDefinition && (symbolInformation.isMethod || symbolInformation.isField)

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
