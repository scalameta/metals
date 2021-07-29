package scala.meta.internal.metals.formatting

trait FormatterParams {
  def startPos: meta.Position
  def endPos: meta.Position
  def sourceText: String
}
