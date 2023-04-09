package tests

import scala.meta.internal.builds.GradleBuildTool

import coursierapi.Credentials
import coursierapi.IvyRepository
import coursierapi.MavenRepository
import coursierapi.Repository

class GradleRepositoriesTest extends BaseSuite {

  check(
    Nil,
    """|  repositories {
       |    mavenCentral()
       |  }
       |""".stripMargin,
  )

  check(
    List(
      Repository.central(),
      Repositories.google,
      Repositories.bintrayIvy("testId"),
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
    List(
      Repository.central(),
      Repositories.google.withCredentials(Repositories.testCredentials()),
      Repositories
        .bintrayIvy("testId")
        .withCredentials(Repositories.testCredentials()),
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
      repos: List[Repository],
      expected: String,
  ): Unit = {
    test(expected) {
      val obtained = GradleBuildTool.toGradleRepositories(repos)
      assertNoDiff(obtained, expected)
    }
  }
}

object Repositories {
  val defaultIvyPattern =
    "[organisation]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]"
  def google: MavenRepository =
    MavenRepository.of("https://maven.google.com")
  def bintrayIvy(id: String): IvyRepository =
    IvyRepository.of(
      s"https://dl.bintray.com/${id.stripSuffix("/")}/" +
        defaultIvyPattern
    )
  def testCredentials(): Credentials =
    Credentials.of("testUser", "testPassword")
}
