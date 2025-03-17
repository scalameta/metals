package tests

import java.io.File

import scala.meta.internal.metals.Embedded
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import coursierapi.IvyRepository
import coursierapi.MavenRepository

class CredentialsSuite extends BaseSuite {

  val cache: File = coursier.paths.CoursierPaths.configDirectories().toSeq.head
  val props: AbsolutePath = AbsolutePath(
    cache.toPath().resolve("credentials.properties")
  )

  override def afterEach(context: AfterEach): Unit = {
    super.afterEach(context)
    props.delete()
  }
  override def beforeEach(context: BeforeEach): Unit = {
    super.beforeEach(context)
    props.writeText(
      s"""|simple.username=rob
          |simple.password=my-pass
          |simple.host=artifacts.foo.com
          |""".stripMargin
    )
  }
  test("create-and-verify-credentials-maven") {
    val repo = MavenRepository.of(
      "https://artifacts.foo.com"
    )

    val withCreds = Embedded.setCredentials(repo)

    withCreds match {
      case mvn: MavenRepository =>
        assert(mvn.getCredentials() != null)
        assertEquals(mvn.getCredentials().getUser(), "rob")
        assertEquals(mvn.getCredentials().getPassword(), "my-pass")
    }
  }

  test("create-and-verify-credentials-maven-wrong") {
    val repo = MavenRepository.of(
      "https://artifacts.foo2.com"
    )

    val withCreds = Embedded.setCredentials(repo)

    withCreds match {
      case mvn: MavenRepository =>
        assert(
          mvn.getCredentials() == null,
          "We should not add credentials for wrong url",
        )
    }
  }

  test("create-and-verify-credentials-ivy") {
    val repo = IvyRepository.of(
      "https://artifacts.foo.com/[module]-[revision]/[module]-[revision]-bin.[ext]"
    )

    val withCreds = Embedded.setCredentials(repo)

    withCreds match {
      case ivy: IvyRepository =>
        assert(ivy.getCredentials() != null)
        assertEquals(ivy.getCredentials().getUser(), "rob")
        assertEquals(ivy.getCredentials().getPassword(), "my-pass")
    }
  }

  test("create-and-verify-credentials-ivy-wrong") {
    val repo = IvyRepository.of(
      "https://artifacts.foo2.com/[module]-[revision]/[module]-[revision]-bin.[ext]"
    )

    val withCreds = Embedded.setCredentials(repo)

    withCreds match {
      case ivy: IvyRepository =>
        assert(
          ivy.getCredentials() == null,
          "We should not add credentials for wrong url",
        )
    }
  }
}
