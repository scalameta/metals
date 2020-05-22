package scala.meta.internal.metals

import scala.meta.internal.metals.MetalsEnrichments._

import org.eclipse.{lsp4j => l}

case class Command(
    id: String,
    title: String,
    description: String,
    arguments: String = "`null`"
) {
  def unapply(string: String): Boolean = string == id

  def toLSP(arguments: List[AnyRef]): l.Command =
    new l.Command(title, id, arguments.asJava)
}
