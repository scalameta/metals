package scala.meta.internal.implementation
import scala.meta.internal.semanticdb.AnnotatedType
import scala.meta.internal.semanticdb.ByNameType
import scala.meta.internal.semanticdb.ClassSignature
import scala.meta.internal.semanticdb.ConstantType
import scala.meta.internal.semanticdb.ExistentialType
import scala.meta.internal.semanticdb.IntersectionType
import scala.meta.internal.semanticdb.MethodSignature
import scala.meta.internal.semanticdb.RepeatedType
import scala.meta.internal.semanticdb.Scala.Descriptor
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.Scope
import scala.meta.internal.semanticdb.Signature
import scala.meta.internal.semanticdb.SingleType
import scala.meta.internal.semanticdb.StructuralType
import scala.meta.internal.semanticdb.SuperType
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.semanticdb.ThisType
import scala.meta.internal.semanticdb.Type
import scala.meta.internal.semanticdb.Type.Empty
import scala.meta.internal.semanticdb.TypeRef
import scala.meta.internal.semanticdb.UnionType
import scala.meta.internal.semanticdb.UniversalType
import scala.meta.internal.semanticdb.ValueSignature
import scala.meta.internal.semanticdb.WithType

object MethodImplementation {

  import ImplementationProvider._

  def findParentSymbol(
      bottomSymbolInformation: SymbolInformation,
      bottomClassSig: ClassSignature,
      parentClassSig: ClassSignature,
      asSeenFromMap: Map[String, String],
      findSymbol: String => Option[SymbolInformation]
  ): Option[String] = {
    val validMethods = for {
      declarations <- parentClassSig.declarations.toIterable
      methodSymbol <- declarations.symlinks
      methodSymbolInfo <- findSymbol(methodSymbol)
      asSeenFrom = AsSeenFrom.toRealNames(
        parentClassSig,
        bottomClassSig,
        Some(asSeenFromMap)
      )
      context = Context(
        findSymbol,
        findSymbol,
        asSeenFrom
      )
      if isOverriddenMethod(
        methodSymbolInfo,
        bottomSymbolInformation,
        findParent = true
      )(
        context
      )
    } yield methodSymbol
    validMethods.headOption
  }

  def findInherited(
      parentSymbol: SymbolInformation,
      parentClassSymbol: SymbolInformation,
      inheritanceContext: InheritanceContext,
      classLocation: ClassLocation,
      findSymbolInCurrentContext: String => Option[SymbolInformation]
  ): Option[String] = {
    val classSymbolInfo = findSymbolInCurrentContext(classLocation.symbol)

    def createAsSeenFrom(info: SymbolInformation) = {
      classLocation
        .toRealNames(info, translateKey = false)
        .asSeenFromMap
    }

    val validMethods = for {
      symbolInfo <- classSymbolInfo.toIterable
      if symbolInfo.signature.isInstanceOf[ClassSignature]
      classSignature = symbolInfo.signature.asInstanceOf[ClassSignature]
      declarations <- classSignature.declarations.toIterable
      methodSymbol <- declarations.symlinks
      methodSymbolInfo <- findSymbolInCurrentContext(methodSymbol)
      asSeenFrom = createAsSeenFrom(symbolInfo)
      context = Context(
        findSymbolInCurrentContext,
        inheritanceContext.findSymbol,
        asSeenFrom
      )
      if isOverriddenMethod(methodSymbolInfo, parentSymbol)(context)
    } yield methodSymbol
    validMethods.headOption
  }

  private def isOverriddenMethod(
      methodSymbolInfo: SymbolInformation,
      otherSymbol: SymbolInformation,
      findParent: Boolean = false
  )(implicit context: Context): Boolean = {
    val isVisiblySame =
      (methodSymbolInfo.kind.isField || methodSymbolInfo.kind.isMethod) &&
        methodSymbolInfo.displayName == otherSymbol.displayName
    if (findParent) {
      isVisiblySame && signaturesEqual(
        methodSymbolInfo.signature,
        otherSymbol.signature
      )(
        context
      )
    } else {
      isVisiblySame && signaturesEqual(
        otherSymbol.signature,
        methodSymbolInfo.signature
      )(
        context
      )
    }
  }

  private def symbolsAreEqual(
      symParent: String,
      symChild: String
  )(implicit context: Context) = {
    val dealiasedChild = dealiasClass(symChild, context.findSymbol)
    val dealiasedParent = dealiasClass(symParent, context.findSymbolInParent)
    (dealiasedParent.desc, dealiasedChild.desc) match {
      case (Descriptor.TypeParameter(tp), Descriptor.TypeParameter(tc)) =>
        context.asSeenFrom.getOrElse(tp, tp) == tc
      case (Descriptor.TypeParameter(tp), Descriptor.Type(tc)) =>
        context.asSeenFrom.getOrElse(tp, tp) == tc
      case (Descriptor.Parameter(tp), Descriptor.Parameter(tc)) =>
        tp == tc
      case (Descriptor.Type(tp), Descriptor.Type(tc)) =>
        context.asSeenFrom.getOrElse(tp, tp) == tc
      case (Descriptor.Term(tp), Descriptor.Term(tc)) =>
        tp == tc
      case _ => false
    }
  }

  private def allTypesAreEqual(
      typesParent: Seq[Type],
      typesChild: Seq[Type]
  )(implicit context: Context): Boolean = {
    typesParent.zip(typesChild).forall { case (p, c) => typesAreEqual(p, c) }
  }

  private def typesAreEqual(
      typeParent: Type,
      typeChild: Type
  )(implicit context: Context): Boolean = {
    (typeParent, typeChild) match {
      case (tp: SingleType, tc: SingleType) =>
        typesAreEqual(tp.prefix, tc.prefix) &&
          symbolsAreEqual(tp.symbol, tc.symbol)
      case (tp: SuperType, tc: SuperType) =>
        typesAreEqual(tp.prefix, tc.prefix) &&
          symbolsAreEqual(tp.symbol, tc.symbol)
      case (tp: TypeRef, tc: TypeRef) =>
        symbolsAreEqual(tp.symbol, tc.symbol)
      case (tp: AnnotatedType, tc: AnnotatedType) =>
        typesAreEqual(tp.tpe, tc.tpe)
      case (tp: UniversalType, tc: UniversalType) =>
        typesAreEqual(tp.tpe, tc.tpe)
      case (tp: ThisType, tc: ThisType) =>
        symbolsAreEqual(tp.symbol, tc.symbol)
      case (tp: ExistentialType, tc: ExistentialType) =>
        typesAreEqual(tp.tpe, tc.tpe)
      case (tp: RepeatedType, tc: RepeatedType) =>
        typesAreEqual(tp.tpe, tc.tpe)
      case (tp: IntersectionType, tc: IntersectionType) =>
        allTypesAreEqual(tp.types, tc.types)
      case (tp: WithType, tc: WithType) =>
        allTypesAreEqual(tp.types, tc.types)
      case (tp: UnionType, tc: UnionType) =>
        allTypesAreEqual(tp.types, tc.types)
      case (tp: StructuralType, tc: StructuralType) =>
        typesAreEqual(tp.tpe, tc.tpe)
      case (tp: ByNameType, tc: ByNameType) =>
        typesAreEqual(tp.tpe, tc.tpe)
      case (tp: ConstantType, tc: ConstantType) =>
        tp.constant == tc.constant
      case (Empty, Empty) => true
      case _ => false
    }
  }

  private def paramsAreEqual(
      scopesParent: Seq[Scope],
      scopesChild: Seq[Scope]
  )(implicit context: Context): Boolean = {
    scopesParent.size == scopesChild.size &&
    scopesParent.zip(scopesChild).forall {
      case (scopePar, scopeChild) =>
        scopePar.hardlinks.size == scopeChild.hardlinks.size && scopePar.hardlinks
          .zip(scopeChild.hardlinks)
          .forall {
            case (linkPar, linkChil) =>
              signaturesEqual(linkPar.signature, linkChil.signature)
          }
    }
  }

  def checkSignaturesEqual(
      parentSignature: MethodSignature,
      childSignature: MethodSignature,
      asSeenFrom: Map[String, String],
      findSymbol: String => Option[SymbolInformation]
  ): Boolean = {
    val context = Context(findSymbol, findSymbol, asSeenFrom)
    signaturesEqual(parentSignature, childSignature)(context)
  }

  private def signaturesEqual(
      parentSignature: Signature,
      childSignature: Signature
  )(implicit context: Context): Boolean = {
    (parentSignature, childSignature) match {
      case (
            methodParentSignature: MethodSignature,
            methodChildSignature: MethodSignature
          ) =>
        val newContext = context.addAsSeenFrom(
          typeMappingFromMethodScope(
            methodParentSignature.typeParameters,
            methodChildSignature.typeParameters
          )
        )
        lazy val enrichedSig1 =
          addParameterSignatures(methodParentSignature, context.findSymbol)
        lazy val enrichedSig2 =
          addParameterSignatures(methodChildSignature, context.findSymbol)
        paramsAreEqual(
          enrichedSig1.parameterLists,
          enrichedSig2.parameterLists
        )(newContext)
      case (v1: ValueSignature, v2: ValueSignature) =>
        typesAreEqual(v1.tpe, v2.tpe)
      case _ => false
    }
  }

  private def typeMappingFromMethodScope(
      scopeParent: Option[Scope],
      scopeChild: Option[Scope]
  ): Map[String, String] = {
    val mappings = for {
      scopeP <- scopeParent.toList
      scopeC <- scopeChild.toList
      (typeP, typeC) <- scopeP.symlinks.zip(scopeC.symlinks)
    } yield typeP.desc.name.toString -> typeC.desc.name.toString
    mappings.toMap
  }

  private case class Context(
      findSymbol: String => Option[SymbolInformation],
      findSymbolInParent: String => Option[SymbolInformation],
      asSeenFrom: Map[String, String]
  ) {
    def addAsSeenFrom(asSeenFrom: Map[String, String]): Context = {
      this.copy(asSeenFrom = this.asSeenFrom ++ asSeenFrom)
    }
  }
}
