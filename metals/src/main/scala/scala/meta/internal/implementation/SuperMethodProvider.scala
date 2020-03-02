package scala.meta.internal.implementation

import org.eclipse.{lsp4j => l}
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.{
  ClassSignature,
  MethodSignature,
  SymbolInformation,
  SymbolOccurrence,
  TextDocument,
  TypeRef,
  TypeSignature
}

class SuperMethodProvider(
    findSemanticDbWithPathForSymbol: String => Option[TextDocumentWithPath]
) {

  def findSuperForMethodOrField(
      msi: SymbolInformation,
      documentWithPath: TextDocumentWithPath,
      symbolRole: SymbolOccurrence.Role,
      search: String => Option[SymbolInformation]
  ): Option[l.Location] = {

    findSuperForMethodOrField(
      msi,
      documentWithPath,
      symbolRole,
      search,
      scala.collection.mutable.Map()
    )
  }

  def findSuperForMethodOrField(
      msi: SymbolInformation,
      documentWithPath: TextDocumentWithPath,
      symbolRole: SymbolOccurrence.Role,
      findSymbol: String => Option[SymbolInformation],
      cache: scala.collection.mutable.Map[String, List[
        (SymbolInformation, Option[TextDocumentWithPath])
      ]]
  ): Option[l.Location] = {
    if (symbolRole.isDefinition && (msi.isMethod || msi.isField)) {
      findSuperForMethodOrFieldChecked(msi, documentWithPath, cache, findSymbol)
    } else {
      None
    }
  }

  private def getSuperClasses(
      si: SymbolInformation,
      findSymbol: String => Option[SymbolInformation],
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
            .filterNot(si =>
              Set(
                "scala/AnyRef#",
                "scala/Serializable#",
                "java/io/Serializable#",
                "scala/AnyVal#"
              ).contains(si.symbol)
            )
            .filterNot(p => skip.exists(_.symbol == p.symbol))
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
          val semDB = findSemanticDbWithPathForSymbol(si.symbol)
          (si, semDB) +: expParents
        case sig: TypeSignature =>
          findSymbol(sig.lowerBound.asInstanceOf[TypeRef].symbol)
            .filterNot(s => skip.exists(_.symbol == s.symbol))
            .map(getSuperClasses(_, findSymbol, skip))
            .getOrElse(List())
        case _ =>
          List()
      }
    }
  }

  private def calculateClassSuperHierarchyWithCache(
      classSymbolInformation: SymbolInformation,
      cache: scala.collection.mutable.Map[String, List[
        (SymbolInformation, Option[TextDocumentWithPath])
      ]],
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
    val result = getSuperClasses(
      classSymbolInformation,
      findSymbol,
      scala.collection.mutable.Set[SymbolInformation]()
    )
    println(s"${classSymbolInformation.symbol} ==> ${result.map(_._1.symbol)}")
    result
  }

  def findSuperForMethodOrFieldChecked(
      msi: SymbolInformation,
      documentWithPath: TextDocumentWithPath,
      cache: scala.collection.mutable.Map[String, List[
        (SymbolInformation, Option[TextDocumentWithPath])
      ]],
      findSymbol: String => Option[SymbolInformation]
  ): Option[l.Location] = {
    val classSymbolInformation =
      findClassInfo(msi.symbol, msi.symbol.owner, documentWithPath.textDocument)
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
      findSymbol: String => Option[SymbolInformation]
  ): Boolean = {
    first.symbol != second.symbol &&
    first.displayName == second.displayName &&
    MethodImplementation.checkSignaturesEqual(
      firstMethod,
      secondMethod,
      findSymbol
    )
  }
}
