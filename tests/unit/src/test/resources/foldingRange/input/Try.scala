class A{
  val shortExpr =
    try ???
    catch {
      case 0 =>
      case 1 => println()
      case 2 =>
        println()
        println()
        println()
      case 3 =>
        println()
      case _ => println()
    }

  val aTry =
    try {
      ???
      ???
      ???
      ???
    } catch {
      case 0 =>
      case 1 => println()
      case 2 =>
        println()
        println()
        println()
      case 3 =>
        println()
      case _ => println()
    } finally {
      ???
      ???
      ???
      ???
      ???
    }
}
