package scala.meta.internal.implementation

import scala.meta.internal.semanticdb.ClassSignature
import scala.meta.internal.semanticdb.SymbolInformation

object MethodImplementation {

  def findParentSymbol(
      bottomSymbolInformation: SymbolInformation,
      parentClassSig: ClassSignature,
      findSymbol: String => Option[SymbolInformation]
  ): Option[String] = {
    val validMethods = for {
      declarations <- parentClassSig.declarations.toIterable
      methodSymbol <- declarations.symlinks
      methodSymbolInfo <- findSymbol(methodSymbol)
      if isOverriddenMethod(bottomSymbolInformation, methodSymbolInfo)
    } yield methodSymbol
    validMethods.headOption
  }

  def findInherited(
      parentSymbol: SymbolInformation,
      classLocation: ClassLocation,
      findSymbolInCurrentContext: String => Option[SymbolInformation]
  ): Option[String] = {
    val classSymbolInfo = findSymbolInCurrentContext(classLocation.symbol)

    val validMethods = for {
      symbolInfo <- classSymbolInfo.toIterable
      if symbolInfo.signature.isInstanceOf[ClassSignature]
      classSignature = symbolInfo.signature.asInstanceOf[ClassSignature]
      declarations <- classSignature.declarations.toIterable
      methodSymbol <- declarations.symlinks
      methodSymbolInfo <- findSymbolInCurrentContext(methodSymbol)
      if isOverriddenMethod(methodSymbolInfo, parentSymbol)
    } yield methodSymbol
    validMethods.headOption
  }

  private def isOverriddenMethod(
      methodSymbolInfo: SymbolInformation,
      parentSymbol: SymbolInformation
  ): Boolean = {
    methodSymbolInfo.overriddenSymbols.contains(parentSymbol.symbol)
  }

}
