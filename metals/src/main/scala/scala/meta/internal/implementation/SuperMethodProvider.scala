package scala.meta.internal.implementation

import scala.meta.internal.semanticdb.{
  ClassSignature,
  MethodSignature,
  Signature,
  SymbolInformation,
  SymbolOccurrence,
  TextDocument,
  TypeRef,
  TypeSignature,
  ValueSignature
}
import org.eclipse.{lsp4j => l}

import scala.meta.internal.implementation.ImplementationProvider.addParameterSignatures
import scala.meta.internal.implementation.MethodImplementation.{
  Context,
  paramsAreEqual,
  typeMappingFromMethodScope,
  typesAreEqual
}
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.semanticdb.Scala._
import scala.meta.io.AbsolutePath

class SuperMethodProvider(
    implementationProvider: ImplementationProvider,
    findSemanticDbWithPathForSymbol: String => Option[TextDocumentWithPath]
) {

  def findSuperForMethodOrField(
      msi: SymbolInformation,
      documentWithPath: TextDocumentWithPath,
      symbolRole: SymbolOccurrence.Role
  ): Option[l.Location] = {
    if (symbolRole.isDefinition && (msi.isMethod || msi.isField)) {
      findSuperForMethodOrFieldChecked(msi, documentWithPath)
    } else {
      None
    }
  }

  private def getSuperClasses(
      si: SymbolInformation,
      source: AbsolutePath,
      skip: scala.collection.mutable.Set[SymbolInformation]
  ): List[(SymbolInformation, Option[TextDocumentWithPath])] = {
    if (skip.exists(_.symbol == si.symbol)) {
      List()
    } else {
      skip += si
      si.signature match {
        case clz: ClassSignature =>
          val parents = clz.parents
            .collect { case x: TypeRef => x }
            .filterNot(_.symbol == "scala/AnyRef#")
            .filterNot(p => skip.exists(_.symbol == p.symbol))
          val mainParents = parents.headOption
            .flatMap(p => typeRefToSI(p, source))
            .map(si => getSuperClasses(si, source, skip))
            .toList
            .flatten
          val withParents = if (parents.nonEmpty) {
            parents.tail.reverse.flatMap { linParent =>
              typeRefToSI(linParent, source)
                .map(ssss => getSuperClasses(ssss, source, skip))
                .toIterable
                .flatten
            }.toList
          } else {
            List.empty
          }
          val expParents = withParents ++ mainParents
          val semDB = findSemanticDbWithPathForSymbol(si.symbol)
          (si, semDB) +: expParents
        case sig: TypeSignature =>
          typeRefToSI(sig.lowerBound.asInstanceOf[TypeRef], source)
            .filterNot(s => skip.exists(_.symbol == s.symbol))
            .map(getSuperClasses(_, source, skip))
            .getOrElse(List())
        case _ =>
          List()
      }
    }
  }

  private def typeRefToSI(
      tr: TypeRef,
      source: AbsolutePath
  ): Option[SymbolInformation] = {
    implementationProvider.search(tr.symbol, source)
  }

  private def calculateClassSuperHierarchy(
      classSymbolInformation: SymbolInformation,
      source: AbsolutePath
  ): List[(SymbolInformation, Option[TextDocumentWithPath])] = {
    val result = getSuperClasses(
      classSymbolInformation,
      source,
      scala.collection.mutable.Set[SymbolInformation]()
    )
    println(s"${classSymbolInformation.symbol} ==> ${result.map(_._1.symbol)}")
    result
  }

  def findSuperForMethodOrFieldChecked(
      msi: SymbolInformation,
      documentWithPath: TextDocumentWithPath
  ): Option[l.Location] = {
    val classSymbolInformation =
      findClassInfo(msi.symbol, msi.symbol.owner, documentWithPath.textDocument)
    val methodInfo = msi.signature.asInstanceOf[MethodSignature]

    val result = for {
      si <- classSymbolInformation.toIterable
      (superClass, docMaybe) <- calculateClassSuperHierarchy(
        si,
        documentWithPath.filePath
      )
      if superClass.signature.isInstanceOf[ClassSignature]
      classSig = superClass.signature.asInstanceOf[ClassSignature]
      methodSlink <- classSig.getDeclarations.symlinks
      mSymbolInformation <- implementationProvider
        .search(methodSlink, documentWithPath.filePath)
        .toIterable
      if mSymbolInformation.isMethod
      methodSignature = mSymbolInformation.signature
        .asInstanceOf[MethodSignature]
      if checkSignaturesEqual(
        msi,
        methodInfo,
        mSymbolInformation,
        methodSignature,
        documentWithPath.filePath
      )
      _ = { if (docMaybe.isEmpty) println(s"UNABLE TO JUMP TO $methodSlink") }
      doc <- docMaybe
      soc <- ImplementationProvider.findDefOccurrence(
        doc.textDocument,
        mSymbolInformation.symbol,
        doc.filePath
      )
    } yield new l.Location(doc.filePath.toURI.toString, soc.getRange.toLSP)
    result.headOption
  }

  private def findClassInfo(
      symbol: String,
      owner: String,
      textDocument: TextDocument
  ): Option[SymbolInformation] = {
    if (owner.nonEmpty) {
      ImplementationProvider.findSymbol(textDocument, owner)
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

  private def checkSignaturesEqual(
      first: SymbolInformation,
      firstMethod: MethodSignature,
      second: SymbolInformation,
      secondMethod: MethodSignature,
      source: AbsolutePath
  ): Boolean = {
    val findSymbol: String => Option[SymbolInformation] =
      implementationProvider.search(_, source)

    first.symbol != second.symbol &&
    first.displayName == second.displayName &&
    MethodImplementation.checkSignaturesEqual(
      firstMethod,
      secondMethod,
      findSymbol
    )
  }
}
