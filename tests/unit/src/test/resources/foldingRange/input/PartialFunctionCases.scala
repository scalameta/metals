class A {
  val tryCatch =
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

  val patternMatching = ??? match {
    case 0 =>
    case 1 => println()
    case 2 => println()
      println()
    case 3 =>
      println()
    case _ => println()
  }

  val foo = Seq().map{
    case 0 =>
    case 1 => println()
    case 2 => println()
      println()
    case 3 =>
      println()
    case _ => println()
  }
}
