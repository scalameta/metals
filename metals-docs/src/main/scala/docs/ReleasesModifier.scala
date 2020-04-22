package docs

import scala.meta.inputs.Input

import mdoc.Reporter
import mdoc.StringModifier

class ReleasesModifier extends StringModifier {
  override val name: String = "releases"
  override def process(info: String, code: Input, reporter: Reporter): String =
    Docs.releasesTable
}
