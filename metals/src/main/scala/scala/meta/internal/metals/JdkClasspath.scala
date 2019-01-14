package scala.meta.internal.metals

import scala.meta.io.Classpath

object JdkClasspath {
  def bootClasspath: Classpath =
    sys.props
      .collectFirst {
        case (k, v) if k.endsWith(".boot.class.path") => Classpath(v)
      }
      .getOrElse(Classpath(Nil))
}
