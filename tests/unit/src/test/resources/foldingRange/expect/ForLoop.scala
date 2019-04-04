class A >>region>>{
  def foo =
    for>>region>> {
      x <- ???
    } <<region<<>>region>>{
      ???
    }<<region<<

  def noSpacing =
    for>>region>>{
      x <- ???
    }<<region<<>>region>>{
      ???
    }<<region<<

  def why =
    for>>region>>

      {
        x <- ???
        y <- ???
        z <- ???
    }

  // yes, it is the body
  <<region<<>>region>>{
    ???
  }<<region<<
}<<region<<
