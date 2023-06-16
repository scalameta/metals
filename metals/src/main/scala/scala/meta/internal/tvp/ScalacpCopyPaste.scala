package scala.meta.internal.tvp

import java.{util => ju}

import scala.annotation.nowarn
import scala.collection.mutable
import scala.reflect.NameTransformer
import scala.tools.scalap.scalax.rules.scalasig._

import scala.meta.internal.scalacp._
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.Scala.{Descriptor => d}
import scala.meta.internal.semanticdb.Scala.{DisplayNames => dn}
import scala.meta.internal.semanticdb.Scala.{Names => n}
import scala.meta.internal.semanticdb.SymbolInformation.{Kind => k}
import scala.meta.internal.semanticdb.SymbolInformation.{Property => p}
import scala.meta.internal.semanticdb.{Language => l}
import scala.meta.internal.{semanticdb => s}

/**
 * This class contains copy-pasted code from scala.meta.internal.scalacp with minor changes.
 *
 * If the `Scalacp` class was not final then we could avoid this copy-pasting. Changes are
 * documented with "// scalacp deviation" comments.
 */
@nowarn("msg=parameter value node")
class ScalacpCopyPaste(node: ScalaSigNode) {
  lazy val symbolCache = new ju.HashMap[Symbol, String]
  implicit class XtensionSymbolSSymbol(sym: Symbol) {
    def toSemantic: String = {
      def uncached(sym: Symbol): String = {
        if (sym.isSemanticdbGlobal) Symbols.Global(sym.owner, sym.descriptor)
        else freshSymbol()
      }
      val ssym = symbolCache.get(sym)
      if (ssym != null) {
        ssym
      } else {
        val ssym = uncached(sym)
        symbolCache.put(sym, ssym)
        ssym
      }
    }
  }

  implicit class XtensionSymbolSSpec(sym: Symbol) {
    def isSemanticdbGlobal: Boolean = !isSemanticdbLocal
    def isSemanticdbLocal: Boolean = {
      val owner = sym.parent.getOrElse(NoSymbol)
      def definitelyGlobal = sym.isPackage
      def definitelyLocal =
        sym == NoSymbol ||
          (owner.isInstanceOf[MethodSymbol] && !sym.isParam) ||
          ((owner.isAlias || (owner.isType && owner.isDeferred)) && !sym.isParam) ||
          // NOTE: Scalap doesn't expose locals.
          // sym.isSelfParameter ||
          // sym.isLocalDummy ||
          sym.isRefinementClass ||
          sym.isAnonymousClass ||
          sym.isAnonymousFunction ||
          (sym.isInstanceOf[TypeSymbol] && sym.isExistential)
      def ownerLocal = sym.parent.map(_.isSemanticdbLocal).getOrElse(false)
      !definitelyGlobal && (definitelyLocal || ownerLocal)
    }
    def owner: String = {
      if (sym.isRootPackage) Symbols.None
      else if (sym.isEmptyPackage) Symbols.RootPackage
      else if (sym.isToplevelPackage) Symbols.RootPackage
      else {
        sym.parent match {
          case Some(NoSymbol) => ""
          case Some(parent) => parent.ssym
          case None => sys.error(s"unsupported symbol $sym")
        }
      }
    }
    def symbolName: String = {
      if (sym.isRootPackage) n.RootPackage.value
      else if (sym.isEmptyPackage) n.EmptyPackage.value
      else if (sym.isConstructor) n.Constructor.value
      else {
        def loop(value: String): String = {
          val i = value.lastIndexOf("$$")
          if (i > 0) loop(value.substring(i + 2))
          else NameTransformer.decode(value).stripSuffix(" ")
        }
        loop(sym.name)
      }
    }
    def descriptor: Descriptor = {
      sym match {
        case sym: SymbolInfoSymbol =>
          sym.kind match {
            case k.LOCAL | k.OBJECT | k.PACKAGE_OBJECT =>
              d.Term(symbolName)
            case k.METHOD if sym.isValMethod =>
              d.Term(symbolName)
            case k.METHOD | k.CONSTRUCTOR | k.MACRO =>
              val overloads = {
                val peers = sym.parent.get.semanticdbDecls.syms
                peers.filter {
                  case peer: MethodSymbol =>
                    peer.symbolName == sym.symbolName && !peer.isValMethod
                  case _ => false
                }
              }
              val disambiguator = {
                if (overloads.lengthCompare(1) == 0) "()"
                else {
                  val index = overloads.indexOf(sym)
                  if (index <= 0) "()"
                  else s"(+${index})"
                }
              }
              d.Method(symbolName, disambiguator)
            case k.TYPE | k.CLASS | k.TRAIT =>
              d.Type(symbolName)
            case k.PACKAGE =>
              d.Package(symbolName)
            case k.PARAMETER =>
              d.Parameter(symbolName)
            case k.TYPE_PARAMETER =>
              d.TypeParameter(symbolName)
            case skind =>
              sys.error(s"unsupported kind $skind for symbol $sym")
          }
        case sym: ExternalSymbol =>
          // scalacp deviation: since we are only interested in definitions (not
          // signatures) it's safe to emit `d.Package` for all term references.
          if (sym.entry.entryType == 9) d.Type(symbolName)
          else if (sym.entry.entryType == 10) d.Package(symbolName)
          else d.Type(symbolName)
        case NoSymbol =>
          d.None
      }
    }
    def semanticdbDecls: SemanticdbDecls = {
      val decls =
        sym.children.filter(decl => decl.isUseful && !decl.isTypeParam)
      SemanticdbDecls(decls.toList)
    }
  }

  implicit class XtensionSymbolsSSpec(syms: Seq[Symbol]) {
    def semanticdbDecls: SemanticdbDecls = {
      SemanticdbDecls(syms.filter(_.isUseful))
    }

    def sscope(linkMode: LinkMode): s.Scope = {
      linkMode match {
        case SymlinkChildren =>
          s.Scope(symlinks = syms.map(_.ssym))
        case HardlinkChildren =>
          syms.map(registerHardlink)
          val hardlinks = syms.map {
            case sym: SymbolInfoSymbol =>
              sym.toSymbolInformation(HardlinkChildren)
            case sym => sys.error(s"unsupported symbol $sym")
          }
          s.Scope(hardlinks = hardlinks)
      }
    }
  }

  case class SemanticdbDecls(syms: Seq[Symbol]) {
    def sscope(linkMode: LinkMode): s.Scope = {
      linkMode match {
        case SymlinkChildren =>
          val sbuf = List.newBuilder[String]
          syms.foreach { sym =>
            val ssym = sym.ssym
            sbuf += ssym
            if (sym.isUsefulField && sym.isMutable) {
              val setterSymbolName = ssym.desc.name + "_="
              val setterSym =
                Symbols.Global(ssym.owner, d.Method(setterSymbolName, "()"))
              sbuf += setterSym
            }
          }
          s.Scope(sbuf.result)
        case HardlinkChildren =>
          val sbuf = List.newBuilder[s.SymbolInformation]
          syms.foreach { sym =>
            registerHardlink(sym)
            val sinfo = sym match {
              case sym: SymbolInfoSymbol =>
                sym.toSymbolInformation(HardlinkChildren)
              case sym => sys.error(s"unsupported symbol $sym")
            }
            sbuf += sinfo
            if (sym.isUsefulField && sym.isMutable) {
              Synthetics.setterInfos(sinfo, HardlinkChildren).foreach(sbuf.+=)
            }
          }
          s.Scope(hardlinks = sbuf.result)
      }
    }
  }

  implicit class XtensionSymbol(sym: Symbol) {
    def ssym: String = sym.toSemantic
    def self: Type =
      sym match {
        case sym: ClassSymbol =>
          sym.selfType
            .map {
              case RefinedType(_, List(_, self)) => self
              case _ => NoType
            }
            .getOrElse(NoType)
        case _ =>
          NoType
      }
    def isRootPackage: Boolean = sym.path == "<root>"
    def isEmptyPackage: Boolean = sym.path == "<empty>"
    def isToplevelPackage: Boolean = sym.parent.isEmpty
    def isModuleClass: Boolean = sym.isInstanceOf[ClassSymbol] && sym.isModule
    def moduleClass: Symbol =
      sym match {
        case sym: SymbolInfoSymbol if sym.isModule =>
          sym.infoType match {
            case TypeRefType(_, moduleClass, _) => moduleClass
            case _ => NoSymbol
          }
        case _ =>
          NoSymbol
      }
    def isClass: Boolean = sym.isInstanceOf[ClassSymbol] && !sym.isModule
    def isObject: Boolean = sym.isInstanceOf[ObjectSymbol]
    def isType: Boolean = sym.isInstanceOf[TypeSymbol]
    def isAlias: Boolean = sym.isInstanceOf[AliasSymbol]
    def isMacro: Boolean = sym.isMethod && sym.hasFlag(0x00008000)
    def isConstructor: Boolean =
      sym.isMethod && (sym.name == "<init>" || sym.name == "$init$")
    def isPackageObject: Boolean = sym.name == "package"
    def isTypeParam: Boolean = sym.isType && sym.isParam
    def isAnonymousClass: Boolean = sym.name.contains("$anon")
    def isAnonymousFunction: Boolean = sym.name.contains("$anonfun")
    def isSyntheticConstructor: Boolean =
      sym match {
        case sym: SymbolInfoSymbol =>
          val owner = sym.symbolInfo.owner
          sym.isConstructor && (owner.isModuleClass || owner.isTrait)
        case _ =>
          false
      }
    def isLocalChild: Boolean = sym.name == "<local child>"
    def isExtensionMethod: Boolean = sym.name.contains("$extension")
    def isSyntheticValueClassCompanion: Boolean = {
      sym match {
        case sym: SymbolInfoSymbol =>
          if (sym.isModuleClass) {
            sym.infoType match {
              case ClassInfoType(_, List(TypeRefType(_, _, _))) =>
                sym.isSynthetic && sym.semanticdbDecls.syms.isEmpty
              case _ =>
                false
            }
          } else if (sym.isModule) {
            sym.moduleClass.isSyntheticValueClassCompanion
          } else {
            false
          }
        case _ =>
          false
      }
    }
    def isValMethod: Boolean = {
      sym match {
        case sym: SymbolInfoSymbol =>
          sym.kind.isMethod && {
            (sym.isAccessor && sym.isStable) ||
            (isUsefulField && !sym.isMutable)
          }
        case _ =>
          false
      }
    }
    def isScalacField: Boolean = {
      val isField = sym
        .isInstanceOf[MethodSymbol] && !sym.isMethod && !sym.isParam
      val isJavaDefined = sym.isJava
      isField && !isJavaDefined
    }
    def isUselessField: Boolean = {
      val peers = sym.parent.map(_.children.toList).getOrElse(Nil)
      val getter =
        peers.find(m => m.isAccessor && m.name == sym.name.stripSuffix(" "))
      sym.isScalacField && getter.nonEmpty
    }
    def isUsefulField: Boolean = {
      sym.isScalacField && !sym.isUselessField
    }
    def isSyntheticCaseAccessor: Boolean = {
      sym.isCaseAccessor && sym.name.contains("$")
    }
    def isRefinementClass: Boolean = {
      sym.name == "<refinement>"
    }
    def isUseless: Boolean = {
      sym == NoSymbol ||
      sym.isAnonymousClass ||
      sym.isSyntheticConstructor ||
      sym.isModuleClass ||
      sym.isLocalChild ||
      sym.isExtensionMethod ||
      sym.isSyntheticValueClassCompanion ||
      sym.isUselessField ||
      sym.isSyntheticCaseAccessor ||
      sym.isRefinementClass
    }
    def isUseful: Boolean = !sym.isUseless
    def isDefaultParameter: Boolean = {
      sym.hasFlag(0x02000000) && sym.isParam
    }
  }

  private var nextId = 0
  private def freshSymbol(): String = {
    val result = Symbols.Local(nextId.toString)
    nextId += 1
    result
  }

  lazy val hardlinks = new ju.HashSet[String]
  private def registerHardlink(sym: Symbol): Unit = {
    hardlinks.add(sym.ssym)
  }

  private val primaryCtors = mutable.Map[String, Int]()
  implicit class XtensionGSymbolSSymbolInformation(sym: SymbolInfoSymbol) {
    private def language: s.Language = {
      // NOTE: We have no way to figure out whether an external symbol
      // comes from Java or Scala. Moreover, we can't even find out
      // whether it's a package or an object. Therefore, we default to l.SCALA.
      l.SCALA
    }

    private[meta] def kind: s.SymbolInformation.Kind = {
      sym match {
        // NOTE: Scalacp doesn't care about self parameters
        // because they are local, i.e. not saved in scala signatures.
        // case _ if sym.isSelfParameter =>
        //   k.SELF_PARAMETER
        case sym: MethodSymbol if sym.isMethod =>
          if (sym.isConstructor) k.CONSTRUCTOR
          else if (sym.isMacro) k.MACRO
          // NOTE: Scalap doesn't expose locals.
          // else if (sym.isGetter && sym.isLazy && sym.isLocalToBlock) k.LOCAL
          else k.METHOD
        case _: ObjectSymbol | _: ClassSymbol if sym.isModule =>
          if (sym.isPackage) k.PACKAGE
          else if (sym.isPackageObject) k.PACKAGE_OBJECT
          else k.OBJECT
        case sym: MethodSymbol =>
          // NOTE: This is craziness. In scalap, parameters, val and vars
          // are also modelled with method symbols.
          if (sym.isParam) k.PARAMETER
          // NOTE: Scalap doesn't expose locals.
          // else if (gsym.isLocalToBlock) k.LOCAL
          // NOTE: Scalap doesn't expose JAVA_ENUM.
          else if (sym.isJava /* || sym.isJavaEnum */ ) k.FIELD
          else k.METHOD
        case sym: ClassSymbol if !sym.isModule =>
          if (sym.isTrait && sym.isJava) k.INTERFACE
          else if (sym.isTrait) k.TRAIT
          else k.CLASS
        case _: TypeSymbol | _: AliasSymbol =>
          if (sym.isParam) k.TYPE_PARAMETER
          else k.TYPE
        case _ =>
          sys.error(s"unsupported symbol $sym")
      }
    }

    private[meta] def properties: Int = {
      var flags = 0
      def flip(sprop: s.SymbolInformation.Property) = flags |= sprop.value
      def isAbstractClass = sym.isClass && sym.isAbstract && !sym.isTrait
      def isAbstractMethod = sym.isMethod && sym.isDeferred
      def isAbstractType = sym.isType && !sym.isParam && sym.isDeferred
      if (sym.isPackage) {
        ()
      } else if (sym.isJava) {
        if (isAbstractClass || kind.isInterface || isAbstractMethod)
          flip(p.ABSTRACT)
        // NOTE: Scalap doesn't expose JAVA_ENUM.
        if (sym.isFinal /* || sym.isJavaEnum */ ) flip(p.FINAL)
        // NOTE: Scalap doesn't expose JAVA_ENUM.
        // if (sym.isJavaEnum) flip(p.ENUM)
        if (sym.isStatic) flip(p.STATIC)
        ???
      } else {
        if (isAbstractClass || isAbstractMethod || isAbstractType)
          flip(p.ABSTRACT)
        if (sym.isFinal || sym.isModule) flip(p.FINAL)
        if (sym.isSealed) flip(p.SEALED)
        if (sym.isImplicit) flip(p.IMPLICIT)
        if (sym.isLazy) flip(p.LAZY)
        if (sym.isCase && (sym.isClass || sym.isModule)) flip(p.CASE)
        if (sym.isType && sym.isCovariant) flip(p.COVARIANT)
        if (sym.isType && sym.isContravariant) flip(p.CONTRAVARIANT)
        // NOTE: Scalap doesn't expose locals.
        if (/*kind.isLocal ||*/ sym.isUsefulField) {
          if (sym.isMutable) flip(p.VAR)
          else flip(p.VAL)
        }
        if (sym.isAccessor) {
          if (sym.isStable) flip(p.VAL)
          else flip(p.VAR)
        }
        if (sym.isParam) {
          sym.parent.foreach {
            case parent: SymbolInfoSymbol =>
              if ((parent.properties & p.PRIMARY.value) != 0) {
                parent.parent.foreach { grandParent =>
                  val classMembers = grandParent.children
                  val accessor =
                    classMembers.find(m =>
                      m.isParamAccessor && m.symbolName == sym.symbolName
                    )
                  accessor.foreach { accessor =>
                    val isStable = {
                      if (accessor.isMethod) accessor.isStable
                      else !accessor.isMutable
                    }
                    if (!isStable) flip(p.VAR)
                    else if (accessor.isMethod) flip(p.VAL)
                    else ()
                  }
                }
              }
            case _ =>
              ()
          }
        }
        if (sym.isConstructor) {
          val primaryIndex =
            primaryCtors.getOrElseUpdate(sym.path, sym.entry.index)
          if (sym.entry.index == primaryIndex) flip(p.PRIMARY)
        }
        if (sym.isDefaultParameter) flip(p.DEFAULT)
      }
      flags
    }

    private def displayName: String = {
      if (sym.isRootPackage) dn.RootPackage
      else if (sym.isEmptyPackage) dn.EmptyPackage
      else if (sym.isConstructor) dn.Constructor
      else if (sym.name.startsWith("_$")) dn.Anonymous
      else if (sym.isPackageObject) sym.ssym.owner.desc.value
      else sym.symbolName
    }

    private def sig(linkMode: LinkMode): s.Signature =
      s.NoSignature // scalacp deviation

    private def annotations: List[s.Annotation] = {
      Nil // scalacp deviation
    }

    private def access: s.Access = {
      kind match {
        case k.LOCAL | k.PARAMETER | k.SELF_PARAMETER | k.TYPE_PARAMETER |
            k.PACKAGE | k.PACKAGE_OBJECT =>
          s.NoAccess
        case _ =>
          sym.symbolInfo.privateWithin match {
            case None =>
              if (sym.isPrivate && sym.isLocal) s.PrivateThisAccess()
              else if (sym.isPrivate) s.PrivateAccess()
              else if (sym.isProtected && sym.isLocal) s.ProtectedThisAccess()
              else if (sym.isProtected) s.ProtectedAccess()
              else s.PublicAccess()
            case Some(privateWithin: Symbol) =>
              val ssym = privateWithin.ssym
              if (sym.isProtected) s.ProtectedWithinAccess(ssym)
              else s.PrivateWithinAccess(ssym)
            case Some(other) =>
              sys.error(s"unsupported privateWithin: ${other.getClass} $other")
          }
      }
    }

    def toSymbolInformation(linkMode: LinkMode): s.SymbolInformation = {
      s.SymbolInformation(
        symbol = sym.ssym,
        language = language,
        kind = kind,
        properties = properties,
        displayName = displayName,
        signature = sig(linkMode),
        annotations = annotations,
        access = access,
      )
    }
  }

}
