package example

class TryCatch/*TryCatch.scala*/ {
  try {
    val x/*TryCatch.semanticdb*/ = 2
    x/*TryCatch.semanticdb*/ +/*Int.scala*/ 2
  } catch {
    case t/*TryCatch.semanticdb*/: Throwable/*package.scala*/ =>
  } finally {
    val text/*TryCatch.semanticdb*/ = ""
  }
}
