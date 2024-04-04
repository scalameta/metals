package scala.meta.internal.metals

trait JvmTarget {

  def classpath: Option[List[String]]

  def classDirectory: String
}
