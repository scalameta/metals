package example

class TryCatch/*TryCatch.scala*/ {
  try {
    val x/*TryCatch.semanticdb*/ = 2
    x/*TryCatch.semanticdb*/ +/*Int.scala*/ 2
  } catch {
    case t/*TryCatch.semanticdb*/: Throwable/*package.scala*/ =>
      t/*TryCatch.semanticdb*/.printStackTrace/*Throwable.java*/()
  } finally {
    val text/*TryCatch.semanticdb*/ = ""
    text/*TryCatch.semanticdb*/ +/*String.java fallback to java.lang.String#*/ ""
  }
}
