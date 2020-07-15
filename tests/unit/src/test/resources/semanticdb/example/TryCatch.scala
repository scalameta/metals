package example

class TryCatch/*example.TryCatch#*/ {
  try {
    val x/*local0*/ = 2
    x/*local0*/ +/*scala.Int#`+`(+4).*/ 2
  } catch {
    case t/*local1*/: Throwable/*scala.package.Throwable#*/ =>
      t/*local1*/.printStackTrace/*java.lang.Throwable#printStackTrace().*/()
  } finally {
    val text/*local2*/ = ""
    text/*local2*/ +/*java.lang.String#`+`().*/ ""
  }
}
