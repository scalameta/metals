package scala.meta.internal.metals

// TODO is there a better place for this?
object ExcludedPackages {
  val default = List(
    "META-INF", "images", "toolbarButtonGraphics", "jdk", "sun", "javax",
    "oracle", "java.awt.desktop", "org.jcp", "org.omg", "org.graalvm",
    "com.oracle", "com.sun", "com.apple", "apple"
  )
}
