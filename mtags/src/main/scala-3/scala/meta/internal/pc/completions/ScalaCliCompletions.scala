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
        if !text.startsWith("//>") then None
        else
          val directive = text.take(pos.point).split("//>").last
          if directive.contains('\n') then None
          else
            directive match
              case s"${empty}using lib $dep" => Some(dep)
              case _ => None
  def contribute(pos: SourcePosition, dep: String) =
    val dependency = dep.stripPrefix(s"\"").stripSuffix(s"\"")
    val scalaVersion = BuildInfo.scalaCompilerVersion
    val api = coursierapi.Complete
      .create()
      .withScalaVersion(scalaVersion)
      .withScalaBinaryVersion(
        if scalaVersion.startsWith("3") then "3"
        else scalaVersion.split('.').take(2).mkString(".")
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
    val editRange = pos.withStart(editStart).toLsp
    (javaCompletions ++ scalaCompletions)
      .map(_.stripPrefix(dependency))
      .map(insertText =>
        CompletionValue.ScalaCLiImport(
          insertText.stripPrefix(":"),
          Some(insertText),
          Some(editRange),
        )
      )
  end contribute

end ScalaCliCompletions
