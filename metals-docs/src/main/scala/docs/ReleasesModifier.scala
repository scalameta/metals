package docs

import mdoc.Reporter
import mdoc.StringModifier
import scala.meta.inputs.Input

class ReleasesModifier extends StringModifier {
  override val name: String = "releases"
  override def process(info: String, code: Input, reporter: Reporter): String =
    Docs.releasesTable
}
