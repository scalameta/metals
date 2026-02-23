package scala.meta.internal.metals

import java.{util => ju}

import scala.util.control.NonFatal

import scala.meta.internal.metals.Configs.DefinitionProviderConfig
import scala.meta.internal.metals.Configs.ProtobufLspConfig
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.mbt.MbtWorkspaceSymbolProvider
import scala.meta.internal.metals.mbt.ProtoJavaVirtualFile
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.{semanticdb => s}
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.Location

final class DefinitionProviderProtobufSupport(
    workspace: AbsolutePath,
    buffers: Buffers,
    mbt: MbtWorkspaceSymbolProvider,
    definitionProviders: () => DefinitionProviderConfig,
    protobufLspConfig: () => ProtobufLspConfig,
) {

  def hasProtoJavaLocation(res: DefinitionResult): Boolean =
    if (!protobufLspConfig().definition) false
    else {
      val it = res.locations.iterator()
      var found = false
      while (!found && it.hasNext) {
        found = ProtoJavaVirtualFile.isProtoJavaUri(it.next().getUri())
      }
      found
    }

  def enhanceWithProtobufDefinition(
      result: DefinitionResult
  ): DefinitionResult = try {
    if (!definitionProviders().isProtobuf) {
      return result
    }
    val protoLocation: Option[Location] = (for {
      path <- result.definition.iterator
      if !ProtoJavaVirtualFile.isProtoJavaUri(path.toURI.toString())
      source <- path
        .toInputFromBuffers(buffers)
        .text
        .linesIterator
        .takeWhile(line => line.startsWith("//") || line.startsWith(" //"))
        .collectFirst {
          case s"// source: $source" => source
          case s" // testing-only-source: $source" => source
        }
        .iterator
      protoDoc <- mbt.document(workspace.resolve(source)).iterator
      sym = Symbol(result.symbol.stripSuffix("apply()."))
      symSuffix = symbolSuffixAfterPackage(sym)
      protoSym <- protoDoc.symbols.iterator
      protoSymSuffix = symbolSuffixAfterPackage(Symbol(protoSym.getSymbol()))
      if symSuffix == protoSymSuffix
    } yield new Location(
      protoDoc.file.toURI.toString(),
      protoSym.getDefinitionRange().toLspRange,
    )).headOption

    protoLocation.fold(result) { loc =>
      val allLocations = new ju.ArrayList[Location](result.locations.size() + 1)
      allLocations.add(loc)
      allLocations.addAll(result.locations)
      result.copy(locations = allLocations)
    }
  } catch {
    case NonFatal(e) =>
      scribe.warn(
        s"failed to enhance with protobuf definition for '${result.symbol}'",
        e,
      )
      result
  }

  def handleProtoJavaDefinition(
      res: DefinitionResult
  ): Option[DefinitionResult] = {
    val mbtResult = mbt.definition(res.symbol)
    if (mbtResult.nonEmpty) {
      val locations = new ju.ArrayList[Location](mbtResult.size)
      mbtResult.foreach(locations.add)
      return Some(
        DefinitionResult(
          locations,
          res.symbol,
          None,
          None,
          res.querySymbol,
        )
      )
    }

    val protoUri =
      if (res.locations.isEmpty) None
      else ProtoJavaVirtualFile.extractProtoPath(res.locations.get(0).getUri())

    val protoClassLocation = protoUri.flatMap { protoPath =>
      findProtoClassFromJavaSymbol(protoPath, res.symbol)
    }

    val protoFieldLocation = protoClassLocation.orElse {
      protoUri.flatMap { protoPath =>
        findProtoFieldFromJavaMethod(protoPath, res.symbol)
      }
    }

    val protoRpcLocation = protoFieldLocation.orElse {
      protoUri.flatMap { protoPath =>
        findProtoRpcFromJavaMethod(protoPath, res.symbol)
      }
    }

    protoRpcLocation match {
      case Some(loc) =>
        Some(
          DefinitionResult(
            ju.Collections.singletonList(loc),
            res.symbol,
            None,
            None,
            res.querySymbol,
          )
        )
      case None =>
        scribe.debug(
          s"proto-java: could not resolve symbol ${res.symbol} to proto"
        )
        Some(DefinitionResult.empty)
    }
  }

  private def symbolSuffixAfterPackage(sym: Symbol): List[String] = {
    val pkg = sym.enclosingPackage
    def loop(s: Symbol, acc: List[String]): List[String] = {
      if (s.isNone || s.isRootPackage || s.isEmptyPackage || s == pkg) acc
      else loop(s.owner, s.displayName.toLowerCase :: acc)
    }
    loop(sym, Nil)
  }

  private def findProtoClassFromJavaSymbol(
      protoPath: AbsolutePath,
      javaSymbol: String,
  ): Option[Location] = {
    import scala.meta.internal.mtags.proto.ProtoMtagsV2

    try {
      val sym = Symbol(javaSymbol)
      if (!sym.isType) return None

      val className = sym.displayName
      val input = protoPath.toInputFromBuffers(buffers)
      val protoMtags = new ProtoMtagsV2(input, includeMembers = false)
      val doc = protoMtags.index()

      // Candidate class-name suffixes to look for in proto symbols:
      //  1. The exact Java class name (messages, enums, services)
      //  2. The name with "Grpc" stripped — protoc-java wraps each gRPC service
      //     in an outer class called XxxGrpc, but ProtoMtagsV2 emits the bare
      //     service name (e.g. CompatibilityService#, not CompatibilityServiceGrpc#).
      val candidates = Seq(s"$className#") ++
        (if (className.endsWith("Grpc"))
           Seq(s"${className.dropRight(4)}#")
         else Nil)

      doc.occurrences
        .find { occ =>
          candidates.exists(occ.symbol.endsWith) &&
          occ.role == s.SymbolOccurrence.Role.DEFINITION
        }
        .flatMap { occ =>
          occ.range.map { range =>
            new Location(
              protoPath.toURI.toString(),
              range.toLsp,
            )
          }
        }
    } catch {
      case NonFatal(e) =>
        scribe.debug(
          s"Failed to map Java class to proto type: $javaSymbol",
          e,
        )
        None
    }
  }

  private def findProtoFieldFromJavaMethod(
      protoPath: AbsolutePath,
      javaSymbol: String,
  ): Option[Location] = {
    import scala.meta.internal.mtags.proto.ProtoMtagsV2

    try {
      val methodNameOpt = extractMethodName(javaSymbol)
      val classNameOpt = extractClassName(javaSymbol)

      methodNameOpt.flatMap { methodName =>
        val protoFieldNameOpt = javaMethodToProtoField(methodName)
        protoFieldNameOpt.flatMap { protoFieldName =>
          val input = protoPath.toInputFromBuffers(buffers)
          val protoJavaMtags = new ProtoMtagsV2(input, includeMembers = true)
          val doc = protoJavaMtags.index()
          val fieldSuffix = s"$protoFieldName()."

          doc.occurrences
            .find { occ =>
              val matchesFieldName = occ.symbol.endsWith(fieldSuffix)
              val isDefinition = occ.role == s.SymbolOccurrence.Role.DEFINITION
              val matchesClass = classNameOpt.forall { className =>
                occ.symbol.contains(s"/$className#") || occ.symbol.startsWith(
                  s"$className#"
                )
              }
              matchesFieldName && isDefinition && matchesClass
            }
            .flatMap { occ =>
              occ.range.map { range =>
                new Location(
                  protoPath.toURI.toString(),
                  range.toLsp,
                )
              }
            }
        }
      }
    } catch {
      case NonFatal(e) =>
        scribe.debug(
          s"Failed to map Java method to proto field: $javaSymbol",
          e,
        )
        None
    }
  }

  private def findProtoRpcFromJavaMethod(
      protoPath: AbsolutePath,
      javaSymbol: String,
  ): Option[Location] = {
    import scala.meta.internal.mtags.proto.ProtoMtagsV2

    try {
      val isGrpcClass = javaSymbol.contains("ImplBase#") ||
        javaSymbol.contains("Stub#") ||
        javaSymbol.contains("BlockingStub#") ||
        javaSymbol.contains("FutureStub#")

      if (!isGrpcClass) return None

      val methodNameOpt = extractMethodName(javaSymbol)

      methodNameOpt.flatMap { methodName =>
        val rpcName = capitalize(methodName)

        val input = protoPath.toInputFromBuffers(buffers)
        val protoMtags = new ProtoMtagsV2(input, includeMembers = true)
        val doc = protoMtags.index()
        val rpcSuffix = s"$rpcName()."

        doc.occurrences
          .find { occ =>
            val matchesRpc = occ.symbol.endsWith(rpcSuffix)
            val isDefinition = occ.role == s.SymbolOccurrence.Role.DEFINITION
            val ownerSymbol = Symbol(occ.symbol).owner.value
            val isServiceMethod = doc.symbols.exists { info =>
              info.symbol == ownerSymbol && info.kind.isInterface
            }
            matchesRpc && isDefinition && isServiceMethod
          }
          .flatMap { occ =>
            occ.range.map { range =>
              new Location(
                protoPath.toURI.toString(),
                range.toLsp,
              )
            }
          }
      }
    } catch {
      case NonFatal(e) =>
        scribe.debug(
          s"Failed to map Java gRPC method to proto RPC: $javaSymbol",
          e,
        )
        None
    }
  }

  private def capitalize(s: String): String = {
    if (s.isEmpty) s
    else s.head.toUpper + s.tail
  }

  private def extractClassName(symbol: String): Option[String] = {
    val sym = Symbol(symbol)
    def findTopLevelClass(s: Symbol): Option[String] = {
      if (s.isNone || s.isRootPackage || s.isEmptyPackage) None
      else if (s.owner.isPackage) Some(s.displayName)
      else findTopLevelClass(s.owner)
    }
    findTopLevelClass(sym)
  }

  private def extractMethodName(symbol: String): Option[String] = {
    val hashIndex = symbol.lastIndexOf('#')
    if (hashIndex < 0) return None

    val afterHash = symbol.substring(hashIndex + 1)
    val parenIndex = afterHash.indexOf('(')
    if (parenIndex < 0) {
      Some(afterHash.stripSuffix("."))
    } else {
      Some(afterHash.substring(0, parenIndex))
    }
  }

  private def javaMethodToProtoField(methodName: String): Option[String] = {
    val fieldName =
      if (methodName.startsWith("set") && methodName.length > 3)
        Some(methodName.substring(3))
      else if (methodName.startsWith("get") && methodName.length > 3) {
        val rest = methodName.substring(3)
        if (rest.endsWith("List")) Some(rest.stripSuffix("List"))
        else if (rest.endsWith("Map")) Some(rest.stripSuffix("Map"))
        else if (rest.endsWith("Count")) Some(rest.stripSuffix("Count"))
        else if (rest.endsWith("OrDefault")) Some(rest.stripSuffix("OrDefault"))
        else if (rest.endsWith("OrThrow")) Some(rest.stripSuffix("OrThrow"))
        else if (rest.endsWith("Bytes")) Some(rest.stripSuffix("Bytes"))
        else if (rest.endsWith("Builder")) Some(rest.stripSuffix("Builder"))
        else Some(rest)
      } else if (methodName.startsWith("has") && methodName.length > 3)
        Some(methodName.substring(3))
      else if (methodName.startsWith("clear") && methodName.length > 5)
        Some(methodName.substring(5))
      else if (methodName.startsWith("add") && methodName.length > 3) {
        val rest = methodName.substring(3)
        if (rest.startsWith("All") && rest.length > 3) Some(rest.substring(3))
        else Some(rest)
      } else if (methodName.startsWith("put") && methodName.length > 3) {
        val rest = methodName.substring(3)
        if (rest.startsWith("All") && rest.length > 3) Some(rest.substring(3))
        else Some(rest)
      } else if (methodName.startsWith("remove") && methodName.length > 6)
        Some(methodName.substring(6))
      else if (methodName.startsWith("contains") && methodName.length > 8)
        Some(methodName.substring(8))
      else if (methodName.forall(c => c.isUpper || c == '_' || c.isDigit))
        Some(methodName)
      else
        None

    fieldName.map { name =>
      if (name.forall(c => c.isUpper || c == '_' || c.isDigit)) name
      else camelToSnakeCase(name)
    }
  }

  private def camelToSnakeCase(camelCase: String): String = {
    if (camelCase.isEmpty) return camelCase
    val sb = new StringBuilder
    sb.append(camelCase.charAt(0).toLower)
    for (i <- 1 until camelCase.length) {
      val c = camelCase.charAt(i)
      if (c.isUpper) {
        sb.append('_')
        sb.append(c.toLower)
      } else {
        sb.append(c)
      }
    }
    sb.toString
  }
}
