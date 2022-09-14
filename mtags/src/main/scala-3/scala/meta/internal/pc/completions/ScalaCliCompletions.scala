package scala.meta.internal.pc.completions

import dotty.tools.dotc.util.SourcePosition
import dotty.tools.dotc.ast.tpd.*
import scala.meta.internal.mtags.BuildInfo

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
              case s"${empty}using lib \"$dep\"" => Some(dep)
              case _ => None
  def contribute(pos: SourcePosition, dependency: String) =
    val scalaVersion = BuildInfo.scalaCompilerVersion
    val api = coursierapi.Complete
      .create()
      .withScalaVersion(scalaVersion)
      .withScalaBinaryVersion(
        scalaVersion.split('.').take(2).mkString(".")
      )
    def completions(s: String): List[String] =
      api.withInput(s).complete().getCompletions().asScala.toList
    val javaCompletions = completions(dependency)
    val scalaCompletions =
      if dependency.endsWith(":") && dependency.count(_ == ':') == 1 then
        completions(dependency + ":").map(":" + _)
      else List.empty

    val ivyEditRange =
        // We need the text edit to span the whole group/artefact/version
        val rangeStart = inferStart(
          pos,
          pos.source.content.mkString,
          c => Chars.isIdentifierPart(c) || c == '.',
        )
        pos.withStart(rangeStart).withEnd(pos.point).toLsp

    (javaCompletions ++ scalaCompletions)
  end contribute

end ScalaCliCompletions
