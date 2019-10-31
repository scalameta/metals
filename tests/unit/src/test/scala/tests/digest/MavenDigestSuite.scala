package tests
package digest

import scala.meta.internal.builds.MavenDigest
import scala.meta.io.AbsolutePath

object MavenDigestSuite extends BaseDigestSuite {

  override def digestCurrent(
      root: AbsolutePath
  ): Option[String] = MavenDigest.current(root)

  checkSame(
    "pom.xml",
    """
      |/pom.xml
      |<project>
      |  <modelVersion>4.0.0</modelVersion>
      |  <groupId>com.mycompany.app</groupId>
      |  <artifactId>my-app</artifactId>
      |  <version>1</version>
      |</project>
    """.stripMargin,
    """
      |/pom.xml
      |<project>
      |  <modelVersion>4.0.0</modelVersion>
      |  <groupId>com.mycompany.app</groupId>
      |  <artifactId>my-app</artifactId>
      |  <version>1</version>
      |</project>
    """.stripMargin
  )

  checkSame(
    "comment-diff",
    """
      |/pom.xml
      |<project>
      |  <!--Your comment-->
      |  <modelVersion>4.0.0</modelVersion>
      |  <groupId>com.mycompany.app</groupId>
      |  <artifactId>my-app</artifactId>
      |  <version>1</version>
      |</project>
    """.stripMargin,
    """
      |/pom.xml
      |<project>
      |  <modelVersion>4.0.0</modelVersion>
      |  <groupId>com.mycompany.app</groupId>
      |  <artifactId>my-app</artifactId>
      |  <version>1</version>
      |</project>
    """.stripMargin
  )

  checkSame(
    "whitespace-diff",
    """
      |/pom.xml
      |<project>
      |  <modelVersion>4.0.0</modelVersion>
      |  <groupId>com.mycompany.app</groupId>
      |  <artifactId>my-app</artifactId>
      |  <version>1</version>
      |</project>
    """.stripMargin,
    """
      |/pom.xml
      |<project>
      |  <modelVersion>4.0.0</modelVersion>
      |  <groupId>com.mycompany.app</groupId>
      |  <artifactId>my-app</artifactId>
      |  <version>
      |           1
      |  </version>
      |</project>
    """.stripMargin
  )

  checkDiff(
    "real-change",
    """
      |/pom.xml
      |<project>
      |  <modelVersion>4.0.0</modelVersion>
      |  <groupId>com.mycompany.app</groupId>
      |  <artifactId>my-app</artifactId>
      |  <version>1</version>
      |</project>
    """.stripMargin,
    """
      |/pom.xml
      |<project>
      |  <modelVersion>4.0.0</modelVersion>
      |  <groupId>com.mycompany.app</groupId>
      |  <artifactId>my-app-id</artifactId>
      |  <version>112</version>
      |</project>
    """.stripMargin
  )

  checkDiff(
    "attribute",
    """
      |/pom.xml
      |<project>
      |  <modelVersion name="abc">4.0.0</modelVersion>
      |  <groupId>com.mycompany.app</groupId>
      |  <artifactId>my-app</artifactId>
      |  <version>1</version>
      |</project>
    """.stripMargin,
    """
      |/pom.xml
      |<project>
      |  <modelVersion>4.0.0</modelVersion>
      |  <groupId>com.mycompany.app</groupId>
      |  <artifactId>my-app-id</artifactId>
      |  <version>1</version>
      |</project>
    """.stripMargin
  )

  checkDiff(
    "namespace",
    """
      |/pom.xml
      |<project>
      |  <modelVersion xsi:name="abc">4.0.0</modelVersion>
      |  <groupId>com.mycompany.app</groupId>
      |  <artifactId>my-app</artifactId>
      |  <version>1</version>
      |</project>
    """.stripMargin,
    """
      |/pom.xml
      |<project>
      |  <modelVersion name="abc">4.0.0</modelVersion>
      |  <groupId>com.mycompany.app</groupId>
      |  <artifactId>my-app-id</artifactId>
      |  <version>1</version>
      |</project>
    """.stripMargin
  )

  def projectString: String =
    """
      |<project>
      |<modelVersion>4.0.0</modelVersion>
      |<groupId>com.mycompany.app</groupId>
      |<artifactId>my-app</artifactId>
      |<version>1</version>
      |</project>
  """.stripMargin

  checkDiff(
    "subprojects-not-ignored",
    s"""
       |/pom.xml
       |$projectString
    """.stripMargin,
    s"""
       |/pom.xml
       |$projectString
       |/a/pom.xml
       |$projectString
    """.stripMargin
  )

  checkDiff(
    "subsubprojects-not-ignored",
    s"""
       |/pom.xml
       |$projectString
    """.stripMargin,
    s"""
       |/pom.xml
       |$projectString
       |/a/b/pom.xml
       |$projectString
    """.stripMargin
  )
}
