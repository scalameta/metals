package scala.meta.internal.mtags.proto

import scala.meta.internal.mtags.proto.ProtobufToken.TokenType

// --- API Definition ---

object ProtobufToken {
  type TokenType = Int
  // Special Tokens
  val ILLEGAL: TokenType = 0
  val EOF: TokenType = -1

  // Literals
  val IDENTIFIER: TokenType = 1
  val INTEGER: TokenType = 2
  val FLOAT: TokenType = 3
  val STRING: TokenType = 4

  // Punctuation
  val LEFT_BRACE: TokenType = 10 // {
  val RIGHT_BRACE: TokenType = 11 // }
  val LEFT_PAREN: TokenType = 12 // (
  val RIGHT_PAREN: TokenType = 13 // )
  val LEFT_BRACKET: TokenType = 14 // [
  val RIGHT_BRACKET: TokenType = 15 // ]
  val LEFT_ANGLE: TokenType = 16 // <
  val RIGHT_ANGLE: TokenType = 17 // >
  val SEMICOLON: TokenType = 18 // ;
  val COMMA: TokenType = 19 // ,
  val EQUALS: TokenType = 20 // =
  val DOT: TokenType = 21 // .

  // Keywords
  val SYNTAX: TokenType = 100
  val PACKAGE: TokenType = 101
  val IMPORT: TokenType = 102
  val MESSAGE: TokenType = 103
  val ENUM: TokenType = 104
  val SERVICE: TokenType = 105
  val RPC: TokenType = 106
  val RETURNS: TokenType = 107
  val STREAM: TokenType = 108
  val REPEATED: TokenType = 109
  val OPTIONAL: TokenType = 110
  val ONEOF: TokenType = 111
  val MAP: TokenType = 112
  val TRUE: TokenType = 113
  val FALSE: TokenType = 114

  // Proto2-specific keywords
  val REQUIRED: TokenType = 130
  val DEFAULT: TokenType = 131
  val GROUP: TokenType = 132
  val EXTENSIONS: TokenType = 133
  val EXTEND: TokenType = 134
  val TO: TokenType = 135

  // Built-in scalar types
  val STRING_TYPE: TokenType = 115
  val BOOL: TokenType = 116
  val INT32: TokenType = 117
  val INT64: TokenType = 118
  val UINT32: TokenType = 119
  val UINT64: TokenType = 120
  val SINT32: TokenType = 121
  val SINT64: TokenType = 122
  val FIXED32: TokenType = 123
  val FIXED64: TokenType = 124
  val SFIXED32: TokenType = 125
  val SFIXED64: TokenType = 126
  val DOUBLE: TokenType = 127
  val FLOAT_TYPE: TokenType = 128
  val BYTES: TokenType = 129

  val keywords: Map[String, TokenType] = Map(
    "syntax" -> SYNTAX,
    "package" -> PACKAGE,
    "import" -> IMPORT,
    "message" -> MESSAGE,
    "enum" -> ENUM,
    "service" -> SERVICE,
    "rpc" -> RPC,
    "returns" -> RETURNS,
    "stream" -> STREAM,
    "repeated" -> REPEATED,
    "optional" -> OPTIONAL,
    "oneof" -> ONEOF,
    "map" -> MAP,
    "true" -> TRUE,
    "false" -> FALSE,
    // Proto2-specific keywords
    "required" -> REQUIRED,
    "default" -> DEFAULT,
    "group" -> GROUP,
    "extensions" -> EXTENSIONS,
    "extend" -> EXTEND,
    "to" -> TO,
    // Built-in scalar types
    "string" -> STRING_TYPE,
    "bool" -> BOOL,
    "int32" -> INT32,
    "int64" -> INT64,
    "uint32" -> UINT32,
    "uint64" -> UINT64,
    "sint32" -> SINT32,
    "sint64" -> SINT64,
    "fixed32" -> FIXED32,
    "fixed64" -> FIXED64,
    "sfixed32" -> SFIXED32,
    "sfixed64" -> SFIXED64,
    "double" -> DOUBLE,
    "float" -> FLOAT_TYPE,
    "bytes" -> BYTES
  )
  val keywordsById: Map[TokenType, String] = keywords.map(_.swap)
}

case class ProtobufToken(
    tpe: TokenType,
    start: Int,
    end: Int,
    lexeme: String,
    filepath: String
) {
  def tpeName: String = ProtobufToken.keywordsById.get(tpe) match {
    case Some(keyword) => keyword.toUpperCase
    case None =>
      tpe match {
        case ProtobufToken.IDENTIFIER => "IDENTIFIER"
        case ProtobufToken.INTEGER => "INTEGER"
        case ProtobufToken.FLOAT => "FLOAT"
        case ProtobufToken.STRING => "STRING"
        case ProtobufToken.LEFT_BRACE => "LEFT_BRACE"
        case ProtobufToken.RIGHT_BRACE => "RIGHT_BRACE"
        case ProtobufToken.LEFT_PAREN => "LEFT_PAREN"
        case ProtobufToken.RIGHT_PAREN => "RIGHT_PAREN"
        case ProtobufToken.LEFT_BRACKET => "LEFT_BRACKET"
        case ProtobufToken.RIGHT_BRACKET => "RIGHT_BRACKET"
        case ProtobufToken.LEFT_ANGLE => "LEFT_ANGLE"
        case ProtobufToken.RIGHT_ANGLE => "RIGHT_ANGLE"
        case ProtobufToken.SEMICOLON => "SEMICOLON"
        case ProtobufToken.COMMA => "COMMA"
        case ProtobufToken.EQUALS => "EQUALS"
        case ProtobufToken.DOT => "DOT"
        case ProtobufToken.EOF => "EOF"
        case _ => "ILLEGAL"
      }
  }
  override def toString: String = {
    s"Token($tpeName, '$lexeme', [$start..$end])"
  }
}
