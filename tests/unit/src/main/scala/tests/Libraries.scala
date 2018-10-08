package tests

import java.nio.file.Files
import java.nio.file.Paths
import scala.meta.io.AbsolutePath
import scala.meta.io.Classpath

object Libraries {



  lazy val suite: List[Library] = {
    val buf = List.newBuilder[Library]
    buf += Library.jdk
    buf += Library.scalaLibrary
    buf += Library(
      "org.scalameta",
      "scalameta_2.12",
      "3.2.0",
      provided = List(
        ModuleID.scalaReflect("2.12.7")
      )
    )
    buf += Library("com.typesafe.akka", "akka-testkit_2.12", "2.5.9")
    buf += Library("com.typesafe.akka", "akka-actor_2.11", "2.5.9")
    buf += Library(
      "org.apache.spark",
      "spark-sql_2.11",
      "2.2.1",
      provided = List(
        ModuleID(
          "org.eclipse.jetty",
          "jetty-servlet",
          "9.3.11.v20160721"
        )
      )
    )
    buf += Library("org.apache.kafka", "kafka_2.12", "1.0.0")
    buf += Library("org.apache.flink", "flink-parent", "1.4.1")
    buf += Library("io.grpc", "grpc-all", "1.10.0")
    buf += Library("io.buoyant", "linkerd-core_2.12", "1.4.3")
    buf.result
  }
}

case class Library(
    name: String,
    classpath: () => Classpath,
    sources: () => Classpath
)
object Library {
  def apply(
      organization: String,
      artifact: String,
      version: String,
      provided: List[ModuleID] = Nil
  ): Library = {
    def fetch(sources: Boolean) = {
      val jars =
        Jars.fetch(organization, artifact, version, fetchSourceJars = sources)
      Classpath(jars)

    }
    Library(
      List(organization, artifact, version).mkString(":"),
      classpath = () => fetch(sources = false),
      sources = () => fetch(sources = true)
    )
  }

  def jdkSources: Option[AbsolutePath] =
    for {
      javaHome <- sys.props.get("java.home")
      jdkSources = Paths.get(javaHome).getParent.resolve("src.zip")
      if Files.isRegularFile(jdkSources)
    } yield AbsolutePath(jdkSources)



  lazy val jdk: Library = {
    val bootClasspath = Classpath(
      sys.props
        .collectFirst { case (k, v) if k.endsWith(".boot.class.path") => v }
        .getOrElse("")
    ).entries.filter(_.isFile)
    Library(
      "JDK",
      () => Classpath(bootClasspath),
      () => Classpath(jdkSources.toList)
    )
  }
  lazy val scalaLibrary: Library = Library(
    "org.scala-lang",
    "scala-library",
    scala.util.Properties.versionNumberString
  )
}
