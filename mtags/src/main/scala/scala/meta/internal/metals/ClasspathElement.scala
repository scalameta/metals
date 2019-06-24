package scala.meta.internal.metals

sealed abstract class ClasspathElementPart
case class PackageElementPart(name: String) extends ClasspathElementPart
case class ClassfileElementPart(name: String) extends ClasspathElementPart
