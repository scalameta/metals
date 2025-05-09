class A >>region>>{
  val field = >>region>>{




    ???
  }<<region<<

  def method(a:Int) = >>region>>{





  }<<region<<

  def longSingleLineBlock = { ???; ???; ???; }

  def chain =>>region>> Seq(1).map{
    x =>
    >>region>>x + 1
     + 1
     + 1
     + 1
     + 1
     + 1
     + 1<<region<<
  }.map>>region>>{
    >>region>>_ + 1
    + 1
    + 1
    + 1
    + 1
    + 1<<region<<
  }<<region<<<<region<<

  def chain =>>region>> Seq(1).map(
    x =>
    >>region>>x + 1
     + 1
     + 1
     + 1
     + 1
     + 1
     + 1<<region<<
  ).map>>region>>(
    x => >>region>>x + 1
    + 1
    + 1
    + 1
    + 1
    + 1<<region<<
  )<<region<<<<region<<

  def mappedSequence =>>region>> Seq().map {
    x => x.toString()




  }<<region<<

  def collapsedBlock = {}

  class CollapsedType {}
}<<region<<
