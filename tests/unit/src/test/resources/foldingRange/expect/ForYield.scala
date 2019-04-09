class A >>region>>{
  def foo =
    for>>region>> {
      x <- ???
    } <<region<<yield {
      ???
    }

  def noSpacing =
    for{
      x <- ???
    }yield>>region>>{
      ???
      ???
      ???
    }<<region<<

  def why =
    for>>region>>

      {
      x <- ???
      y <- ???
      z <- ???
    }

    // because why not...
      <<region<<yield


  // yes, it is the body
  {
    ???
  }
}<<region<<
