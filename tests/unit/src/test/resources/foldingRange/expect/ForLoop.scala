class A >>region>>{
  def foo =>>region>>
    for >>region>>{
      x <- ???
    } <<region<<>>region>>{
      ???
    }<<region<<<<region<<

  def noSpacing =>>region>>
    for>>region>>{
      x <- ???
    }<<region<<>>region>>{
      ???
      ???
      ???
      ???
    }<<region<<<<region<<

  def why =>>region>>
    for

      >>region>>{
        x <- ???
        y <- ???
        z <- ???
    }

  // yes, it is the body
  <<region<<>>region>>{
    ???
  }<<region<<<<region<<
}<<region<<
