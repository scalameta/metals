package tests.pc

import java.net.URLClassLoader
import java.nio.file.Path
import java.nio.file.Paths
import tests.BaseSignatureHelpSuite

object HKSignatureHelpSuite extends BaseSignatureHelpSuite {
  override def extraClasspath: List[Path] =
    this.getClass.getClassLoader
      .asInstanceOf[URLClassLoader]
      .getURLs
      .map(url => Paths.get(url.toURI))
      .toList

  check(
    "foldmap",
    """import cats.implicits._
      |import cats._
      |object a {
      |  Foldable[Option].foldMap(a @@)
      |}
      |""".stripMargin,
    """|foldMap[A, B](fa: Option[A])(f: A => B)(implicit B: Monoid[B]): B
       |              ^^^^^^^^^^^^^
       |""".stripMargin
  )

}
