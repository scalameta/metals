package scala.meta.internal.metals
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.TextDocumentPositionParams
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.mtags.{Symbol => MSymbol}
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath
import java.nio.file.Paths
import scala.meta.internal.semanticdb.TextDocuments
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.ClassSignature
import scala.meta.internal.semanticdb.TypeRef
import scala.meta.internal.semanticdb.Signature
import scala.meta.internal.semanticdb.TextDocument
import java.util.concurrent.ConcurrentHashMap
import java.nio.file.Path
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.semanticdb.MethodSignature
import scala.meta.internal.semanticdb.Type
import scala.meta.internal.semanticdb.Scope
import scala.meta.internal.semanticdb.ValueSignature
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.Scala.Descriptor
import scala.util.Try
import scala.util.Success
import scala.meta.internal.mtags.GlobalSymbolIndex

final class ImplementationProvider(
    semanticdbs: Semanticdbs,
    workspace: AbsolutePath,
    index: GlobalSymbolIndex,
    buffer: Buffers,
    definitionProvider: DefinitionProvider
) {
  private val implementationsInPath =
    new ConcurrentHashMap[Path, Map[String, Set[ClassLocation]]]

  def clear(): Unit = {
    implementationsInPath.clear()
  }

  def onDelete(path: Path): Unit = {
    implementationsInPath.remove(path)
  }

  def onChange(docs: TextDocuments, path: Path): Unit = {
    implementationsInPath.compute(
      path, { (_, _) =>
        computeInheritance(docs)
      }
    )
  }

  def implementations(params: TextDocumentPositionParams): List[Location] = {
    val source = params.getTextDocument.getUri.toAbsolutePath

    def findSemanticdb(fileSource: AbsolutePath) =
      semanticdbs
        .textDocument(fileSource)
        .documentIncludingStale
        .toList

    def findSemanticDbForSymbol(symbol: String): List[TextDocument] = {
      for {
        symbolDefinition <- index.definition(MSymbol(symbol)).toList
        document <- findSemanticdb(symbolDefinition.path)
      } yield {
        document
      }
    }

    for {
      currentDoc <- findSemanticdb(source)
      positionOccurrence = definitionProvider.positionOccurrence(
        source,
        params,
        currentDoc
      )
      occ <- positionOccurrence.occurrence.toList
      definitionDocument <- if (currentDoc.definesSymbol(occ.symbol))
        List(currentDoc)
      else findSemanticDbForSymbol(occ.symbol).toList
      plainSym <- findSymbol(definitionDocument, occ.symbol).toList
      sym = plainSym.copy(
        signature = enrichSignature(plainSym.signature, definitionDocument)
      )
      classSym <- classFromSymbol(sym, definitionDocument).toList
      (file, locations) <- findImplementation(classSym.symbol).groupBy(_.file)
      fileSource = AbsolutePath(file)
      doc <- findSemanticdb(fileSource)
      distance = TokenEditDistance.fromBuffer(fileSource, doc.text, buffer)
      impl <- locations
      implReal = impl.toRealNames(classSym, translateKey = true)
      implOccurence <- if (isClassLike(sym)) findDefOccurence(doc, impl.symbol)
      else findImplementationMethod(sym, classSym, implReal, doc)
      range <- implOccurence.range
      revised <- distance.toRevised(range.toLSP)
      uri = impl.file.toUri.toString
    } yield new Location(uri, revised)
  }

  private def classFromSymbol(
      info: SymbolInformation,
      semanticDb: TextDocument
  ): Option[SymbolInformation] = {
    if (isClassLike(info)) {
      Some(info)
    } else {
      val definitionsFound = semanticDb.symbols.filter { defn =>
        info.symbol.startsWith(defn.symbol) && isClassLike(defn)
      }
      if (definitionsFound.isEmpty) {
        None
      } else {
        Some(definitionsFound.maxBy(_.symbol.length()))
      }
    }
  }

  private def findDefOccurence(
      semanticDb: TextDocument,
      symbol: String
  ): Option[SymbolOccurrence] = {
    semanticDb.occurrences.find(
      occ => occ.role.isDefinition && occ.symbol == symbol
    )
  }

  private def findSymbol(
      semanticDb: TextDocument,
      symbol: String
  ): Option[SymbolInformation] = {
    semanticDb.symbols.find(
      sym => sym.symbol == symbol
    )
  }

  private def isClassLike(info: SymbolInformation) =
    info.isObject || info.isClass || info.isTrait

  private def enrichSignature(
      signature: Signature,
      semanticDb: TextDocument
  ): Signature = {
    signature match {
      case sig: MethodSignature =>
        val allParams = sig.parameterLists.map { scope =>
          val hardlinks = scope.symlinks.flatMap { sym =>
            findSymbol(semanticDb, sym)
          }
          scope.copy(hardlinks = hardlinks)
        }
        sig.copy(parameterLists = allParams)
      case _ => signature
    }
  }

  private def findImplementationMethod(
      parentSymbol: SymbolInformation,
      parentClassSymbol: SymbolInformation,
      classSymbol: ClassLocation,
      semanticDb: TextDocument
  ): Option[SymbolOccurrence] = {

    def symbolsEqual(
        symParent: String,
        symChild: String,
        typeMapping: Map[String, String]
    ) = {
      (symParent.desc, symChild.desc) match {
        case (Descriptor.TypeParameter(tp), Descriptor.TypeParameter(tc)) =>
          typeMapping.getOrElse(tp, tp) == tc
        case (Descriptor.TypeParameter(tp), Descriptor.Type(tc)) =>
          typeMapping.getOrElse(tp, tp) == tc
        case (Descriptor.Parameter(tp), Descriptor.Parameter(tc)) =>
          tp == tc
        case (Descriptor.Type(tp), Descriptor.Type(tc)) =>
          tp == tc
        case _ =>
          false
      }
    }

    def typesEqual(
        typeParent: Type,
        typeChild: Type,
        typeMapping: Map[String, String]
    ) = {
      (typeParent, typeChild) match {
        case (tp: TypeRef, tc: TypeRef) =>
          symbolsEqual(tp.symbol, tc.symbol, typeMapping)
        case _ => false
      }
    }

    def paramsEqual(
        scopesParent: Seq[Scope],
        scopesChild: Seq[Scope],
        typeMapping: Map[String, String]
    ): Boolean = {
      scopesParent.size == scopesChild.size &&
      scopesParent.zip(scopesChild).forall {
        case (scopePar, scopeChil) =>
          scopePar.hardlinks.size == scopeChil.hardlinks.size && scopePar.hardlinks
            .zip(scopeChil.hardlinks)
            .forall {
              case (linkPar, linkChil) =>
                signaturesEqual(
                  linkPar.signature,
                  linkChil.signature,
                  typeMapping
                )
            }
      }
    }

    def typeMappingFromScope(
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

    def signaturesEqual(
        parentSig: Signature,
        sig: Signature,
        typeMapping: Map[String, String]
    ): Boolean = {
      (parentSig, sig) match {
        case (sig1: MethodSignature, sig2: MethodSignature) =>
          val methodTypeMapping =
            typeMapping ++ typeMappingFromScope(
              sig1.typeParameters,
              sig2.typeParameters
            )
          def returnTypesEqual =
            typesEqual(sig1.returnType, sig2.returnType, methodTypeMapping)
          val enrichedSig =
            enrichSignature(sig2, semanticDb).asInstanceOf[MethodSignature]
          returnTypesEqual && paramsEqual(
            sig1.parameterLists,
            enrichedSig.parameterLists,
            methodTypeMapping
          )
        case (v1: ValueSignature, v2: ValueSignature) =>
          typesEqual(v1.tpe, v2.tpe, typeMapping)
        case _ => false
      }
    }

    val classInfo = findSymbol(semanticDb, classSymbol.symbol)

    val validMethods = for {
      info <- classInfo.toList
      asSeenFrom = classSymbol
        .toRealNames(info, translateKey = false)
        .asSeenFrom
        .getOrElse(Map.empty)
      if info.signature.isInstanceOf[ClassSignature]
      clsSig = info.signature.asInstanceOf[ClassSignature]
      decl <- clsSig.declarations.toList
      methodSym <- decl.symlinks
      method <- semanticDb.symbols.find(inf => inf.symbol == methodSym)
      if (method.kind.isField || method.kind.isMethod) &&
        method.displayName == parentSymbol.displayName &&
        signaturesEqual(parentSymbol.signature, method.signature, asSeenFrom)
      occ <- findDefOccurence(semanticDb, method.symbol)
    } yield occ
    validMethods.headOption
  }

  private def findImplementation(symbol: String): Set[ClassLocation] = {
    val directImpl = for {
      (_, symbols) <- implementationsInPath.asScala
      symbolImpls <- symbols.get(symbol).toList
      impl <- symbolImpls
    } yield impl
    directImpl.toSet ++ directImpl
      .flatMap { loc =>
        findImplementation(loc.symbol).map(_.translateAsSeenFrom(loc))
      }
  }

  private def computeInheritance(
      docs: TextDocuments
  ): Map[String, Set[ClassLocation]] = {
    val allParents = for {
      doc <- docs.documents
      thisSymbol <- doc.symbols
      parent <- parentsFromSignature(
        thisSymbol.symbol,
        thisSymbol.signature,
        doc
      ).toList
    } yield parent

    allParents.groupBy(_._1).map {
      case (symbol, locations) =>
        symbol -> locations.map(_._2).toSet
    }
  }

  private def parentsFromSignature(
      symbol: String,
      signature: Signature,
      doc: TextDocument
  ): Seq[(String, ClassLocation)] = {

    def calculateAsSeenFrom(t: TypeRef, classSig: ClassSignature) = {
      t.typeArguments.zipWithIndex.flatMap {
        case (arg: TypeRef, ind) =>
          // create mapping dependent on order - this way we don't need parent information here
          classSig.typeParameters match {
            case Some(sc) =>
              val indInClass = sc.symlinks.indexOf(arg.symbol)
              if (indInClass >= 0)
                Some(s"$ind" -> s"$indInClass")
              else
                Some(s"$ind" -> arg.symbol.desc.name.toString())
            case None => None
          }

        case other => None
      }.toMap
    }

    val filePath = workspace.toNIO.resolve(Paths.get(doc.uri))

    signature match {
      case classSig: ClassSignature =>
        val allLocations = classSig.parents.collect {
          case t: TypeRef =>
            val asSeenFrom = calculateAsSeenFrom(t, classSig)
            val loc = ClassLocation(symbol, filePath, asSeenFrom)
            t.symbol -> loc
        }
        allLocations
      case _ =>
        Seq.empty
    }
  }

  private case class ClassLocation(
      symbol: String,
      file: Path,
      asSeenFrom: Option[Map[String, String]]
  ) {

    def translateAsSeenFrom(other: ClassLocation): ClassLocation = {
      val newASF = other.asSeenFrom match {
        case None => other.asSeenFrom
        case Some(parentASF) =>
          asSeenFrom match {
            case None => Some(parentASF)
            case Some(childASF) =>
              Some(parentASF.map {
                case (key, value) => key -> childASF.getOrElse(value, value)
              })
          }
      }
      this.copy(asSeenFrom = newASF)
    }

    // Translate postion based names to real names in the class
    def toRealNames(
        classInfo: SymbolInformation,
        translateKey: Boolean
    ): ClassLocation = {
      classInfo.signature match {
        case clsSig: ClassSignature =>
          val newASF = for {
            typeScope <- clsSig.typeParameters.toList
            asf <- asSeenFrom.toList
            (key, value) <- asf
          } yield {
            val translated = if (translateKey) key else value
            Try(translated.toInt) match {
              case Success(ind) if typeScope.symlinks.size > ind =>
                if (translateKey)
                  typeScope.symlinks(ind).desc.name.toString() -> value
                else
                  key -> typeScope.symlinks(ind).desc.name.toString()
              case _ =>
                key -> value
            }
          }
          ClassLocation(symbol, file, newASF.toMap)
        case other => this
      }
    }
  }

  private object ClassLocation {
    def apply(
        symbol: String,
        file: Path,
        asSeenFrom: Map[String, String]
    ): ClassLocation = {
      if (asSeenFrom.isEmpty) {
        ClassLocation(symbol, file, asSeenFrom = None)
      } else {
        ClassLocation(symbol, file, asSeenFrom = Some(asSeenFrom))
      }
    }
  }
}
