class A >>region>>{
  val tryCatch =
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

  val patternMatching = ??? match>>region>> {
    case 0 =>
    case 1 => println()
    case 2 => println()
      println()
    case 3 =>
      println()
    case _ => println()
  }<<region<<

  val foo = >>region>>Seq().map{
    case 0 =>
    case 1 => println()
    case 2 => println()
      println()
    case 3 =>
      println()
    case _ => println()
  }<<region<<
}<<region<<
