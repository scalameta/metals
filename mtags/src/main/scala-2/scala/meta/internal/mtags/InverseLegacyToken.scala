package scala.meta.internal.mtags

import scala.meta.internal.tokenizers.LegacyToken._

/**
 * Utility to print helpful messages for parse errors. */
object InverseLegacyToken {
  val category: Map[Int, String] = Map[Int, String](
    EMPTY -> "EMPTY",
    UNDEF -> "UNDEF",
    ERROR -> "ERROR",
    EOF -> "EOF",
    /**
     * literals */
    CHARLIT -> "CHARLIT",
    INTLIT -> "INTLIT",
    LONGLIT -> "LONGLIT",
    FLOATLIT -> "FLOATLIT",
    DOUBLELIT -> "DOUBLELIT",
    STRINGLIT -> "STRINGLIT",
    STRINGPART -> "STRINGPART",
    SYMBOLLIT -> "SYMBOLLIT",
    INTERPOLATIONID -> "INTERPOLATIONID",
    XMLLIT -> "XMLLIT",
    XMLLITEND -> "XMLLITEND",
    /**
     * identifiers */
    IDENTIFIER -> "IDENTIFIER",
    BACKQUOTED_IDENT -> "BACKQUOTED_IDENT",
    /**
     * keywords */
    NEW -> "NEW",
    THIS -> "THIS",
    SUPER -> "SUPER",
    NULL -> "NULL",
    TRUE -> "TRUE",
    FALSE -> "FALSE",
    /**
     * modifiers */
    IMPLICIT -> "IMPLICIT",
    OVERRIDE -> "OVERRIDE",
    PROTECTED -> "PROTECTED",
    PRIVATE -> "PRIVATE",
    ABSTRACT -> "ABSTRACT",
    FINAL -> "FINAL",
    SEALED -> "SEALED",
    LAZY -> "LAZY",
    MACRO -> "MACRO",
    /**
     * templates */
    PACKAGE -> "PACKAGE",
    IMPORT -> "IMPORT",
    CLASS -> "CLASS",
    CASECLASS -> "CASECLASS",
    OBJECT -> "OBJECT",
    CASEOBJECT -> "CASEOBJECT",
    TRAIT -> "TRAIT",
    EXTENDS -> "EXTENDS",
    WITH -> "WITH",
    TYPE -> "TYPE",
    FORSOME -> "FORSOME",
    DEF -> "DEF",
    VAL -> "VAL",
    VAR -> "VAR",
    ENUM -> "ENUM",
    /**
     * control structures */
    IF -> "IF",
    THEN -> "THEN",
    ELSE -> "ELSE",
    WHILE -> "WHILE",
    DO -> "DO",
    FOR -> "FOR",
    YIELD -> "YIELD",
    THROW -> "THROW",
    TRY -> "TRY",
    CATCH -> "CATCH",
    FINALLY -> "FINALLY",
    CASE -> "CASE",
    RETURN -> "RETURN",
    MATCH -> "MATCH",
    /**
     * parenthesis */
    LPAREN -> "LPAREN",
    RPAREN -> "RPAREN",
    LBRACKET -> "LBRACKET",
    RBRACKET -> "RBRACKET",
    LBRACE -> "LBRACE",
    RBRACE -> "RBRACE",
    /**
     * special symbols */
    COMMA -> "COMMA",
    SEMI -> "SEMI",
    DOT -> "DOT",
    COLON -> "COLON",
    EQUALS -> "EQUALS",
    AT -> "AT",
    /**
     * special symbols */
    HASH -> "HASH",
    USCORE -> "USCORE",
    ARROW -> "ARROW",
    LARROW -> "LARROW",
    SUBTYPE -> "SUBTYPE",
    SUPERTYPE -> "SUPERTYPE",
    VIEWBOUND -> "VIEWBOUND",
    WHITESPACE -> "WHITESPACE",
    COMMENT -> "COMMENT",
    UNQUOTE -> "UNQUOTE",
    ELLIPSIS -> "ELLIPSIS"
  )

}
