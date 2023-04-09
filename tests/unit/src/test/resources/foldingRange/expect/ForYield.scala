class A >>region>>{
  def foo =>>region>>
    for {
      x <- ???
    } yield {
      ???
    }<<region<<

  def noSpacing =>>region>>
    for{
      x <- ???
    }yield{
      ???
      ???
      ???
    }<<region<<

  def why =>>region>>
    for

      >>region>>{
      x <- ???
      y <- ???
      z <- ???
    }

    // because why not...
      <<region<<yield


  // yes, it is the body
  {
    ???
  }<<region<<
}<<region<<
