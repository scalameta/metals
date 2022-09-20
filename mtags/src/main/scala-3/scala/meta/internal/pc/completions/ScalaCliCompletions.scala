package scala.meta.internal.pc.completions

import scala.collection.JavaConverters.*

import scala.meta.internal.mtags.BuildInfo
import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.internal.tokenizers.Chars

import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Symbols.NoSymbol
import dotty.tools.dotc.util.SourcePosition
class ScalaCliCompletions(pos: SourcePosition, text: String):
  def unapply(path: List[Tree]) =
    path match
      case head :: next => None
      case Nil =>
        if !text.stripLeading().startsWith("//") then None
        else
          val directive = text.take(pos.point).split("//").last
          if directive.exists(Chars.isLineBreakChar(_)) then None
          else
            val reg = """>\s*using\s+lib\s+"?(.*)"?""".r
            directive match
              case reg(dep) => Some(dep.stripPrefix(s"\"").stripSuffix(s"\""))
              case _ => None
  def contribute(dependency: String) =
    val scalaVersion = BuildInfo.scalaCompilerVersion
    val api = coursierapi.Complete
      .create()
      .withScalaVersion(scalaVersion)
      .withScalaBinaryVersion(
        "3"
      )
    def completions(s: String): List[String] =
      api.withInput(s).complete().getCompletions().asScala.toList
    val javaCompletions = completions(dependency)
    val scalaCompletions =
      if dependency.endsWith(":") && dependency.count(_ == ':') == 1 then
        completions(dependency + ":").map(":" + _)
      else List.empty
    val editStart =
      var i = pos.point - 1
      while i >= 0 && {
          val c = text.charAt(i)
          (Chars.isIdentifierPart(c) || c == '.' || c == '-')
        }
      do i -= 1
      i + 1
    val editEnd =
      var i = pos.point
      val textLen = text.length()
      while i < text.length() && {
          val c = text.charAt(i)
          (Chars.isIdentifierPart(c) || c == '.' || c == '-')
        }
      do i += 1
      i
    val editRange = pos.withStart(editStart).withEnd(editEnd).toLsp
    (javaCompletions ++ scalaCompletions)
      .map(insertText =>
        CompletionValue.ScalaCLiImport(
          insertText.stripPrefix(":"),
          Some(insertText),
          Some(editRange),
        )
      )
  end contribute

end ScalaCliCompletions
