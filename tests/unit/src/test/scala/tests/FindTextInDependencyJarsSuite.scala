package tests

import java.net.URI

import scala.meta.internal.metals.Directories
import scala.meta.internal.metals.InitializationOptions
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.PositionSyntax._
import scala.meta.internal.semver.SemVer
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.Location

class FindTextInDependencyJarsSuite
    extends BaseLspSuite("find-text-in-dependency-jars") {

  val akkaVersion = "2.6.16"

  override protected def initializationOptions: Option[InitializationOptions] =
    Some(TestingServer.TestDefault)

  test(
    "find exact string match in .conf file inside jar",
    withoutVirtualDocs = true,
  ) {
    val isJavaAtLeast9 = scala.util.Properties.isJavaAtLeast(9.toString)
    val isJavaAtLeast17 = scala.util.Properties.isJavaAtLeast(17.toString)

    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "2.12.4",
           |    "libraryDependencies": ["com.typesafe.akka::akka-actor-typed:2.6.16"]
           |  }
           |}
        """.stripMargin
      )
      akkaLocations <- server.findTextInDependencyJars(
        include = ".conf",
        pattern = "jvm-shutdown-hooks",
      )
      jdkLocations <- server.findTextInDependencyJars(
        include = ".java",
        pattern = "public String(StringBuffer buffer) {",
      )
    } yield {

      assertLocations(
        akkaLocations,
        """|akka-actor_2.12-2.6.16.jar/reference.conf:96:3: info: result
           |  jvm-shutdown-hooks = on
           |  ^^^^^^^^^^^^^^^^^^
           |akka-actor_2.12-2.6.16.jar/reference.conf:1178:41: info: result
           |    # This property is related to `akka.jvm-shutdown-hooks` above.
           |                                        ^^^^^^^^^^^^^^^^^^
           |akka-actor_2.12-2.6.16-sources.jar/reference.conf:96:3: info: result
           |  jvm-shutdown-hooks = on
           |  ^^^^^^^^^^^^^^^^^^
           |akka-actor_2.12-2.6.16-sources.jar/reference.conf:1178:41: info: result
           |    # This property is related to `akka.jvm-shutdown-hooks` above.
           |                                        ^^^^^^^^^^^^^^^^^^
           |""".stripMargin,
      )

      assertLocations(
        jdkLocations, {
          val line =
            if (
              SemVer.isCompatibleVersion(
                "17.0.8",
                // for 17.0.8+7 or 17.0.7-7 the suffix is irrelevant here
                scala.util.Properties.javaVersion.replaceAll("(\\+|\\-).*", ""),
              )
            ) 1449
            else if (isJavaAtLeast17) 1445
            else if (isJavaAtLeast9) 626
            else 578

          val pathPrefix =
            if (isJavaAtLeast9) "/java.base/"
            else "/"

          s"""|src.zip${pathPrefix}java/lang/String.java:$line:5: info: result
              |    public String(StringBuffer buffer) {
              |    ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
              |""".stripMargin
        },
      )
    }
  }

  private def assertLocations(
      locations: List[Location],
      expected: String,
  ): Unit = {
    val rendered = locations
      .flatMap { loc =>
        val uri = URI.create(loc.getUri())
        val input = if (uri.getScheme() == "jar") {
          val jarPath = uri.toAbsolutePath
          val relativePath =
            s"${jarPath.jarPath.map(_.filename).getOrElse("")}${jarPath}"
          jarPath.toInput.copy(path = relativePath.toString)
        } else {
          val path = AbsolutePath.fromAbsoluteUri(uri)
          val relativePath = path
            .toRelative(workspace.resolve(Directories.dependencies))
            .toString()
            .replace("\\", "/")
          path.toInput.copy(path = relativePath.toString)
        }
        loc
          .getRange()
          .toMeta(input)
      }
      .map(_.formatMessage("info", "result"))
      .mkString("\n")
    assertNoDiff(rendered, expected)
  }
}
