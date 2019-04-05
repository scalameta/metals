class A >>region>>{
  val tryCatch =
    try ???
    catch {
      case 0 =>
      case 1 =>>>region>> println()<<region<<
      case 2 =>>>region>>
        println()
        println()
        println()<<region<<
      case 3 =>>>region>>
        println()<<region<<
      case _ =>>>region>> println()<<region<<
    }

  val patternMatching = ??? match>>region>> {
    case 0 =>
    case 1 =>>>region>> println()<<region<<
    case 2 =>>>region>> println()
      println()<<region<<
    case 3 =>>>region>>
      println()<<region<<
    case _ =>>>region>> println()<<region<<
  }<<region<<

  val foo = Seq().map{
    case 0 =>
    case 1 =>>>region>> println()<<region<<
    case 2 =>>>region>> println()
      println()<<region<<
    case 3 =>>>region>>
      println()<<region<<
    case _ =>>>region>> println()<<region<<
  }
}<<region<<
