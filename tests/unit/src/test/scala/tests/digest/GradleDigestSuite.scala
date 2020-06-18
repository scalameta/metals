package tests
package digest

import scala.meta.internal.builds.GradleDigest
import scala.meta.io.AbsolutePath

class GradleDigestSuite extends BaseDigestSuite {

  override def digestCurrent(
      root: AbsolutePath
  ): Option[String] = GradleDigest.current(root)

  checkSame(
    "solo-build.gradle",
    """
      |/build.gradle
      |def x = 2
    """.stripMargin,
    """
      |/build.gradle
      |def x = 2
    """.stripMargin
  )

  /**
   * @tgodzik Currently we do not support multiline comments since regexes might be too compilacted
   * and we do not have tokenizer for gradle.
   */
  checkDiff(
    "multiline-comment-diff",
    """
      |/build.gradle
      |def x = 2 /* this
      |is a multiline comment*/
    """.stripMargin,
    """
      |/build.gradle
      |def x = 2
    """.stripMargin
  )

  checkSame(
    "comments-whitespace",
    """
      |/build.gradle
      |def x = 2
    """.stripMargin,
    """
      |/build.gradle
      |def x =
      | 2 // this is two
    """.stripMargin
  )

  checkSame(
    "comments-multi",
    """
      |/build.gradle
      |def x = 2
    """.stripMargin,
    """
      |/build.gradle
      |def x =
      | 2 // this is two
    """.stripMargin
  )

  checkDiff(
    "significant-tokens",
    """
      |/build.gradle
      |def x = 2
    """.stripMargin,
    """
      |/build.gradle
      |def x = 2
      |def y = 3
    """.stripMargin
  )

  checkDiff(
    "plugin-not-ignored",
    """
      |/build.gradle
      |def x = 2
    """.stripMargin,
    """
      |/build.gradle
      |def x = 2
      |/buildSrc/Plugin.kts
      |package a.b
      |class A
    """.stripMargin
  )

  checkDiff(
    "subprojects-not-ignored",
    """
      |/build.gradle
      |def x = 2
    """.stripMargin,
    """
      |/build.gradle
      |def x = 2
      |/a/build.gradle
      |def y = 1
    """.stripMargin
  )

  checkDiff(
    "subsubprojects-not-ignored",
    """
      |/build.gradle
      |def x = 2
    """.stripMargin,
    """
      |/build.gradle
      |def x = 2
      |/a/b/c/build.gradle
      |def y = 1
    """.stripMargin
  )
}
