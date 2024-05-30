package scala.meta.internal.metals

import scala.meta.internal.mtags.CommonMtagsEnrichments._

import org.eclipse.lsp4j.jsonrpc.messages.Either

/**
 * This file contains known and useful (from metals perspective) diagnostic codes.
 * Diagnostic codes are defined in scala3 compiler, but since metals can refernce multiple scala compiler versions at runtime,
 * we cannot have a compile-time dependency on the original definition.
 *
 * See https://github.com/scala/scala3/blob/3fdb2923f83acd2ef8910c9f71a394c46be4e268/compiler/src/dotty/tools/dotc/reporting/ErrorMessageID.scala for more codes
 */
object DiagnosticCodes {
  val Unused: Int = 198

  def isEqual(lspCode: Either[String, Integer], other: Int): Boolean = {
    lspCode.asScala match {
      case Left(strCode) => strCode == other.toString()
      case Right(intCode) => intCode == other
    }
  }
}
