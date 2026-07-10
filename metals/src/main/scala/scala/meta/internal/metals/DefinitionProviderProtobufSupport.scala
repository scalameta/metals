package scala.meta.internal.metals

import java.{util => ju}

import scala.util.Try
import scala.util.control.NonFatal

import scala.meta.dialects
import scala.meta.internal.jmbt.Mbt
import scala.meta.internal.metals.Configs.DefinitionProviderConfig
import scala.meta.internal.metals.Configs.ProtobufLspConfig
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.mbt.MbtWorkspaceSymbolProvider
import scala.meta.internal.metals.mbt.ProtoGeneratedJavaFiles
import scala.meta.internal.metals.mbt.ProtoJavaVirtualFile
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.{semanticdb => s}
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.Location

final class DefinitionProviderProtobufSupport(
    workspace: AbsolutePath,
    buffers: Buffers,
    mbt: MbtWorkspaceSymbolProvider,
    definitionProviders: () => DefinitionProviderConfig,
    protobufLspConfig: () => ProtobufLspConfig,
    mtags: () => Mtags,
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
      protoSym <- selectBestProtoSymbol(
        sym,
        protoDoc.symbols.iterator.filter { protoSym =>
          val protoSymSuffix =
            symbolSuffixAfterPackage(Symbol(protoSym.getSymbol()))
          symbolSuffixMatches(symSuffix, protoSymSuffix)
        },
      ).iterator
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
    // When the proto file declares no java_package option, the proto package
    // matches the generated Java package, so the MBT index resolves the Java
    // symbol straight to the proto document. Those proto-file hits are ignored
    // here so the generated srcjar lookup still runs; the proto file stays
    // reachable through the fallback below.
    val mbtJavaResult =
      mbt.definition(res.symbol).filterNot(_.getUri.isProtoFilename)
    if (mbtJavaResult.nonEmpty) {
      val locations = new ju.ArrayList[Location](mbtJavaResult.size)
      mbtJavaResult.foreach(locations.add)
      Some(
        DefinitionResult(
          locations,
          res.symbol,
          None,
          None,
          res.querySymbol,
        )
      )
    } else {}

    val virtualUri = Try(res.locations.get(0)).toOption.map(_.getUri())
    val protoUri = virtualUri.flatMap(ProtoJavaVirtualFile.extractProtoPath)

    val generatedJavaLocation = for {
      uri <- virtualUri
      protoPath <- protoUri
      location <- findGeneratedJavaFromOutline(protoPath, res.symbol, uri)
    } yield location

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

    val allLocations = (generatedJavaLocation ++ protoRpcLocation).toList
    if (allLocations.nonEmpty) {
      Some(
        DefinitionResult(
          allLocations.asJava,
          res.symbol,
          None,
          None,
          res.querySymbol,
        )
      )
    } else {
      scribe.debug(
        s"proto-java: could not resolve symbol ${res.symbol} to proto"
      )
      Some(DefinitionResult.empty)
    }
  }

  /**
   * Finds the definition of `javaSymbol` in the Java source that Metals
   * synthesizes from the proto file. The outline is derived purely from the
   * `.proto`, so this works for any build tool without a build or any
   * configuration; it is materialized to a read-only file on disk so the
   * client can open it.
   */
  private def findGeneratedJavaFromOutline(
      protoPath: AbsolutePath,
      javaSymbol: String,
      virtualUri: String,
  ): Option[Location] = try {
    for {
      className <- ProtoJavaVirtualFile.extractClassName(virtualUri)
      outline <- mbt
        .protoJavaOutlines(protoPath)
        .find(outline =>
          ProtoJavaVirtualFile
            .extractClassName(outline.uri().toString())
            .contains(className)
        )
      javaFile <- ProtoGeneratedJavaFiles.materialize(
        workspace,
        protoPath,
        outline.pkg,
        className,
        outline.text,
      )
      range <- findJavaSymbolRange(javaFile, javaSymbol)
    } yield new Location(javaFile.toURI.toString(), range.toLsp)
  } catch {
    case NonFatal(e) =>
      scribe.debug(
        s"proto-java: failed to resolve generated Java outline for $javaSymbol",
        e,
      )
      None
  }

  /**
   * Locates the definition range of `javaSymbol` in the given Java file,
   * falling back to enclosing classes when the exact symbol is absent, for
   * example when method overload disambiguators computed from the generated
   * outline don't line up with the real generated source.
   */
  private def findJavaSymbolRange(
      javaFile: AbsolutePath,
      javaSymbol: String,
  ): Option[s.Range] = {
    val input = javaFile.toInputFromBuffers(buffers)
    val doc = mtags().allToplevels(input, dialects.Scala213)
    val definitions =
      doc.occurrences.filter(_.role == s.SymbolOccurrence.Role.DEFINITION)

    def loop(sym: Symbol): Option[s.Range] = {
      if (sym.isNone || sym.isPackage) None
      else findRange(sym.value, definitions).orElse(loop(sym.owner))
    }

    val querySym = Symbol(javaSymbol)
    // For type symbols, require the type itself to be present: a message or
    // enum missing from the generated source means the srcjar is stale or
    // belongs to a different proto, and the proto fallback is more precise
    // than an approximate enclosing-class location. Members may still fall
    // back to their enclosing classes, since accessor overloads in the real
    // generated source don't always line up with the synthesized outline.
    if (querySym.isType) findRange(querySym.value, definitions)
    else loop(querySym)
  }

  private def findRange(
      symbol: String,
      definitions: Iterable[SymbolOccurrence],
  ): Option[s.Range] = {
    val exact = definitions.find(_.symbol == symbol)
    exact
      .orElse {
        val normalized = stripDisambiguators(symbol)
        definitions.find(occ => stripDisambiguators(occ.symbol) == normalized)
      }
      .flatMap(_.range)
  }

  private def stripDisambiguators(symbol: String): String =
    symbol.replaceAll("""\(\+\d+\)""", "()")

  private def symbolSuffixAfterPackage(sym: Symbol): List[String] = {
    val pkg = sym.enclosingPackage
    def loop(s: Symbol, acc: List[String]): List[String] = {
      if (s.isNone || s.isRootPackage || s.isEmptyPackage || s == pkg) acc
      else loop(s.owner, s.displayName.toLowerCase :: acc)
    }
    loop(sym, Nil)
  }

  private def symbolSuffixMatches(
      lhs: List[String],
      rhs: List[String],
  ): Boolean = {
    lhs == rhs || endsWithSuffix(lhs, rhs) || endsWithSuffix(rhs, lhs)
  }

  private def endsWithSuffix(
      full: List[String],
      suffix: List[String],
  ): Boolean = {
    suffix.nonEmpty &&
    full.lengthCompare(suffix.length) >= 0 &&
    full.takeRight(suffix.length) == suffix
  }

  /**
   * Proto symbol suffix matching can be ambiguous (for example, `exampleType`
   * may match both enum type `ExampleType` and generated field accessor
   * `exampleType`). Prefer the symbol whose kind and display name best match
   * the original query symbol.
   */
  private def selectBestProtoSymbol(
      querySym: Symbol,
      candidates: Iterator[Mbt.SymbolInformation],
  ): Option[Mbt.SymbolInformation] = {
    val all = candidates.toList
    if (all.isEmpty) None
    else {
      val queryName = normalizeName(querySym.displayName)
      Some(all.maxBy { info =>
        val candidate = Symbol(info.getSymbol())
        val kindMatches =
          (querySym.isMethod && candidate.isMethod) ||
            (querySym.isType && candidate.isType)
        val exactNameMatch = candidate.displayName == querySym.displayName
        val normalizedNameMatch =
          normalizeName(candidate.displayName) == queryName
        (
          if (kindMatches) 1 else 0,
          if (exactNameMatch) 1 else 0,
          if (normalizedNameMatch) 1 else 0,
        )
      })
    }
  }

  private def normalizeName(name: String): String =
    name.replace("_", "").toLowerCase(ju.Locale.ROOT)

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
