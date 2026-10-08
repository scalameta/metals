package scala.meta.internal.metals.config

case class JavacServicesOverrides(
    names: Boolean,
    attr: Boolean,
    typeEnter: Boolean,
    enter: Boolean,
) extends scala.meta.pc.JavacServicesOverridesConfig

object JavacServicesOverrides {
  def default: JavacServicesOverrides =
    JavacServicesOverrides(
      names = true,
      attr = true,
      typeEnter = true,
      enter = true,
    )
}
