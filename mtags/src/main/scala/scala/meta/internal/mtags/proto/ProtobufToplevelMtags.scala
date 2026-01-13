package scala.meta.internal.mtags.proto

import scala.collection.mutable

import scala.meta.inputs.Input
import scala.meta.internal.mtags.MtagsIndexer
import scala.meta.internal.semanticdb.Language
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.{semanticdb => s}

/**
 * A symbol outline indexer for Protobuf v2 and v3.
 *
 * Warning: this file is ~90% AI generated. The best way to work with this file
 * is to write test cases in ProtobufToplevelSuite and let the AI fix the bugs.
 */
class ProtobufToplevelMtags(
    val input: Input.VirtualFile,
    includeGeneratedSymbols: Boolean = false
) extends MtagsIndexer {

  override def language: Language = Language.UNKNOWN_LANGUAGE
  private val scanner = new ProtobufScanner(input.value, input.path)

  // Example input:
  // syntax = "proto3";
  // package com.example;
  // message User {
  //   string name = 1;
  //   int32 age = 2;
  // }
  // Produces symbols:
  // com/example/User# (CLASS)
  // com/example/User#name (FIELD)
  // com/example/User#age (FIELD)

  override def indexRoot(): Unit = {
    try {
      // Set root package for protobuf files without explicit package
      if (currentOwner == Symbols.EmptyPackage) {
        currentOwner = Symbols.RootPackage
      }

      var currentToken = scanner.nextToken()

      while (currentToken.tpe != ProtobufToken.EOF) {
        currentToken = processToken(currentToken)
      }
    } catch {
      case e: Exception =>
        // Log error but don't crash - similar to JavaToplevelMtags
        scala.util.control.NonFatal.unapply(e).foreach(_ => ())
    }
  }

  override def visitOccurrence(
      occ: s.SymbolOccurrence,
      info: s.SymbolInformation,
      owner: String
  ): Unit = {
    super.visitOccurrence(occ, info, owner)
    if (includeGeneratedSymbols) {
      this.emitGeneratedSymbols(occ, info, owner)
    }
  }

  private val isOneof = new mutable.HashSet[String]()
  private def emitGeneratedSymbols(
      occ: s.SymbolOccurrence,
      info: s.SymbolInformation,
      owner: String
  ): Unit = {
    // This method is *not* AI generated, it's all-organic artisinal code.
    info.kind match {
      case s.SymbolInformation.Kind.METHOD if isOneof.contains(owner) =>
        val desc = info.displayName.toUpperCase()
        val symbol = Symbols.Global(owner, Descriptor.Type(desc))
        super.visitOccurrence(
          occ.copy(symbol = symbol),
          info.copy(
            symbol = symbol,
            displayName = desc,
            kind = s.SymbolInformation.Kind.CLASS
          ),
          owner
        )
      case s.SymbolInformation.Kind.METHOD =>
        for {
          desc <- ProtobufToplevelMtags.toCamelCaseVariations(info.displayName)
          if desc != info.displayName
        } {
          val symbol = Symbols.Global(owner, Descriptor.Method(desc, "()"))
          super.visitOccurrence(
            occ.copy(symbol = symbol),
            info.copy(symbol = symbol, displayName = desc),
            owner
          )
        }
      case s.SymbolInformation.Kind.PACKAGE_OBJECT =>
        isOneof.add(info.symbol)
        val camelCase =
          ProtobufToplevelMtags.toCamelCase(info.displayName.capitalize)
        val desc = s"${camelCase}Case"
        val symbol = Symbols.Global(owner, Descriptor.Type(desc))
        super.visitOccurrence(
          occ.copy(symbol = symbol),
          info.copy(
            symbol = symbol,
            displayName = desc,
            kind = s.SymbolInformation.Kind.CLASS
          ),
          owner
        )
      case _ =>
    }
  }

  private def processToken(token: ProtobufToken): ProtobufToken = {
    import ProtobufToken._

    val packageLevelOwner = currentOwner // Save package level owner

    val result = token.tpe match {
      case PACKAGE =>
        processPackageDeclaration()
      case MESSAGE =>
        processMessageDeclaration()
      case ENUM =>
        processEnumDeclaration()
      case SERVICE =>
        processServiceDeclaration()
      case _ =>
        // Skip other tokens and move to next
        scanner.nextToken()
    }

    // Restore package level owner after processing top-level declarations
    token.tpe match {
      case MESSAGE | ENUM | SERVICE =>
        currentOwner = packageLevelOwner
      case _ =>
    }

    result
  }

  private def processPackageDeclaration(): ProtobufToken = {
    // Skip to package name
    var token = scanner.nextToken()
    val packageParts = scala.collection.mutable.ListBuffer.empty[String]

    // Parse package path like "com.example.proto"
    var break = false
    while (token.tpe == ProtobufToken.IDENTIFIER && !break) {
      val packagePart = token.lexeme
      packageParts += packagePart

      token = scanner.nextToken()

      if (token.tpe == ProtobufToken.DOT) {
        token = scanner.nextToken() // Skip the dot
      } else {
        // End of package name
        break = true
      }
    }

    // Emit each package part individually at root level and build the full package path
    currentOwner = Symbols.RootPackage
    packageParts.foreach { part =>
      val dummyPos = createPosition(token) // Use last token position as dummy
      currentOwner = pkg(part, dummyPos)
    }
    // Keep the full package path as currentOwner for subsequent declarations

    skipToSemicolon(token)
  }

  private def processMessageDeclaration(): ProtobufToken = {
    var token = scanner.nextToken()

    if (token.tpe == ProtobufToken.IDENTIFIER) {
      val messageName = token.lexeme
      val messagePos = createPosition(token)
      token = scanner.nextToken()

      // Process message body if it has one
      if (token.tpe == ProtobufToken.LEFT_BRACE) {
        token = processMessageBody(messageName, messagePos)
      }
    }

    token
  }

  private def processMessageBody(
      messageName: String,
      messagePos: scala.meta.inputs.Position
  ): ProtobufToken = {
    var token = scanner.nextToken() // Skip opening brace
    val messageOwner =
      tpe(messageName, messagePos, s.SymbolInformation.Kind.CLASS, 0)

    withOwner(messageOwner) {
      while (
        token.tpe != ProtobufToken.RIGHT_BRACE && token.tpe != ProtobufToken.EOF
      ) {
        val ownerBeforeProcessing = currentOwner
        token.tpe match {
          case ProtobufToken.MESSAGE =>
            token = processMessageDeclaration() // Nested message
          case ProtobufToken.ENUM =>
            token = processEnumDeclaration() // Nested enum
          case ProtobufToken.ONEOF =>
            token = processOneofDeclaration()
          case _ =>
            token = processFieldDeclaration(token)
        }
        // Restore owner after processing any nested declaration to prevent cascading
        currentOwner = ownerBeforeProcessing
      }
    }

    if (token.tpe == ProtobufToken.RIGHT_BRACE) {
      scanner.nextToken() // Skip closing brace
    } else {
      token
    }
  }

  private def processFieldDeclaration(token: ProtobufToken): ProtobufToken = {
    var current = token

    // Skip field modifiers (repeated, optional)
    while (
      current.tpe == ProtobufToken.REPEATED || current.tpe == ProtobufToken.OPTIONAL
    ) {
      current = scanner.nextToken()
    }

    // Skip field type (could be built-in type, identifier, or complex like map<K,V>)
    current = skipFieldType(current)

    // Field name should be an identifier
    if (current.tpe == ProtobufToken.IDENTIFIER) {
      val fieldName = current.lexeme
      val pos = createPosition(current)

      // Emit field - use method with disambiguator to get proper format
      method(
        fieldName,
        "()",
        pos,
        s.SymbolInformation.Property.VAL.value
      )

      current = scanner.nextToken()
    }

    // Skip to semicolon or end of statement
    skipToSemicolon(current)
  }

  private def processEnumDeclaration(): ProtobufToken = {
    var token = scanner.nextToken()

    if (token.tpe == ProtobufToken.IDENTIFIER) {
      val enumName = token.lexeme
      val enumPos = createPosition(token)
      token = scanner.nextToken()

      // Process enum body if it has one
      if (token.tpe == ProtobufToken.LEFT_BRACE) {
        token = processEnumBody(enumName, enumPos)
      }
    }

    token
  }

  private def processEnumBody(
      enumName: String,
      enumPos: scala.meta.inputs.Position
  ): ProtobufToken = {
    var token = scanner.nextToken() // Skip opening brace
    val enumOwner = tpe(enumName, enumPos, s.SymbolInformation.Kind.CLASS, 0)

    withOwner(enumOwner) {
      while (
        token.tpe != ProtobufToken.RIGHT_BRACE && token.tpe != ProtobufToken.EOF
      ) {
        if (token.tpe == ProtobufToken.IDENTIFIER) {
          val ownerBeforeProcessing = currentOwner
          val valueName = token.lexeme
          val pos = createPosition(token)

          // Emit enum value - use method with disambiguator to get proper format
          method(
            valueName,
            "()",
            pos,
            s.SymbolInformation.Property.VAL.value
          )

          // Restore owner after value processing to prevent cascading
          currentOwner = ownerBeforeProcessing
          token = skipToSemicolon(scanner.nextToken())
        } else {
          token = scanner.nextToken()
        }
      }
    }

    if (token.tpe == ProtobufToken.RIGHT_BRACE) {
      scanner.nextToken() // Skip closing brace
    } else {
      token
    }
  }

  private def processServiceDeclaration(): ProtobufToken = {
    var token = scanner.nextToken()

    if (token.tpe == ProtobufToken.IDENTIFIER) {
      val serviceName = token.lexeme
      val servicePos = createPosition(token)
      token = scanner.nextToken()

      // Process service body if it has one
      if (token.tpe == ProtobufToken.LEFT_BRACE) {
        token = processServiceBody(serviceName, servicePos)
      }
    }

    token
  }

  private def processServiceBody(
      serviceName: String,
      servicePos: scala.meta.inputs.Position
  ): ProtobufToken = {
    var token = scanner.nextToken() // Skip opening brace
    val serviceOwner =
      tpe(serviceName, servicePos, s.SymbolInformation.Kind.INTERFACE, 0)

    withOwner(serviceOwner) {
      while (
        token.tpe != ProtobufToken.RIGHT_BRACE && token.tpe != ProtobufToken.EOF
      ) {
        if (token.tpe == ProtobufToken.RPC) {
          val ownerBeforeProcessing = currentOwner
          token = processRpcDeclaration()
          // Restore owner after RPC processing to prevent cascading
          currentOwner = ownerBeforeProcessing
        } else if (token.tpe == ProtobufToken.OPTION) {
          // Skip option blocks which may contain nested braces
          token = skipOptionBlock(token)
        } else {
          token = scanner.nextToken()
        }
      }
    }

    if (token.tpe == ProtobufToken.RIGHT_BRACE) {
      scanner.nextToken() // Skip closing brace
    } else {
      token
    }
  }

  private def processRpcDeclaration(): ProtobufToken = {
    var token = scanner.nextToken() // Skip 'rpc'

    if (token.tpe == ProtobufToken.IDENTIFIER) {
      val rpcName = token.lexeme
      val pos = createPosition(token)

      // Emit RPC method - use method with disambiguator to get proper format
      method(rpcName, "()", pos, 0)

      token = scanner.nextToken()
    }

    // Skip rest of RPC declaration (parameters, returns, etc.)
    skipRpcDeclaration(token)
  }

  private def processOneofDeclaration(): ProtobufToken = {
    var token = scanner.nextToken() // Skip 'oneof'

    if (token.tpe == ProtobufToken.IDENTIFIER) {
      val oneofName = token.lexeme
      val pos = createPosition(token)

      // Emit oneof name as a field symbol
      term(
        oneofName,
        pos,
        s.SymbolInformation.Kind.PACKAGE_OBJECT, // HACK: we don't have a "oneof" kind
        s.SymbolInformation.Property.VAL.value
      )

      token = scanner.nextToken()
    }

    // Process oneof body - emit the fields inside as symbols
    if (token.tpe == ProtobufToken.LEFT_BRACE) {
      token = scanner.nextToken() // Skip opening brace
      val ownerBeforeOneof =
        currentOwner // Save owner before processing oneof fields

      while (
        token.tpe != ProtobufToken.RIGHT_BRACE && token.tpe != ProtobufToken.EOF
      ) {
        // Process each field in the oneof as a regular field
        token = processFieldDeclaration(token)
        // Restore owner after each field to prevent cascading
        currentOwner = ownerBeforeOneof
      }

      if (token.tpe == ProtobufToken.RIGHT_BRACE) {
        scanner.nextToken() // Skip closing brace
      } else {
        token
      }
    } else {
      token
    }
  }

  private def skipFieldType(token: ProtobufToken): ProtobufToken = {
    var current = token

    if (isFieldType(current)) {
      current = scanner.nextToken()

      // Handle map<K, V> syntax
      if (current.tpe == ProtobufToken.LEFT_ANGLE) {
        var angleCount = 1
        current = scanner.nextToken()
        while (angleCount > 0 && current.tpe != ProtobufToken.EOF) {
          current.tpe match {
            case ProtobufToken.LEFT_ANGLE => angleCount += 1
            case ProtobufToken.RIGHT_ANGLE => angleCount -= 1
            case _ =>
          }
          if (angleCount > 0) {
            current = scanner.nextToken()
          }
        }
        if (current.tpe == ProtobufToken.RIGHT_ANGLE) {
          current = scanner.nextToken() // Skip closing angle
        }
      }
    }

    current
  }

  private def isFieldType(token: ProtobufToken): Boolean = {
    import ProtobufToken._
    token.tpe match {
      case STRING_TYPE | BOOL | INT32 | INT64 | UINT32 | UINT64 | SINT32 |
          SINT64 | FIXED32 | FIXED64 | SFIXED32 | SFIXED64 | DOUBLE |
          FLOAT_TYPE | BYTES | IDENTIFIER | MAP =>
        true
      case _ => false
    }
  }

  private def skipRpcDeclaration(token: ProtobufToken): ProtobufToken = {
    var current = token
    while (
      current.tpe != ProtobufToken.SEMICOLON &&
      current.tpe != ProtobufToken.EOF &&
      current.tpe != ProtobufToken.LEFT_BRACE
    ) {
      current = scanner.nextToken()
    }

    current.tpe match {
      case ProtobufToken.SEMICOLON =>
        scanner.nextToken() // Skip semicolon
      case ProtobufToken.LEFT_BRACE =>
        // Skip balanced braces
        var braceCount = 1
        current = scanner.nextToken()
        while (braceCount > 0 && current.tpe != ProtobufToken.EOF) {
          current.tpe match {
            case ProtobufToken.LEFT_BRACE => braceCount += 1
            case ProtobufToken.RIGHT_BRACE => braceCount -= 1
            case _ =>
          }
          if (braceCount > 0) {
            current = scanner.nextToken()
          }
        }
        if (current.tpe == ProtobufToken.RIGHT_BRACE) {
          scanner.nextToken() // Skip closing brace
        } else {
          current
        }
      case _ =>
        current
    }
  }

  private def skipToSemicolon(token: ProtobufToken): ProtobufToken = {
    var current = token
    while (
      current.tpe != ProtobufToken.SEMICOLON &&
      current.tpe != ProtobufToken.EOF &&
      current.tpe != ProtobufToken.RIGHT_BRACE
    ) {
      current = scanner.nextToken()
    }

    if (current.tpe == ProtobufToken.SEMICOLON) {
      scanner.nextToken() // Skip semicolon
    } else {
      current
    }
  }

  private def skipOptionBlock(token: ProtobufToken): ProtobufToken = {
    // token is the OPTION token, skip past it
    var current =
      if (token.tpe == ProtobufToken.OPTION) scanner.nextToken() else token

    // Skip until we find semicolon (simple option) or handle nested braces
    while (
      current.tpe != ProtobufToken.SEMICOLON &&
      current.tpe != ProtobufToken.EOF &&
      current.tpe != ProtobufToken.LEFT_BRACE
    ) {
      current = scanner.nextToken()
    }

    current.tpe match {
      case ProtobufToken.SEMICOLON =>
        scanner.nextToken() // Skip semicolon
      case ProtobufToken.LEFT_BRACE =>
        // Skip balanced braces for option blocks like: option (x) = { ... };
        var braceCount = 1
        current = scanner.nextToken()
        while (braceCount > 0 && current.tpe != ProtobufToken.EOF) {
          current.tpe match {
            case ProtobufToken.LEFT_BRACE => braceCount += 1
            case ProtobufToken.RIGHT_BRACE => braceCount -= 1
            case _ =>
          }
          if (braceCount > 0) {
            current = scanner.nextToken()
          }
        }
        if (current.tpe == ProtobufToken.RIGHT_BRACE) {
          current = scanner.nextToken() // Skip closing brace
          // Skip the trailing semicolon after the option block
          if (current.tpe == ProtobufToken.SEMICOLON) {
            scanner.nextToken()
          } else {
            current
          }
        } else {
          current
        }
      case _ =>
        current
    }
  }

  private def createPosition(
      token: ProtobufToken
  ): scala.meta.inputs.Position = {
    scala.meta.inputs.Position.Range(input, token.start, token.end)
  }
}

object ProtobufToplevelMtags {
  def toCamelCase(snakeCase: String): String = {
    val matches = "_([a-z])".r.findAllIn(snakeCase)
    matches.map(m => m.toUpperCase()).mkString("")
    val result = new StringBuilder()
    var i = 0
    while (i < snakeCase.length) {
      if (snakeCase(i) == '_' && i + 1 < snakeCase.length) {
        result.append(snakeCase.charAt(i + 1).toUpper)
        i += 2
      } else {
        result.append(snakeCase(i))
        i += 1
      }
    }
    result.toString()
  }
  def toCamelCaseVariations(snakeCase: String): List[String] = {
    List(
      toCamelCase(snakeCase),
      toCamelCase("get_" + snakeCase),
      toCamelCase("with_" + snakeCase)
    )
  }
}
