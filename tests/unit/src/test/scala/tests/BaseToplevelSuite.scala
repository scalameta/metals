package tests

import java.nio.file.Files

import scala.meta.Dialect
import scala.meta.dialects
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.mtags.ResolvedOverriddenSymbol
import scala.meta.internal.mtags.TopLevelMember
import scala.meta.internal.mtags.UnresolvedOverriddenSymbol
import scala.meta.io.AbsolutePath

import munit.TestOptions

abstract class BaseToplevelSuite extends BaseSuite {

  def filename: String = "Test.scala"
  def allowedModes: Set[Mode] = Set(All, Toplevel, ToplevelWithInner)

  def check(
      options: TestOptions,
      code: String,
      expected: List[String],
      mode: Mode = Toplevel,
      dialect: Dialect = dialects.Scala3,
  )(implicit location: munit.Location): Unit = {
    test(options) {
      if (!allowedModes(mode)) {
        throw new Exception(s"Mode $mode is not allowed.")
      }
      val dir = AbsolutePath(Files.createTempDirectory("mtags"))
      val input = dir.resolve(filename)
      input.writeText(code)
      val obtained =
        mode match {
          case All | ToplevelWithInner =>
            val includeMembers = mode == All
            val (doc, overrides, toplevelMembers, _) =
              Mtags.extendedIndexing(input, dialect, includeMembers)
            val overriddenMap = overrides.toMap
            val types = toplevelMembers.map {
              case TopLevelMember(symbol, _, _) =>
                s"type $symbol"
            }
            val symbols = doc.symbols.map { symbolInfo =>
              val suffix = if (symbolInfo.isExtension) " EXT" else ""
              val symbol = symbolInfo.symbol
              overriddenMap.get(symbol) match {
                case None => s"$symbol$suffix"
                case Some(symbols) =>
                  val overridden =
                    symbols
                      .map {
                        case ResolvedOverriddenSymbol(symbol) => symbol
                        case UnresolvedOverriddenSymbol(name) => name
                      }
                      .mkString(", ")
                  s"$symbol$suffix -> $overridden"
              }
            }
            symbols ++ types
          case Toplevel => Mtags.topLevelSymbols(input, dialect)
        }
      input.delete()
      dir.delete()
      assertNoDiff(
        obtained.sorted.mkString("\n"),
        expected.sorted.mkString("\n"),
      )
    }
  }

  def assertToplevelsNoDiff(
      obtained: List[String],
      expected: List[String],
  ): Unit = {
    assertNoDiff(obtained.sorted.mkString("\n"), expected.sorted.mkString("\n"))
  }

  sealed trait Mode
  // includeInnerClasses = false
  // includeMembers = false
  case object Toplevel extends Mode
  // includeInnerClasses = true
  // includeMembers = false
  case object ToplevelWithInner extends Mode
  // includeInnerClasses = true
  // includeMembers = true
  case object All extends Mode
}
