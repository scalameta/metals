package tests

import scala.meta.internal.builds.GradleBuildTool
import scala.meta.internal.metals.Embedded

import coursier.MavenRepository
import coursier.Repositories
import coursier.core.Authentication
import coursier.ivy.IvyRepository

class GradleRepositoriesTest extends BaseSuite {

  check(
    "basic",
    Nil,
    """|  repositories {
       |    mavenCentral()
       |  }
       |""".stripMargin,
  )

  val userHomeString: String =
    userHome
      .toUri()
      .toString()
      .replace("file:///", "file:/")

  check(
    "default",
    Embedded.repositories,
    s"""|  repositories {
        |    ivy {
        |      url "${userHomeString + ".ivy2/local"}"
        |      patternLayout {
        |        artifact "[organisation]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]"
        |      }
        |    }
        |    mavenCentral()
        |    maven {
        |      url "${userHomeString + ".m2/repository"}"
        |    }
        |    maven {
        |      url "https://central.sonatype.com/repository/maven-snapshots"
        |    }
        |  }
        |""".stripMargin,
  )

  check(
    "custom",
    List(
      Repositories.central,
      GradleRepositoriesTest.google,
      GradleRepositoriesTest.bintrayIvy("testId"),
    ),
    """|  repositories {
       |    mavenCentral()
       |    maven {
       |      url "https://maven.google.com"
       |    }
       |    ivy {
       |      url "https://dl.bintray.com/testId"
       |      patternLayout {
       |        artifact "[organisation]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]"
       |      }
       |    }
       |  }
       |""".stripMargin,
  )

  check(
    "with-authentication",
    List(
      Repositories.central,
      GradleRepositoriesTest.google.withAuthentication(
        Some(Authentication("testUser", "testPassword"))
      ),
      GradleRepositoriesTest
        .bintrayIvy("testId")
        .withAuthentication(Some(Authentication("testUser", "testPassword"))),
    ),
    """|  repositories {
       |    mavenCentral()
       |    maven {
       |      url "https://maven.google.com"
       |      credentials {
       |        username "testUser"
       |        password "testPassword"
       |      }
       |    }
       |    ivy {
       |      url "https://dl.bintray.com/testId"
       |      patternLayout {
       |        artifact "[organisation]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]"
       |      }
       |      credentials {
       |        username "testUser"
       |        password "testPassword"
       |      }
       |    }
       |  }
       |""".stripMargin,
  )

  def check(
      name: String,
      repos: List[coursier.Repository],
      expected: String,
  ): Unit = {
    test(name) {
      val obtained = GradleBuildTool.toGradleRepositories(repos)
      assertNoDiff(obtained, expected)
    }
  }
}

object GradleRepositoriesTest {
  val defaultIvyPattern =
    "[organisation]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]"
  def google: MavenRepository =
    MavenRepository("https://maven.google.com")
  def bintrayIvy(id: String): IvyRepository =
    IvyRepository
      .parse(
        s"https://dl.bintray.com/${id.stripSuffix("/")}/" +
          defaultIvyPattern
      )
      .right
      .get
  // def testCredentials(): Credentials =
  //   Credentials.of("testUser", "testPassword")
}
