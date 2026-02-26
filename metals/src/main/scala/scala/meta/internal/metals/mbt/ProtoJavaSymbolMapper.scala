package scala.meta.internal.metals.mbt

import scala.util.control.NonFatal

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.mtags.proto.ProtoMtagsV2
import scala.meta.io.AbsolutePath

/**
 * Maps proto symbols to their corresponding Java symbols.
 *
 * This enables find-references from proto files to show usages in Java files
 * that import the proto-generated classes.
 *
 * For a proto file with java_package option, this converts proto symbols
 * to the Java package namespace and generates all accessor method variants.
 *
 * For example, proto symbol `com/example/User#name().` with java_package
 * "com.example.jproto" maps to:
 * - com/example/jproto/User#getName().
 * - com/example/jproto/User#hasName().
 * - com/example/jproto/User#Builder#setName().
 * - etc.
 */
object ProtoJavaSymbolMapper {
  private val grpcStubSuffixes =
    Seq("ImplBase", "Stub", "BlockingStub", "FutureStub")

  /**
   * Result of mapping proto symbols to Java symbols.
   *
   * @param symbolMapping Map from Java symbol to original proto symbol
   * @param protoPackage The proto package from the proto file
   * @param javaPackage The java_package from the proto file
   * @param javaAccessorMethods List of Java accessor method names to query for
   * @param methodNamePatterns Patterns to match in method chains (for unresolved symbol chains)
   */
  case class ProtoToJavaResult(
      symbolMapping: Map[String, String],
      protoPackage: String,
      javaPackage: String,
      javaAccessorMethods: Seq[String],
      methodNamePatterns: Seq[(String, String)] = Seq.empty,
  )

  object ProtoToJavaResult {
    val empty: ProtoToJavaResult =
      ProtoToJavaResult(Map.empty, "", "", Seq.empty)
  }

  /**
   * Maps proto symbols to their corresponding Java symbols.
   *
   * Uses ProtoMtagsV2 to parse the proto file and leverage its existing
   * knowledge of proto-to-Java symbol mapping.
   */
  def protoToJavaSymbols(
      protoPath: AbsolutePath,
      protoSymbols: Seq[String],
      buffers: Buffers,
  ): ProtoToJavaResult = {
    try {
      val input = protoPath.toInputFromBuffers(buffers)
      val protoMtags = new ProtoMtagsV2(input, includeMembers = true)
      val javaDoc = protoMtags.index()

      val javaPackage = protoMtags.javaPackage
      val protoPackage = protoMtags.protoPackage
      val javaMultipleFiles = protoMtags.javaMultipleFiles
      val outerClassName = protoMtags.outerClassName

      val symbolMapping = scala.collection.mutable.Map.empty[String, String]
      val accessorMethods =
        scala.collection.mutable.ListBuffer.empty[String]

      for (protoSymbol <- protoSymbols) {
        val sym = Symbol(protoSymbol)

        if (sym.isType) {
          // Check if this is a service (interface) with RPC methods
          val isService = javaDoc.symbols.exists { info =>
            info.symbol == protoSymbol && info.kind.isInterface
          }
          if (isService) {
            mapServiceSymbol(
              protoSymbol,
              sym,
              protoPackage,
              javaPackage,
              javaMultipleFiles,
              outerClassName,
              symbolMapping,
            )
          } else {
            mapTypeSymbol(
              protoSymbol,
              sym,
              protoPackage,
              javaPackage,
              javaMultipleFiles,
              outerClassName,
              javaDoc,
              symbolMapping,
            )
          }
        } else if (sym.isMethod || (sym.isTerm && !sym.owner.isPackage)) {
          // Check if this is an RPC method (owner is a service)
          val ownerSymbol = sym.owner.value
          val isRpcMethod = javaDoc.symbols.exists { info =>
            info.symbol == ownerSymbol && info.kind.isInterface
          }
          if (isRpcMethod) {
            mapRpcSymbol(
              protoSymbol,
              sym,
              protoPackage,
              javaPackage,
              javaMultipleFiles,
              outerClassName,
              symbolMapping,
              accessorMethods,
            )
          } else {
            mapFieldSymbol(
              protoSymbol,
              sym,
              protoPackage,
              javaPackage,
              javaMultipleFiles,
              outerClassName,
              javaDoc,
              symbolMapping,
              accessorMethods,
            )
          }
        } else if (sym.isTerm && sym.owner.isPackage) {
          // Top-level term - map directly
          val javaSymbol =
            convertProtoSymbolToJava(protoSymbol, protoPackage, javaPackage)
          addSymbolMapping(
            symbolMapping,
            javaSymbol,
            protoSymbol,
            javaPackage,
            javaMultipleFiles,
            outerClassName,
          )
        }
      }

      ProtoToJavaResult(
        symbolMapping.toMap,
        protoPackage,
        javaPackage,
        accessorMethods.toSeq,
      )
    } catch {
      case NonFatal(e) =>
        scribe.debug(s"Failed to map proto symbols to Java: ${e.getMessage}")
        ProtoToJavaResult.empty
    }
  }

  /**
   * Maps a proto service symbol to its Java gRPC class equivalents.
   * Services generate multiple Java classes: XxxGrpc, XxxImplBase, XxxStub, etc.
   */
  private def mapServiceSymbol(
      protoSymbol: String,
      sym: Symbol,
      protoPackage: String,
      javaPackage: String,
      javaMultipleFiles: Boolean,
      outerClassName: String,
      result: scala.collection.mutable.Map[String, String],
  ): Unit = {
    val serviceName = sym.displayName
    val javaOwner = convertProtoSymbolToJava(
      sym.owner.value,
      protoPackage,
      javaPackage,
    )

    // Map the service class itself
    val javaServiceSymbol =
      convertProtoSymbolToJava(protoSymbol, protoPackage, javaPackage)
    addSymbolMapping(
      result,
      javaServiceSymbol,
      protoSymbol,
      javaPackage,
      javaMultipleFiles,
      outerClassName,
    )

    // gRPC generates XxxGrpc outer class with nested classes
    val grpcClassName = s"${serviceName}Grpc"
    addSymbolMapping(
      result,
      s"$javaOwner$grpcClassName#",
      protoSymbol,
      javaPackage,
      javaMultipleFiles,
      outerClassName,
    )

    // Nested stub classes
    val stubClasses = Seq(
      s"${serviceName}ImplBase",
      s"${serviceName}Stub",
      s"${serviceName}BlockingStub",
      s"${serviceName}FutureStub",
    )
    for (stubClass <- stubClasses) {
      addSymbolMapping(
        result,
        s"$javaOwner$grpcClassName#$stubClass#",
        protoSymbol,
        javaPackage,
        javaMultipleFiles,
        outerClassName,
      )
    }
  }

  /**
   * Maps a proto RPC symbol to its Java gRPC method equivalents.
   * RPC methods are called from ImplBase, Stub, BlockingStub, and FutureStub classes.
   */
  private def mapRpcSymbol(
      protoSymbol: String,
      sym: Symbol,
      protoPackage: String,
      javaPackage: String,
      javaMultipleFiles: Boolean,
      outerClassName: String,
      result: scala.collection.mutable.Map[String, String],
      accessorMethods: scala.collection.mutable.ListBuffer[String],
  ): Unit = {
    val rpcName = sym.displayName
    val javaMethodName = decapitalize(rpcName)
    val ownerSymbol = sym.owner.value
    val serviceName = Symbol(ownerSymbol).displayName

    val javaOwner = convertProtoSymbolToJava(
      Symbol(ownerSymbol).owner.value,
      protoPackage,
      javaPackage,
    )

    // gRPC class name
    val grpcClassName = s"${serviceName}Grpc"

    // All stub class variants
    val stubClasses = Seq(
      s"${serviceName}ImplBase",
      s"${serviceName}Stub",
      s"${serviceName}BlockingStub",
      s"${serviceName}FutureStub",
    )

    // Map method in all stub classes
    for (stubClass <- stubClasses) {
      // Full symbol form for method
      addSymbolMapping(
        result,
        s"$javaOwner$grpcClassName#$stubClass#$javaMethodName().",
        protoSymbol,
        javaPackage,
        javaMultipleFiles,
        outerClassName,
      )
      // Short form for unresolved references
      addSymbolMapping(
        result,
        s"$stubClass#$javaMethodName().",
        protoSymbol,
        javaPackage,
        javaMultipleFiles,
        outerClassName,
      )
      addSymbolMapping(
        result,
        s"$stubClass#$javaMethodName#",
        protoSymbol,
        javaPackage,
        javaMultipleFiles,
        outerClassName,
      )

      // Also add the class symbol for implementation search (for find-refs override detection)
      // This maps the proto service/RPC symbol to the Java stub class
      addSymbolMapping(
        result,
        s"$javaOwner$grpcClassName#$stubClass#",
        protoSymbol,
        javaPackage,
        javaMultipleFiles,
        outerClassName,
      )
    }

    // Add the method name to search for
    accessorMethods += javaMethodName
  }

  /**
   * Decapitalizes the first letter of a string.
   * e.g., "Echo" -> "echo", "GetUser" -> "getUser"
   */
  private def decapitalize(s: String): String = {
    if (s.isEmpty) s
    else s.head.toLower + s.tail
  }

  def isGrpcStubClassSymbol(symbol: String): Boolean = {
    val sym = Symbol(symbol)
    val className = sym.displayName
    grpcStubSuffixes.exists(className.endsWith)
  }

  def isGrpcStubMethodSymbol(symbol: String): Boolean = {
    val sym = Symbol(symbol)
    sym.isMethod && isGrpcStubClassSymbol(sym.owner.value)
  }

  /**
   * Maps a proto type symbol (message or enum) to its Java equivalent.
   */
  private def mapTypeSymbol(
      protoSymbol: String,
      sym: Symbol,
      protoPackage: String,
      javaPackage: String,
      javaMultipleFiles: Boolean,
      outerClassName: String,
      javaDoc: scala.meta.internal.semanticdb.TextDocument,
      result: scala.collection.mutable.Map[String, String],
  ): Unit = {
    val className = sym.displayName
    val javaClassSymbol =
      convertProtoSymbolToJava(protoSymbol, protoPackage, javaPackage)
    addSymbolMapping(
      result,
      javaClassSymbol,
      protoSymbol,
      javaPackage,
      javaMultipleFiles,
      outerClassName,
    )

    // Also find all Java occurrences that reference this type
    javaDoc.occurrences.foreach { occ =>
      if (
        occ.symbol.contains(s"/$className#") ||
        occ.symbol.startsWith(s"$className#") ||
        occ.symbol.contains(s"#$className#")
      ) {
        result(occ.symbol) = protoSymbol
      }
    }
  }

  /**
   * Maps a proto field symbol to its Java accessor method equivalents.
   *
   * For enum values (ALL_CAPS), maps to Java static field.
   * For regular fields, maps to getter/setter methods.
   *
   * Also maps short-name symbols (without package prefix) for cases where
   * javac couldn't fully resolve the proto-generated classes.
   */
  private def mapFieldSymbol(
      protoSymbol: String,
      sym: Symbol,
      protoPackage: String,
      javaPackage: String,
      javaMultipleFiles: Boolean,
      outerClassName: String,
      javaDoc: scala.meta.internal.semanticdb.TextDocument,
      result: scala.collection.mutable.Map[String, String],
      accessorMethods: scala.collection.mutable.ListBuffer[String],
  ): Unit = {
    val fieldName = sym.displayName
    val ownerSymbol = sym.owner.value

    val javaOwner =
      convertProtoSymbolToJava(ownerSymbol, protoPackage, javaPackage)
    val javaClassName = Symbol(javaOwner).displayName

    // Check if this is an enum value (ALL_CAPS)
    val isEnumValue = fieldName.forall(c => c.isUpper || c == '_' || c.isDigit)

    if (isEnumValue) {
      // Enum value - map to Java static field
      val javaEnumValueSymbol = s"$javaOwner$fieldName."
      addSymbolMapping(
        result,
        javaEnumValueSymbol,
        protoSymbol,
        javaPackage,
        javaMultipleFiles,
        outerClassName,
      )
      // Also map method form just in case
      addSymbolMapping(
        result,
        s"$javaOwner$fieldName().",
        protoSymbol,
        javaPackage,
        javaMultipleFiles,
        outerClassName,
      )
      // Short-name variants for unresolved references
      addSymbolMapping(
        result,
        s"$javaClassName#$fieldName.",
        protoSymbol,
        javaPackage,
        javaMultipleFiles,
        outerClassName,
      )
      addSymbolMapping(
        result,
        s"$javaClassName#$fieldName#",
        protoSymbol,
        javaPackage,
        javaMultipleFiles,
        outerClassName,
      )
    } else {
      // Regular field - map to Java getter/setter methods
      val javaMethodNames = protoFieldToJavaMethods(fieldName)
      accessorMethods ++= javaMethodNames

      javaMethodNames.foreach { methodName =>
        // Method on message class: Message#getXxx()
        addSymbolMapping(
          result,
          s"$javaOwner$methodName().",
          protoSymbol,
          javaPackage,
          javaMultipleFiles,
          outerClassName,
        )
        // Method on builder class: Message#Builder#setXxx()
        addSymbolMapping(
          result,
          s"${javaOwner}Builder#$methodName().",
          protoSymbol,
          javaPackage,
          javaMultipleFiles,
          outerClassName,
        )

        // Short-name variants for unresolved references (when proto-generated
        // classes don't exist on classpath, javac produces symbols like
        // `ClassName#methodName#` instead of fully qualified)
        addSymbolMapping(
          result,
          s"$javaClassName#$methodName().",
          protoSymbol,
          javaPackage,
          javaMultipleFiles,
          outerClassName,
        )
        addSymbolMapping(
          result,
          s"$javaClassName#$methodName#",
          protoSymbol,
          javaPackage,
          javaMultipleFiles,
          outerClassName,
        )
        // Also Builder variants
        addSymbolMapping(
          result,
          s"${javaClassName}Builder#$methodName().",
          protoSymbol,
          javaPackage,
          javaMultipleFiles,
          outerClassName,
        )
        addSymbolMapping(
          result,
          s"${javaClassName}Builder#$methodName#",
          protoSymbol,
          javaPackage,
          javaMultipleFiles,
          outerClassName,
        )
        addSymbolMapping(
          result,
          s"$javaClassName#Builder#$methodName().",
          protoSymbol,
          javaPackage,
          javaMultipleFiles,
          outerClassName,
        )
        addSymbolMapping(
          result,
          s"$javaClassName#Builder#$methodName#",
          protoSymbol,
          javaPackage,
          javaMultipleFiles,
          outerClassName,
        )
      }

      // Find actual occurrences in javaDoc that match this field
      javaDoc.occurrences.foreach { occ =>
        val isOwnerMatch =
          occ.symbol.contains(s"/$javaClassName#") ||
            occ.symbol.startsWith(s"$javaClassName#") ||
            occ.symbol.contains(s"#$javaClassName#")
        if (
          isOwnerMatch &&
          javaMethodNames.exists(m =>
            occ.symbol.contains(s"#$m(") || occ.symbol.endsWith(s"#$m().")
          )
        ) {
          result(occ.symbol) = protoSymbol
        }
      }
    }
  }

  /**
   * Converts a proto package symbol to a Java package symbol.
   * e.g., com/example/User# with protoPackage "com.example" and
   * javaPackage "com.example.jproto" -> com/example/jproto/User#
   */
  def convertProtoSymbolToJava(
      protoSymbol: String,
      protoPackage: String,
      javaPackage: String,
  ): String = {
    val protoPrefix = protoPackage.replace('.', '/') + "/"
    val javaPrefix = javaPackage.replace('.', '/') + "/"
    if (protoSymbol.startsWith(protoPrefix)) {
      javaPrefix + protoSymbol.stripPrefix(protoPrefix)
    } else {
      // No package prefix, just add java package
      javaPrefix + protoSymbol
    }
  }

  /**
   * Generates all Java method names that correspond to a proto field.
   *
   * This mirrors the accessor methods generated by ProtoMtagsV2:
   * - indexScalarField generates get, has, set, clear, add, addAll, etc.
   * - indexMapField generates getMap, contains, put, putAll, remove, etc.
   *
   * We generate all variants since we don't know the field type at this point.
   */
  private def protoFieldToJavaMethods(protoFieldName: String): Seq[String] = {
    val camelCase = snakeToCamel(protoFieldName)
    Seq(
      // Scalar field getters (from ProtoMtagsV2.indexScalarField)
      s"get$camelCase",
      s"has$camelCase",
      s"get${camelCase}Bytes",
      // Repeated field methods
      s"get${camelCase}List",
      s"get${camelCase}Count",
      // Map field methods (from ProtoMtagsV2.indexMapField)
      s"get${camelCase}Map",
      s"contains$camelCase",
      s"get${camelCase}OrDefault",
      s"get${camelCase}OrThrow",
      // Builder methods
      s"set$camelCase",
      s"clear$camelCase",
      s"set${camelCase}Bytes",
      s"add$camelCase",
      s"addAll$camelCase",
      s"put$camelCase",
      s"putAll$camelCase",
      s"remove$camelCase",
    )
  }

  /**
   * Converts snake_case to CamelCase.
   * Same logic as ProtoMtagsV2.snakeToCamel.
   */
  private def snakeToCamel(snakeCase: String): String =
    snakeCase.split("_").map(_.capitalize).mkString

  private def addSymbolMapping(
      result: scala.collection.mutable.Map[String, String],
      javaSymbol: String,
      protoSymbol: String,
      javaPackage: String,
      javaMultipleFiles: Boolean,
      outerClassName: String,
  ): Unit = {
    result(javaSymbol) = protoSymbol
    singleFileOuterClassVariant(
      javaSymbol,
      javaPackage,
      javaMultipleFiles,
      outerClassName,
    ).foreach { alt =>
      result(alt) = protoSymbol
    }
  }

  private def singleFileOuterClassVariant(
      javaSymbol: String,
      javaPackage: String,
      javaMultipleFiles: Boolean,
      outerClassName: String,
  ): Option[String] = {
    if (javaMultipleFiles || outerClassName.isEmpty) {
      None
    } else {
      val javaPrefix =
        if (javaPackage.nonEmpty) javaPackage.replace('.', '/') + "/"
        else ""
      if (javaSymbol.startsWith(javaPrefix)) {
        val suffix = javaSymbol.stripPrefix(javaPrefix)
        if (suffix.startsWith(s"$outerClassName#")) None
        else Some(s"$javaPrefix$outerClassName#$suffix")
      } else if (javaSymbol.startsWith(s"$outerClassName#")) {
        None
      } else {
        Some(s"$outerClassName#$javaSymbol")
      }
    }
  }
}
