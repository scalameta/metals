class A >>region>>{
  def foo =>>region>>
    for >>region>>{
      x <- ???
    } <<region<<yield >>region>>{
      ???
    }<<region<<<<region<<

  def noSpacing =>>region>>
    for{
      x <- ???
    }yield>>region>>{
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

    // because why not...
      <<region<<yield


  // yes, it is the body
  >>region>>{
    ???
  }<<region<<<<region<<
}<<region<<
