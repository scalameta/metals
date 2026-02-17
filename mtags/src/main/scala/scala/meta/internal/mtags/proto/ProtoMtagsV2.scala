package scala.meta.internal.mtags.proto

import scala.jdk.CollectionConverters._

import scala.meta.inputs.Input
import scala.meta.inputs.Position
import scala.meta.internal.mtags.MtagsIndexer
import scala.meta.internal.proto.diag.SourceFile
import scala.meta.internal.proto.parse.Parser
import scala.meta.internal.proto.tree.Proto.FieldModifier
import scala.meta.internal.proto.tree.Proto._
import scala.meta.internal.semanticdb.Implicits._
import scala.meta.internal.semanticdb.Language
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.SymbolInformation.Kind
import scala.meta.internal.{semanticdb => s}

/**
 * V2 symbol outline indexer for Protobuf files.
 *
 * This is a superset of [[ProtoMtagsV1]] that generates:
 * 1. Proto-package symbols (for proto-to-proto navigation) - same as V1
 * 2. Generated symbols for Java (getX, setX, hasX) with Language.JAVA
 * 3. Generated symbols for Scala/ScalaPB (camelCase, withX) with Language.SCALA
 *
 * The Language field on SymbolInformation distinguishes Java vs Scala symbols
 * so they don't pollute each other during navigation.
 *
 * @param includeMembers If true, include field/method symbols (not just types)
 * @param includeGeneratedSymbols If true, generate accessor symbols (getX, withX, etc.)
 * @see [[ProtoMtagsV1]] for the legacy indexer
 */
class ProtoMtagsV2(
    val input: Input.VirtualFile,
    includeMembers: Boolean = true,
    includeGeneratedSymbols: Boolean = true
) extends MtagsIndexer {

  override def language: Language = Language.UNKNOWN_LANGUAGE

  private lazy val protoFile: ProtoFile = {
    val source = new SourceFile(input.path, input.text)
    Parser.parse(source)
  }

  /** The java_package option value, or falls back to proto package */
  lazy val javaPackage: String = getJavaPackage(protoFile)

  /** The proto package from the package declaration */
  lazy val protoPackage: String = {
    val pkg = protoFile.pkg()
    if (pkg.isPresent) pkg.get().fullName() else ""
  }

  /** The java_outer_classname option value, or generated from filename */
  lazy val outerClassName: String = getOuterClassName(protoFile)

  /** Whether java_multiple_files=true is set */
  lazy val javaMultipleFiles: Boolean = getJavaMultipleFiles(protoFile)

  /**
   * Returns the semanticdb packages for this proto file.
   * Includes both proto package and java_package (if different).
   */
  def semanticdbPackages: Seq[String] = {
    val protoPackages =
      if (protoPackage.isEmpty) Seq(Symbols.EmptyPackage)
      else Seq(protoPackage.replace('.', '/') + "/")

    if (javaPackage.nonEmpty && javaPackage != protoPackage) {
      protoPackages :+ (javaPackage.replace('.', '/') + "/")
    } else {
      protoPackages
    }
  }

  override def indexRoot(): Unit = {
    try {
      indexWithProtoPackage()
    } catch {
      case _: Exception =>
      // Silently ignore parse errors - V2's Java parser doesn't handle
      // malformed proto files gracefully. Use V1 for better error tolerance.
    }
  }

  /**
   * Index using proto package symbols.
   * Also generates Java and Scala accessor symbols with appropriate Language tags.
   */
  private def indexWithProtoPackage(): Unit = {
    currentOwner = Symbols.RootPackage

    // Build package path from proto package
    if (protoPackage.nonEmpty) {
      protoPackage.split('.').foreach { part =>
        val dummyPos = Position.Range(input, 0, 0)
        currentOwner = pkg(part, dummyPos)
      }
    }

    val packageOwner = currentOwner

    // Index all declarations
    protoFile.declarations().asScala.foreach {
      case msg: MessageDecl =>
        currentOwner = packageOwner
        indexMessage(msg)
      case enum: EnumDecl =>
        currentOwner = packageOwner
        indexEnum(enum)
      case svc: ServiceDecl =>
        currentOwner = packageOwner
        indexService(svc)
      case _ =>
    }
  }

  private def indexMessage(msg: MessageDecl): Unit = {
    val msgPos = identToPosition(msg.name())
    val msgOwner = tpe(msg.name().value(), msgPos, Kind.CLASS, 0)

    withOwner(msgOwner) {
      if (includeMembers) {
        // Regular fields
        msg.fields().asScala.foreach { field =>
          val fieldPos = identToPosition(field.name())
          val protoName = field.name().value()
          val isRepeated = field.modifier() == FieldModifier.REPEATED
          indexField(protoName, fieldPos, isRepeated, isMap = false)
        }

        // Map fields
        msg.mapFields().asScala.foreach { mapField =>
          val fieldPos = identToPosition(mapField.name())
          val protoName = mapField.name().value()
          indexField(protoName, fieldPos, isRepeated = false, isMap = true)
        }

        // Oneof fields
        msg.oneofs().asScala.foreach { oneof =>
          indexOneof(oneof)
        }
      }

      // Nested messages
      msg.nestedMessages().asScala.foreach(indexMessage)

      // Nested enums
      msg.nestedEnums().asScala.foreach(indexEnum)
    }
  }

  /**
   * Index a field with proto name and generated accessor symbols.
   * Generates:
   * - Proto field symbol (e.g., name())
   * - If includeGeneratedSymbols:
   *   - Scala symbols with Language.SCALA: camelCase(), withCamelCase()
   *   - Java symbols with Language.JAVA: getCamelCase(), setCamelCase(), hasCamelCase()
   */
  private def indexField(
      protoName: String,
      pos: Position,
      isRepeated: Boolean,
      isMap: Boolean
  ): Unit = {
    val owner = currentOwner
    val javaName = snakeToCamel(protoName)
    val scalaName = snakeToCamelLower(protoName)

    // 1. Proto field symbol (Language.UNKNOWN_LANGUAGE)
    emitMethod(protoName, pos, owner, Language.UNKNOWN_LANGUAGE)

    if (includeGeneratedSymbols) {
      // 2. Scala/ScalaPB symbols (Language.SCALA)
      if (scalaName != protoName) {
        emitMethod(scalaName, pos, owner, Language.SCALA)
      }
      emitMethod(s"with$javaName", pos, owner, Language.SCALA)

      // 3. Java symbols (Language.JAVA)
      emitMethod(s"get$javaName", pos, owner, Language.JAVA)

      if (isMap) {
        emitMethod(s"get${javaName}Map", pos, owner, Language.JAVA)
        emitMethod(s"get${javaName}Count", pos, owner, Language.JAVA)
        emitMethod(s"contains$javaName", pos, owner, Language.JAVA)
        emitMethod(s"get${javaName}OrDefault", pos, owner, Language.JAVA)
        emitMethod(s"get${javaName}OrThrow", pos, owner, Language.JAVA)
      } else if (isRepeated) {
        emitMethod(s"get${javaName}List", pos, owner, Language.JAVA)
        emitMethod(s"get${javaName}Count", pos, owner, Language.JAVA)
      } else {
        emitMethod(s"has$javaName", pos, owner, Language.JAVA)
        emitMethod(s"get${javaName}Bytes", pos, owner, Language.JAVA)
      }
    }
  }

  private def indexOneof(oneof: OneofDecl): Unit = {
    val owner = currentOwner
    val oneofPos = identToPosition(oneof.name())
    val oneofName = oneof.name().value()

    // Emit oneof name as a term symbol
    term(
      oneofName,
      oneofPos,
      Kind.PACKAGE_OBJECT, // HACK: we don't have a "oneof" kind
      s.SymbolInformation.Property.VAL.value
    )
    currentOwner = owner // Restore owner

    if (includeGeneratedSymbols) {
      // Emit the CamelCaseCase class for the oneof
      val camelCase = snakeToCamel(oneofName)
      val caseClassName = s"${camelCase}Case"
      val caseClassSymbol =
        Symbols.Global(owner, Descriptor.Type(caseClassName))
      visitOccurrence(
        s.SymbolOccurrence(
          range = Some(oneofPos.toRange),
          symbol = caseClassSymbol,
          role = s.SymbolOccurrence.Role.DEFINITION
        ),
        s.SymbolInformation(
          symbol = caseClassSymbol,
          language = Language.UNKNOWN_LANGUAGE,
          kind = Kind.CLASS,
          properties = 0,
          displayName = caseClassName
        ),
        owner
      )
    }

    // Emit fields inside the oneof
    val oneofOwner = lastCurrentOwner // The term symbol we just emitted
    oneof.fields().asScala.foreach { field =>
      val fieldPos = identToPosition(field.name())
      val fieldName = field.name().value()
      val javaName = snakeToCamel(fieldName)

      // Proto field name under the oneof term
      withOwner(oneofOwner) {
        emitMethod(fieldName, fieldPos, oneofOwner, Language.UNKNOWN_LANGUAGE)
      }

      if (includeGeneratedSymbols) {
        // Uppercase enum-like symbol (e.g., FIELD_NAME#)
        val upperName = fieldName.toUpperCase()
        val upperSymbol = Symbols.Global(oneofOwner, Descriptor.Type(upperName))
        visitOccurrence(
          s.SymbolOccurrence(
            range = Some(fieldPos.toRange),
            symbol = upperSymbol,
            role = s.SymbolOccurrence.Role.DEFINITION
          ),
          s.SymbolInformation(
            symbol = upperSymbol,
            language = Language.UNKNOWN_LANGUAGE,
            kind = Kind.CLASS,
            properties = 0,
            displayName = upperName
          ),
          oneofOwner
        )

        // Scala withX symbol at message level
        emitMethod(s"with$javaName", fieldPos, owner, Language.SCALA)

        // Java getX/hasX at message level
        emitMethod(s"get$javaName", fieldPos, owner, Language.JAVA)
        emitMethod(s"has$javaName", fieldPos, owner, Language.JAVA)
      }
    }
  }

  private def indexEnum(enum: EnumDecl): Unit = {
    val enumPos = identToPosition(enum.name())
    val enumOwner = tpe(enum.name().value(), enumPos, Kind.CLASS, 0)

    if (includeMembers) {
      withOwner(enumOwner) {
        val owner = currentOwner
        enum.values().asScala.foreach { value =>
          val valuePos = identToPosition(value.name())
          emitMethod(
            value.name().value(),
            valuePos,
            owner,
            Language.UNKNOWN_LANGUAGE,
            s.SymbolInformation.Property.VAL.value
          )
        }
      }
    }
  }

  private def indexService(svc: ServiceDecl): Unit = {
    val svcPos = identToPosition(svc.name())
    val svcOwner = tpe(svc.name().value(), svcPos, Kind.INTERFACE, 0)

    if (includeMembers) {
      withOwner(svcOwner) {
        val owner = currentOwner
        svc.rpcs().asScala.foreach { rpc =>
          val rpcPos = identToPosition(rpc.name())
          val rpcName = rpc.name().value()

          // Proto RPC symbol (e.g., Echo())
          emitMethod(
            rpcName,
            rpcPos,
            owner,
            Language.UNKNOWN_LANGUAGE
          )

          if (includeGeneratedSymbols) {
            // Java gRPC method symbol - lowercase first letter (e.g., echo())
            // This is the method name used in:
            // - ImplBase classes (server-side implementation)
            // - Stub classes (client-side calls)
            val javaMethodName = decapitalize(rpcName)
            emitMethod(javaMethodName, rpcPos, owner, Language.JAVA)
          }
        }
      }
    }

    // Emit reference occurrences for gRPC stub class names so they appear in
    // the bloom filter. This enables find-implementations to locate this proto
    // file when searching for files that might extend the gRPC stub classes.
    // Similar to how JavacMtags emits "ParentClass:" references for extends clauses.
    if (includeGeneratedSymbols) {
      val serviceName = svc.name().value()
      val grpcStubClasses = Seq(
        s"${serviceName}ImplBase",
        s"${serviceName}Stub",
        s"${serviceName}BlockingStub",
        s"${serviceName}FutureStub"
      )
      grpcStubClasses.foreach { stubClassName =>
        emitTypeReference(stubClassName, svcPos)
      }
    }
  }

  /**
   * Emit a type reference occurrence (not a definition).
   * This is used to populate the bloom filter so that find-implementations
   * can locate this file when searching for types that might be extended.
   * The suffix ":" matches what JavacMtags emits for extends clauses.
   */
  private def emitTypeReference(
      name: String,
      pos: Position
  ): Unit = {
    // Use a synthetic symbol - we just need the display name for bloom filter
    val refSymbol = s"$name:"
    visitFuzzyReferenceOccurrence(
      s.SymbolOccurrence(
        range = Some(pos.toRange),
        symbol = refSymbol,
        role = s.SymbolOccurrence.Role.REFERENCE
      )
    )
  }

  /**
   * Decapitalizes the first letter of a string.
   * e.g., "Echo" -> "echo", "GetUser" -> "getUser"
   */
  private def decapitalize(s: String): String = {
    if (s.isEmpty) s
    else s.head.toLower + s.tail
  }

  /**
   * Emit a method symbol with specific language tag.
   * This avoids the issue of `method()` modifying currentOwner.
   */
  private def emitMethod(
      name: String,
      pos: Position,
      owner: String,
      lang: Language,
      properties: Int = 0
  ): Unit = {
    val methodSymbol = Symbols.Global(owner, Descriptor.Method(name, "()"))
    visitOccurrence(
      s.SymbolOccurrence(
        range = Some(pos.toRange),
        symbol = methodSymbol,
        role = s.SymbolOccurrence.Role.DEFINITION
      ),
      s.SymbolInformation(
        symbol = methodSymbol,
        language = lang,
        kind = Kind.METHOD,
        properties = properties,
        displayName = name
      ),
      owner
    )
  }

  private def identToPosition(ident: Ident): Position = {
    Position.Range(input, ident.position(), ident.endPosition())
  }

  /**
   * Converts snake_case to CamelCase (first letter uppercase).
   * e.g., "first_name" -> "FirstName"
   */
  private def snakeToCamel(snakeCase: String): String = {
    snakeCase.split("_").map(_.capitalize).mkString
  }

  /**
   * Converts snake_case to camelCase (first letter lowercase).
   * e.g., "first_name" -> "firstName"
   */
  private def snakeToCamelLower(snakeCase: String): String = {
    val camel = snakeToCamel(snakeCase)
    if (camel.nonEmpty) camel.head.toLower + camel.tail else camel
  }

  private def getJavaPackage(file: ProtoFile): String = {
    file.options().asScala.foreach { option =>
      val optionName = option
        .name()
        .asScala
        .map(_.value())
        .mkString(".")
      if (optionName == "java_package") {
        option.value() match {
          case ident: Ident =>
            val v = ident.value()
            if (v.startsWith("\"") && v.endsWith("\""))
              return v.substring(1, v.length - 1)
            return v
          case _ =>
        }
      }
    }
    // Fall back to proto package
    val pkg = file.pkg()
    if (pkg.isPresent) pkg.get().fullName() else ""
  }

  private def getOuterClassName(file: ProtoFile): String = {
    file.options().asScala.foreach { option =>
      val optionName = option
        .name()
        .asScala
        .map(_.value())
        .mkString(".")
      if (optionName == "java_outer_classname") {
        option.value() match {
          case ident: Ident =>
            val v = ident.value()
            if (v.startsWith("\"") && v.endsWith("\""))
              return v.substring(1, v.length - 1)
            return v
          case _ =>
        }
      }
    }
    // Generate from filename (convert snake_case to CamelCase)
    val filename = input.path.split('/').last.stripSuffix(".proto")
    snakeToCamel(filename) + "OuterClass"
  }

  private def getJavaMultipleFiles(file: ProtoFile): Boolean = {
    file.options().asScala.foreach { option =>
      val optionName = option
        .name()
        .asScala
        .map(_.value())
        .mkString(".")
      if (optionName == "java_multiple_files") {
        option.value() match {
          case ident: Ident =>
            return ident.value() == "true"
          case _ =>
        }
      }
    }
    false
  }
}
