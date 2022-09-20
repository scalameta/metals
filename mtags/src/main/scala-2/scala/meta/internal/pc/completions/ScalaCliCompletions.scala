package scala.meta.internal.pc.completions

import scala.collection.JavaConverters._

import scala.meta.internal.mtags.BuildInfo
import scala.meta.internal.pc.MetalsGlobal
import scala.meta.internal.tokenizers.Chars

import org.eclipse.{lsp4j => l}

trait CliCompletions {
  this: MetalsGlobal =>
  class CliExtractor(pos: Position, text: String) {
    def unapply(path: List[Tree]): Option[String] =
      path match {
        case Nil =>
          if (!text.stripLeading().startsWith("//")) None
          else {
            val directive = text.take(pos.point).split("//").last
            if (directive.exists(Chars.isLineBreakChar(_))) None
            else {
              val reg = """>\s*using\s+lib\s+"?(.*)"?""".r
              directive match {
                case reg(dep) => Some(dep.stripPrefix("\"").stripSuffix("\""))
                case _ => None
              }
            }
          }
        case _ => None
      }
  }

  case class ScalaCliCompletions(
      pos: Position,
      text: String,
      dependency: String
  ) extends CompletionPosition {

    override def contribute: List[Member] = {
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
        if (dependency.endsWith(":") && dependency.count(_ == ':') == 1)
          completions(dependency + ":").map(":" + _)
        else List.empty
      val editStart = {
        var i = pos.point - 1
        while (
          i >= 0 && {
            val c = text.charAt(i)
            (Chars.isIdentifierPart(c) || c == '.' || c == '-')
          }
        ) { i -= 1 }
        i + 1
      }
      val editEnd = {
        var i = pos.point
        val textLen = text.length()
        while (
          i < textLen && {
            val c = text.charAt(i)
            (Chars.isIdentifierPart(c) || c == '.' || c == '-')
          }
        ) {
          i += 1
        }
        i
      }
      val editRange = pos.withStart(editStart).withEnd(editEnd).toLsp
      (javaCompletions ++ scalaCompletions)
        .map(insertText =>
          new TextEditMember(
            filterText = insertText,
            edit = new l.TextEdit(editRange, insertText),
            sym = completionsSymbol(insertText),
            label = Some(insertText.stripPrefix(":"))
          )
        )
    }
  }
}
