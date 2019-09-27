package scala.meta.internal.implementation
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.Type
import scala.meta.internal.semanticdb.Scope
import scala.meta.internal.semanticdb.ValueSignature
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.Scala.Descriptor
import scala.meta.internal.semanticdb.TypeSignature
import scala.meta.internal.semanticdb.TypeRef
import scala.meta.internal.semanticdb.MethodSignature
import scala.meta.internal.semanticdb.Signature
import scala.meta.internal.semanticdb.ClassSignature

object MethodImplementation {

  import ImplementationProvider._

  def find(
      parentSymbol: SymbolInformation,
      parentClassSymbol: SymbolInformation,
      classLocation: ClassLocation,
      currentDocument: TextDocument
  ): Option[SymbolOccurrence] = {
    val classSymbolInfo = findSymbol(currentDocument, classLocation.symbol)

    def createAsSeenFrom(info: SymbolInformation) = {
      classLocation
        .toRealNames(info, translateKey = false)
        .asSeenFromMap
    }

    def isOverridenMethod(
        methodSymbolInfo: SymbolInformation
    )(implicit context: Context): Boolean = {
      (methodSymbolInfo.kind.isField || methodSymbolInfo.kind.isMethod) &&
      methodSymbolInfo.displayName == parentSymbol.displayName &&
      signaturesEqual(parentSymbol.signature, methodSymbolInfo.signature)(
        context
      )
    }

    val validMethods = for {
      symbolInfo <- classSymbolInfo.toIterable
      if symbolInfo.signature.isInstanceOf[ClassSignature]
      classSignature = symbolInfo.signature.asInstanceOf[ClassSignature]
      declarations <- classSignature.declarations.toIterable
      methodSymbol <- declarations.symlinks
      methodSymbolInfo <- findSymbol(currentDocument, methodSymbol)
      asSeenFrom = createAsSeenFrom(symbolInfo)
      aliasMappings = aliasTypeMappings(declarations, currentDocument)
      context = Context(currentDocument, aliasMappings, asSeenFrom)
      if isOverridenMethod(methodSymbolInfo)(context)
      occ <- findDefOccurence(currentDocument, methodSymbol)
    } yield occ
    validMethods.headOption
  }

  private def symbolsAreEqual(
      symParent: String,
      symChild: String
  )(implicit context: Context) = {
    (symParent.desc, symChild.desc) match {
      case (Descriptor.TypeParameter(tp), Descriptor.TypeParameter(tc)) =>
        context.asSeenFrom.getOrElse(tp, tp) == context.aliasMappings
          .getOrElse(tc, tc)
      case (Descriptor.TypeParameter(tp), Descriptor.Type(tc)) =>
        context.asSeenFrom.getOrElse(tp, tp) == context.aliasMappings
          .getOrElse(tc, tc)
      case (Descriptor.Parameter(tp), Descriptor.Parameter(tc)) =>
        tp == tc
      case (Descriptor.Type(tp), Descriptor.Type(tc)) =>
        tp == tc
      case _ =>
        false
    }
  }

  private def typesAreEqual(
      typeParent: Type,
      typeChild: Type
  )(implicit context: Context) = {
    (typeParent, typeChild) match {
      case (tp: TypeRef, tc: TypeRef) =>
        symbolsAreEqual(tp.symbol, tc.symbol)
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

  private def signaturesEqual(
      parentSig: Signature,
      sig: Signature
  )(implicit context: Context): Boolean = {
    (parentSig, sig) match {
      case (sig1: MethodSignature, sig2: MethodSignature) =>
        val newContext = context.addAsSeenFrom(
          typeMappingFromMethodScope(
            sig1.typeParameters,
            sig2.typeParameters
          )
        )
        val returnTypesEqual =
          typesAreEqual(sig1.returnType, sig2.returnType)(newContext)
        val enrichedSig = enrichSignature(sig2, context.semanticDb)
        returnTypesEqual && paramsAreEqual(
          sig1.parameterLists,
          enrichedSig.parameterLists
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

  private def aliasTypeMappings(
      declarations: Scope,
      currentDocument: TextDocument
  ): Map[String, String] = {
    declarations.symlinks
      .map(sym => (sym, sym.desc))
      .flatMap {
        case (sym, Descriptor.Type(t)) =>
          findSymbol(currentDocument, sym).map(_.signature).flatMap {
            case ts: TypeSignature =>
              ts.lowerBound match {
                case tr: TypeRef =>
                  Some(t -> tr.symbol.desc.name.toString())
                case _ =>
                  None
              }
            case _ => None
          }
        case _ => None
      }
      .toMap
  }

  private case class Context(
      semanticDb: TextDocument,
      aliasMappings: Map[String, String],
      asSeenFrom: Map[String, String]
  ) {
    def addAsSeenFrom(asSeenFrom: Map[String, String]) = {
      this.copy(asSeenFrom = this.asSeenFrom ++ asSeenFrom)
    }
  }
}
