package scala.meta.internal.implementation

import org.eclipse.{lsp4j => l}
import scala.meta.internal.metals.CodeLensProvider.LensGoSuperCache
import scala.meta.internal.metals.DefinitionProvider
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.ClassSignature
import scala.meta.internal.semanticdb.MethodSignature
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.TypeRef
import scala.meta.internal.semanticdb.TypeSignature
import SuperMethodProvider._

class SuperMethodProvider(
    definitionProvider: DefinitionProvider,
    findSemanticDbWithPathForSymbol: String => Option[TextDocumentWithPath]
) {

  def findSuperForMethodOrField(
      methodSymbolInformation: SymbolInformation,
      documentWithPath: TextDocumentWithPath,
      symbolRole: SymbolOccurrence.Role,
      findSymbol: String => Option[SymbolInformation],
      cache: LensGoSuperCache
  ): Option[l.Location] = {
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
      skip: scala.collection.mutable.Set[SymbolInformation]
  ): List[(SymbolInformation, Option[TextDocumentWithPath])] = {
    if (skip.exists(_.symbol == symbolInformation.symbol)) {
      List()
    } else {
      skip += symbolInformation
      symbolInformation.signature match {
        case classSignature: ClassSignature =>
          val parents = classSignature.parents
            .collect { case x: TypeRef => x }
            .filterNot(typeRef => stopSymbols.contains(typeRef.symbol))
            .filterNot(typeRef => skip.exists(_.symbol == typeRef.symbol))
          val mainParents = parents.headOption
            .flatMap(p => findSymbol(p.symbol))
            .map(si => getSuperClasses(si, findSymbol, skip))
            .toList
            .flatten
          val withParents = if (parents.nonEmpty) {
            parents.tail.reverse.flatMap { linParent =>
              findSymbol(linParent.symbol)
                .map(ssss => getSuperClasses(ssss, findSymbol, skip))
                .toIterable
                .flatten
            }.toList
          } else {
            List.empty
          }
          val expParents = withParents ++ mainParents
          val semDB = findSemanticDbWithPathForSymbol(symbolInformation.symbol)
          (symbolInformation, semDB) +: expParents
        case sig: TypeSignature =>
          findSymbol(sig.lowerBound.asInstanceOf[TypeRef].symbol)
            .filterNot(s => skip.exists(_.symbol == s.symbol))
            .map(getSuperClasses(_, findSymbol, skip))
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
  ): List[(SymbolInformation, Option[TextDocumentWithPath])] = {
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
  ): List[(SymbolInformation, Option[TextDocumentWithPath])] = {
    getSuperClasses(
      classSymbolInformation,
      findSymbol,
      scala.collection.mutable.Set[SymbolInformation]()
    )
  }

  private def findSuperForMethodOrFieldChecked(
      msi: SymbolInformation,
      documentWithPath: TextDocumentWithPath,
      cache: LensGoSuperCache,
      findSymbol: String => Option[SymbolInformation]
  ): Option[l.Location] = {
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
      (superClass, docMaybe) <- calculateClassSuperHierarchyWithCache(
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
        msi,
        methodInfo,
        mSymbolInformation,
        methodSignature,
        findSymbol
      )
      loc <- {
        docMaybe match {
          case Some(doc) =>
            val socMaybe = ImplementationProvider.findDefOccurrence(
              doc.textDocument,
              mSymbolInformation.symbol,
              doc.filePath
            )
            socMaybe.map(soc =>
              new l.Location(doc.filePath.toURI.toString, soc.getRange.toLSP)
            )
          case None =>
            definitionProvider
              .fromSymbol(mSymbolInformation.symbol)
              .asScala
              .headOption
        }
      }
    } yield loc
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
      findSymbol: String => Option[SymbolInformation]
  ): Boolean = {
    parentSymbol.symbol != childSymbol.symbol &&
    parentSymbol.displayName == childSymbol.displayName &&
    MethodImplementation.checkSignaturesEqual(
      parentSignature,
      childSignature,
      findSymbol
    )
  }
}
