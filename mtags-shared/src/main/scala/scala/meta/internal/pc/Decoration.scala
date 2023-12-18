package scala.meta.internal.pc

import scala.meta.pc.SyntheticDecoration

import org.eclipse.{lsp4j => l}

case class Decoration(
    range: l.Range,
    label: String,
    kind: Int
) extends SyntheticDecoration
