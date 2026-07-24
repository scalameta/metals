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
import scala.meta.internal.metals.mbt.ProtoJavaSymbolMapper
import scala.meta.internal.metals.mbt.ProtoJavaVirtualFile
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.mtags.MtagsIndexer.AllParameterSignatures
import scala.meta.internal.mtags.MtagsIndexer.ParameterSignature
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
    // here so the generated Java outline lookup still runs; the proto file
    // stays reachable through the fallback below.
    val mbtJavaResult =
      mbt.definition(res.symbol).filterNot(_.getUri.isProtoFilename)
    if (mbtJavaResult.nonEmpty) {
      val locations = new ju.ArrayList[Location](mbtJavaResult.size)
      mbtJavaResult.foreach(locations.add)
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

    val generatedJavaFileUri =
      Try(res.locations.get(0)).toOption.map(_.getUri())
    val protoFilePath =
      generatedJavaFileUri.flatMap(ProtoJavaVirtualFile.extractProtoPath)
    val generatedJavaLocations = (for {
      javaPath <- generatedJavaFileUri
      protoPath <- protoFilePath
    } yield findSymbolInGeneratedJavaFile(
      protoPath,
      res.symbol,
      javaPath,
      res.parameters,
    )).getOrElse(Seq.empty)

    val protoClassLocation = protoFilePath.flatMap { protoPath =>
      findProtoClassFromJavaSymbol(protoPath, res.symbol)
    }

    val protoFieldLocation = protoClassLocation.orElse {
      protoFilePath.flatMap { protoPath =>
        findProtoFieldFromJavaMethod(protoPath, res.symbol)
      }
    }

    val protoRpcLocation = protoFieldLocation.orElse {
      protoFilePath.flatMap { protoPath =>
        findProtoRpcFromJavaMethod(protoPath, res.symbol)
      }
    }

    val allLocations = (generatedJavaLocations ++ protoRpcLocation).toList
    if (allLocations.nonEmpty) {
      Some(
        DefinitionResult(
          allLocations.asJava,
          res.symbol,
          // Recording the materialized file as the definition destination lets
          // Metals remember which build target the user jumped from
          // (InteractiveSemanticdbs.didDefinition), so later requests inside
          // the materialized file use that target's classpath, which includes
          // the protobuf runtime.
          definition = generatedJavaLocations.headOption.map(
            _.getUri().toAbsolutePath
          ),
          semanticdb = None,
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
  private def findSymbolInGeneratedJavaFile(
      protoPath: AbsolutePath,
      javaSymbol: String,
      virtualUri: String,
      callSiteParameterSignature: Seq[ParameterSignature],
  ): Seq[Location] = try {
    (for {
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
        className,
        outline.text,
      )
    } yield findJavaSymbolRange(
      javaFile,
      javaSymbol,
      callSiteParameterSignature,
    )
      .map(range => new Location(javaFile.toURI.toString(), range.toLsp)))
      .getOrElse(Seq.empty)
  } catch {
    case NonFatal(e) =>
      scribe.debug(
        s"proto-java: failed to resolve generated Java outline for $javaSymbol",
        e,
      )
      Seq.empty
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
      callSiteParameterSignature: Seq[ParameterSignature],
  ): Seq[s.Range] = {
    val input = javaFile.toInputFromBuffers(buffers)
    val (doc, paramSignaturesBySymbol) =
      mtags().allToplevelsWithParameterSignatures(input, dialects.Scala213)
    val definitions =
      doc.occurrences.filter(_.role == s.SymbolOccurrence.Role.DEFINITION)

    def loop(sym: Symbol): Seq[s.Range] = {
      if (sym.isNone || sym.isPackage) Seq.empty
      else {
        val ranges = findRange(
          sym.value,
          definitions,
          paramSignaturesBySymbol,
          callSiteParameterSignature,
        )
        if (ranges.nonEmpty) ranges else loop(sym.owner)
      }
    }

    val querySym = Symbol(javaSymbol)
    // For type symbols, require the type itself to be present: a message or
    // enum missing from the generated source means the synthesized outline
    // is stale or belongs to a different proto, and the proto fallback is
    // more precise than an approximate enclosing-class location. Members may
    // still fall back to their enclosing classes, since accessor overloads
    // in the real generated source don't always line up with the
    // synthesized outline.
    if (querySym.isType)
      findRange(
        querySym.value,
        definitions,
        paramSignaturesBySymbol,
        callSiteParameterSignature,
      )
    else loop(querySym)
  }

  /**
   * `symbol` comes from javac resolving a call site against the
   * turbine-compiled classfile ([[scala.meta.internal.jpc.JavaDefinitionProvider]]).
   * `definitions` comes from mtags re-parsing the outline from scratch
   * ([[scala.meta.internal.mtags.JavacMtags]]). The two independently
   * compute the SemanticDB overload disambiguator, so it can order overloads
   * differently and the exact match can miss. When that happens and there's
   * more than one name match, `callSiteParameterSignature` (the call site's real
   * resolved parameter names and types) narrows the candidates down to the
   * one whose signature actually matches; otherwise every name match is
   * returned, since neither side can tell overloads apart on name alone.
   */
  private def findRange(
      symbol: String,
      definitions: Iterable[SymbolOccurrence],
      paramSignaturesBySymbol: AllParameterSignatures,
      callSiteParameterSignature: Seq[ParameterSignature],
  ): Seq[s.Range] = {
    val normalized = withoutOverloadIndex(symbol)
    val candidates =
      definitions
        .filter(occ => withoutOverloadIndex(occ.symbol) == normalized)
        .toSeq
    val narrowed =
      if (candidates.size > 1 && callSiteParameterSignature.nonEmpty)
        candidates.filter(occ =>
          paramSignaturesBySymbol
            .get(occ.symbol)
            .exists(sameParameterSignature(_, callSiteParameterSignature))
        )
      else Seq.empty
    (if (narrowed.size == 1) narrowed else candidates)
      .flatMap(_.range)
      .toSeq
  }

  private def sameParameterSignature(
      a: Seq[ParameterSignature],
      b: Seq[ParameterSignature],
  ): Boolean =
    a.size == b.size && a.zip(b).forall { case (x, y) =>
      x.name == y.name && sameParamType(x.typeName, y.typeName)
    }

  // The generated outline refers to sibling types (e.g. other messages in
  // the same file) by their simple name, while the call site's resolved
  // type is always fully qualified, so an exact string match would miss;
  // comparing by dotted suffix instead lets `Token` match
  // `com.example.ClientProtos.Token`.
  private def sameParamType(a: String, b: String): Boolean = {
    val na = normalizeParamType(a)
    val nb = normalizeParamType(b)
    na == nb || na.endsWith("." + nb) || nb.endsWith("." + na)
  }

  private def normalizeParamType(tpe: String): String =
    tpe.replaceAll("\\s+", "")

  /**
   * Strips the SemanticDB overload index, which is appended to overloaded
   * method symbols to keep them unique, for example:
   *   - `getFoo(+1).` -> `getFoo().`
   *   - `setBar(+3).` -> `setBar().`
   */
  private def withoutOverloadIndex(symbol: String): String =
    symbol.replaceAll("""\(\+\d+\)""", "()")

  /**
   * The lowercased chain of owner names between `sym` and its enclosing
   * package, for example the SemanticDB symbol
   * `com/example/Foo#Bar#baz().` with package `com.example` becomes
   * `List("foo", "bar", "baz")`.
   */
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
      if (!ProtoJavaSymbolMapper.isGrpcStubMethodSymbol(javaSymbol)) return None

      val methodNameOpt = extractMethodName(javaSymbol)

      methodNameOpt.flatMap { methodName =>
        // Java stub methods use the lowerCamel RPC name (e.g. `doSomething`),
        // while the proto symbol uses the UpperCamel RPC name declared in the
        // service (e.g. `DoSomething`).
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

  /**
   * The top-level class name owning `symbol`, for example the SemanticDB
   * symbol `com/example/Foo#Bar#baz().` becomes `Foo`.
   */
  private def extractClassName(symbol: String): Option[String] = {
    val sym = Symbol(symbol)
    def findTopLevelClass(s: Symbol): Option[String] = {
      if (s.isNone || s.isRootPackage || s.isEmptyPackage) None
      else if (s.owner.isPackage) Some(s.displayName)
      else findTopLevelClass(s.owner)
    }
    findTopLevelClass(sym)
  }

  /**
   * The method name of `symbol`, for example the SemanticDB symbol
   * `com/example/Foo#bar(+1).` becomes `bar`.
   */
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

  /**
   * The proto field name accessed by a generated Java accessor method, for
   * example:
   *   - `getFooBar` -> `foo_bar`
   *   - `setFooBarList` -> `foo_bar` (repeated field)
   *   - `hasFooBar` / `clearFooBar` -> `foo_bar`
   *   - `getHTTPCode` -> `HTTP_CODE` (already-uppercase names, e.g. proto
   *     enum-like fields, are kept as-is instead of snake-cased)
   *
   * Inverse of [[scala.meta.internal.metals.mbt.ProtoJavaSymbolMapper#protoFieldToJavaMethods]].
   */
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
