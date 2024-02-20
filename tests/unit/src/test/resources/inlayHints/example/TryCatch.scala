package example

class TryCatch {
  try {
    val x/*: Int<<scala/Int#>>*/ = 2
    x + 2
  } catch {
    case t: Throwable =>
      t.printStackTrace()
  } finally {
    val text/*: String<<java/lang/String#>>*/ = ""
    text + ""
  }
}