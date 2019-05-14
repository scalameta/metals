class A >>region>>{
  // one liner

  def justASeparator1 = ???

  >>comment>>// consecutive
  // line
  // comments

  // another
  // comment
  // section<<comment<<

  def justASeparator2 = ???

  >>comment>>/**
    * Block comment
    */
  /**
    * Consecutive block comment
    */<<comment<<

  def justASeparator3 = ???

  >>comment>>// mixed
  /**
    * comments
    */
  // are cool<<comment<<
}<<region<<
