class A>>region>>{
  val shortExpr =
    try ???
    catch>>region>> {
      case 0 =>
      case 1 => println()
      case 2 =>>>region>>
        println()
        println()
        println()
<<region<<      case 3 =>
        println()
      case _ => println()
    }<<region<<

  val aTry =
    try >>region>>{
      ???
      ???
      ???
      ???
    }<<region<< catch>>region>> {
      case 0 =>
      case 1 => println()
      case 2 =>>>region>>
        println()
        println()
        println()
<<region<<      case 3 =>
        println()
      case _ => println()
    }<<region<< finally >>region>>{
      ???
      ???
      ???
      ???
      ???
    }<<region<<
}<<region<<
