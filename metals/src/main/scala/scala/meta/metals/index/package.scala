package scala.meta.metals

package object index {

  // TODO: remove these forwarders
  @deprecated("Use org.langmeta.lsp.Range instead", "v0.1")
  type Range = org.langmeta.lsp.Range
  @deprecated("Use org.langmeta.lsp.Range instead", "v0.1")
  val Range = org.langmeta.lsp.Range

  @deprecated("Use org.langmeta.lsp.Location instead", "v0.1")
  type Position = org.langmeta.lsp.Location
  @deprecated("Use org.langmeta.lsp.Location instead", "v0.1")
  val Position = org.langmeta.lsp.Location

}
