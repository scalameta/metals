class A >>region>>{
  val tryCatch =
    try>>region>> ???<<region<<
    catch {
      case 0 =>
      case 1 => println()
      case 2 =>>>region>>
        println()
        println()
        println()
<<region<<      case 3 =>
        println()
      case _ => println()
    }

  val patternMatching = ??? match>>region>> {
    case 0 =>
    case 1 => println()
    case 2 => println()
      println()
    case 3 =>
      println()
    case _ => println()
  }<<region<<

  val foo = Seq().map>>region>>{
    case 0 =>
    case 1 => println()
    case 2 => println()
      println()
    case 3 =>
      println()
    case _ => println()
  }<<region<<
}<<region<<
