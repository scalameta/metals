package tests

import org.eclipse.{lsp4j => l}

final case class QuickLocation(
    uri: String,
    range: (Int, Int, Int, Int)
) {
  def toLsp: l.Location = new l.Location(
    uri,
    new l.Range(
      new l.Position(range._1, range._2),
      new l.Position(range._3, range._4)
    )
  )
}
