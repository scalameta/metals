package scala.meta.internal.metals.mbt

import scala.collection.mutable
import scala.collection.mutable.Buffer
import scala.concurrent.duration.FiniteDuration

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ReferencesResult
import scala.meta.internal.metals.SymbolAlternatives
import scala.meta.internal.metals.TaskProgress
import scala.meta.internal.metals.Time
import scala.meta.internal.metals.Timer
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.TypeRef
import scala.meta.internal.{semanticdb => s}
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.jsonrpc.messages.{Either => JEither}
import org.eclipse.{lsp4j => l}

final class MbtProtobufReferenceProvider(
    mbt: MbtWorkspaceSymbolProvider,
    buffers: Buffers,
    languageClient: MetalsLanguageClient,
    time: Time,
    groupSize: Int,
    timeout: FiniteDuration,
) {

  def implementations(
      path: AbsolutePath,
      requestDoc: s.TextDocument,
      enclosingOccurrences: Seq[s.SymbolOccurrence],
      taskProgress: TaskProgress,
      timer: Timer,
      indexDocuments: Seq[AbsolutePath] => s.TextDocuments,
      commonPrefixLength: (AbsolutePath, AbsolutePath) => Int,
  ): (List[l.Location], Option[String]) = {
    if (enclosingOccurrences.isEmpty) return (Nil, None)

    val protoSymbols = enclosingOccurrences.map(_.symbol)
    val protoJavaResult =
      ProtoJavaSymbolMapper.protoToJavaSymbols(path, protoSymbols, buffers)

    if (protoJavaResult.javaPackage.isEmpty) {
      scribe.debug("proto implementations: no java_package found")
      return (Nil, None)
    }

    val isService = requestDoc.symbols.exists { info =>
      protoSymbols.contains(info.symbol) && info.kind.isInterface
    }
    val isRpc = requestDoc.symbols.exists { info =>
      protoSymbols.contains(info.symbol) && info.kind.isMethod &&
      requestDoc.symbols.exists(p =>
        p.symbol == Symbol(info.symbol).owner.value && p.kind.isInterface
      )
    }

    if (!isService && !isRpc) {
      scribe.debug("proto implementations: not a service or RPC")
      return (Nil, None)
    }

    val implBaseSymbols: Seq[String] =
      protoJavaResult.symbolMapping.keySet.toSeq.filter { javaSymbol =>
        ProtoJavaSymbolMapper.isGrpcStubClassSymbol(javaSymbol) &&
        !Symbol(javaSymbol).isMethod
      }

    if (implBaseSymbols.isEmpty) {
      scribe.debug("proto implementations: no ImplBase symbols found")
      return (Nil, None)
    }

    val javaMethodNames: Set[String] =
      if (isRpc) protoJavaResult.javaAccessorMethods.toSet
      else Set.empty

    scribe.info(
      s"proto implementations: searching for ImplBase extends with symbols: ${implBaseSymbols.take(3).mkString(", ")}..."
    )

    val candidates = mbt
      .possibleReferences(
        MbtPossibleReferencesParams(
          implementations = implBaseSymbols,
          references = implBaseSymbols,
        )
      )
      .iterator
      .filterNot(p => p.isProtoFilename || p.isScalaFilename)
      .distinct
      .toSeq
      .sortBy(c => -commonPrefixLength(path, c))

    scribe.info(
      s"proto implementations: found ${candidates.size} candidate files"
    )

    val result = mutable.ListBuffer.empty[l.Location]
    val isVisitedURI = mutable.Set.empty[String]
    val implBaseClassSymbols = implBaseSymbols.toSet

    def visitDoc(doc: s.TextDocument): Unit = {
      if (isVisitedURI.contains(doc.uri)) return
      isVisitedURI += doc.uri

      if (!doc.language.isJava) return

      lazy val occurrencesForSymbol = doc.occurrences.groupBy(_.symbol)
      val addedLocations = mutable.Set.empty[String]

      for {
        info <- doc.symbols.iterator
      } {
        if (isService) {
          if (info.kind.isClass) {
            val extendsImplBaseViaOverrides = info.overriddenSymbols.exists {
              parentSymbol =>
                implBaseClassSymbols.contains(parentSymbol) ||
                ProtoJavaSymbolMapper.isGrpcStubClassSymbol(parentSymbol)
            }

            val extendsImplBaseViaSignature = info.signature match {
              case s.ClassSignature(_, parents, _, _) =>
                parents.collect { case TypeRef(_, sym, _) => sym }.exists {
                  parentSymbol =>
                    implBaseClassSymbols.contains(parentSymbol) ||
                    ProtoJavaSymbolMapper.isGrpcStubClassSymbol(parentSymbol)
                }
              case _ => false
            }

            if (extendsImplBaseViaOverrides || extendsImplBaseViaSignature) {
              for {
                occ <- occurrencesForSymbol.getOrElse(info.symbol, Nil)
                if occ.role.isDefinition
                range <- occ.range
              } {
                val locKey =
                  s"${doc.uri}:${range.startLine}:${range.startCharacter}"
                if (!addedLocations.contains(locKey)) {
                  addedLocations += locKey
                  result += range.toLocation(doc.uri)
                }
              }
            }
          }

          if (info.kind.isMethod && info.symbol.startsWith("local")) {
            val overridesImplBaseMethod = info.overriddenSymbols.exists {
              overriddenMethod =>
                implBaseClassSymbols.exists { implBaseSymbol =>
                  overriddenMethod.startsWith(implBaseSymbol)
                }
            }

            if (overridesImplBaseMethod) {
              for {
                occ <- occurrencesForSymbol.getOrElse(info.symbol, Nil)
                if occ.role.isDefinition
                range <- occ.range
              } {
                val locKey =
                  s"${doc.uri}:${range.startLine}:${range.startCharacter}"
                if (!addedLocations.contains(locKey)) {
                  addedLocations += locKey
                  result += range.toLocation(doc.uri)
                }
              }
            }
          }
        } else if (isRpc) {
          if (info.kind.isMethod) {
            val methodName = info.displayName
            val isTargetMethod = javaMethodNames.contains(methodName)
            val overridesImplBase =
              isTargetMethod && info.overriddenSymbols.exists { overriddenSym =>
                ProtoJavaSymbolMapper.isGrpcStubMethodSymbol(overriddenSym)
              }
            val matchesByName = isTargetMethod && {
              val ownerSymbol = Symbol(info.symbol).owner.value
              doc.symbols
                .find(_.symbol == ownerSymbol)
                .exists { classInfo =>
                  classInfo.signature match {
                    case s.ClassSignature(_, parents, _, _) =>
                      parents.exists {
                        case TypeRef(_, parentSymbol, _) =>
                          ProtoJavaSymbolMapper.isGrpcStubClassSymbol(
                            parentSymbol
                          )
                        case _ => false
                      }
                    case _ => false
                  }
                }
            }

            if (overridesImplBase || matchesByName) {
              for {
                occ <- occurrencesForSymbol.getOrElse(info.symbol, Nil)
                if occ.role.isDefinition
                range <- occ.range
              } {
                result += range.toLocation(doc.uri)
              }
            }
          }
        }
      }
    }

    var processedCandidates = 0
    val totalCandidates = candidates.size

    for {
      paths <- candidates.iterator.grouped(groupSize)
      if !timer.hasElapsed(timeout)
      doc <- indexDocuments(paths).documents
    } {
      visitDoc(doc)
      processedCandidates += 1
      taskProgress.update(
        processedCandidates,
        totalCandidates,
        Some(s"Processing ${doc.uri.toString.split("/").last}"),
      )
    }

    if (timer.hasElapsed(timeout)) {
      scribe.warn(
        s"proto implementations: timed out at $processedCandidates/$totalCandidates"
      )
    }

    scribe.info(
      s"proto implementations: found ${result.size} results in $timer"
    )

    (result.toList, Some(protoJavaResult.javaPackage))
  }

  def references(
      path: AbsolutePath,
      timer: Timer,
      params: ReferenceParams,
      requestDoc: s.TextDocument,
      enclosingOccurrences: Seq[s.SymbolOccurrence],
      toQuerySymbols: Seq[String],
      taskProgress: TaskProgress,
      indexDocuments: Seq[AbsolutePath] => s.TextDocuments,
  ): List[ReferencesResult] = {
    val token = params.getPartialResultToken()
    val protoSymbols = enclosingOccurrences.map(_.symbol)
    val protoJavaResult = {
      val result =
        ProtoJavaSymbolMapper.protoToJavaSymbols(path, protoSymbols, buffers)
      if (result.javaPackage.nonEmpty) Some(result) else None
    }

    val protoFilteredMapping = protoJavaResult.map { result =>
      result.symbolMapping.filter { case (javaSymbol, protoSymbol) =>
        val isStubClassSymbol =
          ProtoJavaSymbolMapper.isGrpcStubClassSymbol(
            javaSymbol
          ) && !Symbol(javaSymbol).isMethod
        val isRpcSymbol = Symbol(protoSymbol).isMethod
        !(isStubClassSymbol && isRpcSymbol)
      }
    }

    val javaMatchingOccurrenceBaseMap: Map[String, String] =
      (for {
        symbol <- toQuerySymbols.iterator
        original = Symbol(symbol)
        expanded = SymbolAlternatives.expand(original).filter { alt =>
          val altSymbol = Symbol(alt)
          altSymbol.displayName == original.displayName || alt == symbol
        }
        alternative <- expanded
      } yield alternative -> symbol).toMap

    val javaMatchingOccurrence: Map[String, String] =
      javaMatchingOccurrenceBaseMap ++ protoFilteredMapping.getOrElse(Map.empty)

    val overrideToProtoSymbol: Map[String, String] = protoJavaResult match {
      case Some(result) =>
        result.symbolMapping.filter { case (javaSymbol, _) =>
          ProtoJavaSymbolMapper.isGrpcStubMethodSymbol(javaSymbol)
        }
      case None => Map.empty
    }

    val enclosingGlobalOccurrences =
      toQuerySymbols.filter(sym => sym.isGlobal)

    val protoJavaSymbolsToQuery: Seq[String] = protoJavaResult match {
      case Some(result) =>
        val javaPackagePrefix =
          result.javaPackage.replace('.', '/') + "/"
        val classSymbols = toQuerySymbols.flatMap { protoSymbol =>
          val sym = Symbol(protoSymbol)
          if (sym.isType) {
            Some(
              ProtoJavaSymbolMapper.convertProtoSymbolToJava(
                protoSymbol,
                result.protoPackage,
                result.javaPackage,
              )
            )
          } else if (sym.isMethod || sym.isTerm) {
            Some(
              ProtoJavaSymbolMapper.convertProtoSymbolToJava(
                sym.owner.value,
                result.protoPackage,
                result.javaPackage,
              )
            )
          } else None
        }
        val methodSymbols =
          result.javaAccessorMethods.distinct.flatMap { methodName =>
            Seq(
              s"${javaPackagePrefix}X#$methodName().",
              s"X#$methodName().",
            )
          }
        val grpcImplementationSymbols =
          result.symbolMapping.keySet.toSeq.filter { javaSymbol =>
            ProtoJavaSymbolMapper.isGrpcStubClassSymbol(javaSymbol) &&
            !Symbol(javaSymbol).isMethod
          }
        (classSymbols ++ methodSymbols ++ grpcImplementationSymbols).distinct
      case None => Seq.empty
    }

    val externalDocumentCandidates: Iterable[AbsolutePath] = {
      val hasGlobalSymbols = enclosingGlobalOccurrences.nonEmpty
      val hasProtoJavaSymbols = protoJavaSymbolsToQuery.nonEmpty

      if (hasGlobalSymbols || hasProtoJavaSymbols) {
        val protoRefs =
          if (hasGlobalSymbols)
            mbt.possibleReferences(
              MbtPossibleReferencesParams(references = toQuerySymbols)
            )
          else Iterable.empty

        val javaRefs =
          if (hasProtoJavaSymbols)
            mbt.possibleReferences(
              MbtPossibleReferencesParams(
                references = protoJavaSymbolsToQuery,
                implementations = protoJavaSymbolsToQuery
                  .filter(s => ProtoJavaSymbolMapper.isGrpcStubClassSymbol(s)),
              )
            )
          else Iterable.empty

        (protoRefs ++ javaRefs).toSeq.distinct
      } else {
        Nil
      }
    }

    val candidatesList = externalDocumentCandidates.iterator
      .filterNot(_.isScalaFilename)
      .distinct
      .toSeq
    val totalCandidates = candidatesList.size
    scribe.info(
      s"references: found $totalCandidates external document candidates in $timer"
    )
    val referenceResults = toQuerySymbols.iterator
      .map(occ => occ -> Buffer.empty[l.Location])
      .toMap
    var processedCandidates = 0

    def processDoc(doc: s.TextDocument): Unit = {
      val isProtoDoc = doc.uri.toAbsolutePath.isProtoFilename
      val symbolInfos: Map[String, s.SymbolInformation] =
        doc.symbols.iterator.map(info => info.symbol -> info).toMap
      val docLines: Array[String] =
        if (isProtoDoc)
          doc.uri.toAbsolutePath
            .toInputFromBuffers(buffers)
            .text
            .linesIterator
            .toArray
        else
          Array.empty[String]

      def protobufTokenAt(range: s.Range): Option[String] = {
        if (!isProtoDoc) None
        else {
          val line = range.startLine
          if (
            line < 0 || line >= docLines.length || range.startCharacter < 0 ||
            range.endCharacter < range.startCharacter
          ) None
          else {
            val lineText = docLines(line)
            val end =
              math.min(range.endCharacter, lineText.length)
            val start =
              math.min(range.startCharacter, end)
            Some(lineText.substring(start, end))
          }
        }
      }

      def addLocation(symbol: String, location: l.Location): Unit = {
        referenceResults.get(symbol).foreach { locations =>
          if (token == null) {
            locations += location
          } else {
            languageClient.notifyProgress(
              new l.ProgressParams(token, JEither.forRight(location))
            )
          }
        }
      }

      def protoJavaMatching(occ: s.SymbolOccurrence): Option[String] = {
        if (occ.symbol.contains("#")) {
          (for {
            result <- protoJavaResult.iterator
            methodName <- result.javaAccessorMethods.iterator
            if occ.symbol.contains(s"#$methodName#")
            (_, protoSymbol) <- result.symbolMapping.find {
              case (javaSymbol, _) =>
                javaSymbol.contains(s"#$methodName().") ||
                javaSymbol.contains(s"#$methodName#")
            }
          } yield protoSymbol).nextOption()
        } else {
          None
        }
      }

      for {
        occ <- doc.occurrences
        if occ.symbol.isGlobal || doc.eq(requestDoc)
        range <- occ.range.toList
        matchSymbol <- {
          if (doc.language.isJava) {
            javaMatchingOccurrence
              .get(occ.symbol)
              .orElse(protoJavaMatching(occ))
          } else {
            javaMatchingOccurrence.get(occ.symbol)
          }
        }
        if !isProtoDoc || protobufTokenAt(range)
          .contains(Symbol(matchSymbol).displayName)
        if occ.role.isReference || occ.symbol == matchSymbol
      } {
        val location = range.toLocation(doc.uri)
        if (token == null) {
          referenceResults.get(matchSymbol).foreach(_ += location)
        } else {
          languageClient.notifyProgress(
            new l.ProgressParams(token, JEither.forRight(location))
          )
        }
      }

      if (doc.language.isJava && overrideToProtoSymbol.nonEmpty) {
        for {
          occ <- doc.occurrences
          if occ.role.isDefinition
          range <- occ.range.toList
          info <- symbolInfos.get(occ.symbol)
          if info.kind.isMethod
          overriddenSym <- info.overriddenSymbols
          protoSymbol <- overrideToProtoSymbol.get(overriddenSym)
        } {
          addLocation(protoSymbol, range.toLocation(doc.uri))
        }
      }
    }

    processDoc(requestDoc)

    for {
      candidates <- candidatesList.iterator.grouped(groupSize)
      if !timer.hasElapsed(timeout)
    } {
      val docTimer = new Timer(time)
      val docs = indexDocuments(candidates).documents
      scribe.info(
        s"references: indexed ${candidates.length} candidates in $docTimer"
      )
      docs.foreach(processDoc)
      processedCandidates += candidates.length
      taskProgress.update(processedCandidates, totalCandidates)
    }
    if (timer.hasElapsed(timeout)) {
      taskProgress.update(
        processedCandidates,
        totalCandidates,
        Some("Time out analyzing candidate files"),
      )
    }
    val resultCount = referenceResults.valuesIterator.map(_.size).sum
    scribe.info(
      s"references: found $resultCount reference results in $timer"
    )
    referenceResults.iterator.map { case (symbol, locations) =>
      ReferencesResult(symbol, locations.toSeq)
    }.toList
  }
}
