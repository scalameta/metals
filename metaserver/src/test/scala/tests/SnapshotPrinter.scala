package tests

import scala.collection.JavaConverters._
import scala.meta.Lit
import com.typesafe.config.Config
import com.typesafe.config.ConfigList
import com.typesafe.config.ConfigObject
import com.typesafe.config.ConfigValue
import org.typelevel.paiges.Doc

/**
 * Utility to render HOCON config to pretty multiline config.
 *
 * The default HOCON rendered escapes newlines in strings,
 * which makes it impractical to use for snapshot testing.
 */
object SnapshotPrinter {
  import Doc._
  def render(config: Config): String = {
    render(config.root())
  }
  def render(root: ConfigObject): String = {
    def unsupported(what: String) =
      throw new UnsupportedOperationException(what)
    def literal(string: String): Doc =
      string match {
        case "null" | "false" | "true" | "on" | "off" =>
          text("\"" + string + "\"")
        case els =>
          if (string.forall(_.isLetter)) text(string)
          else if (string.contains('\n')) text("\"\"\"" + string + "\"\"\"")
          else text("\"" + string + "\"")
      }
    def loop(value: ConfigValue): Doc = value match {
      case c: ConfigObject => unsupported(c.toString)
      case c: ConfigList => unsupported(c.toString)
      case _ =>
        value.unwrapped() match {
          case s: String => literal(s)
          case els => unsupported("" + els)
        }
    }
    val doc =
      intercalate(lineBreak, root.keySet().asScala.toSeq.sorted.map { key =>
        literal(key) + text(" = ") + loop(root.get(key))
      })
    doc.render(-1) // force lines to break
  }
}
