package tests

import com.geirsson.coursiersmall.CoursierSmall
import com.geirsson.coursiersmall.Dependency
import com.geirsson.coursiersmall.Settings
import scala.meta.internal.metals.JdkSources
import scala.meta.io.AbsolutePath
import scala.meta.io.Classpath

object Libraries {

  def suite: List[Library] = {
    val settings = new Settings()
      .withDependencies(
        List(
          new Dependency(
            "org.scalameta",
            "scalameta_2.12",
            "3.2.0"
          ),
          new Dependency(
            "org.scalameta",
            "scalameta_2.12",
            "3.2.0"
          ),
          new Dependency(
            "com.typesafe.akka",
            "akka-testkit_2.12",
            "2.5.9"
          ),
          new Dependency(
            "org.apache.spark",
            "spark-sql_2.11",
            "2.2.1"
          ),
          new Dependency(
            "org.eclipse.jetty",
            "jetty-servlet",
            "9.3.11.v20160721"
          ),
          new Dependency(
            "org.apache.kafka",
            "kafka_2.12",
            "1.0.0"
          ),
          new Dependency(
            "org.apache.flink",
            "flink-parent",
            "1.4.1"
          ),
          new Dependency(
            "io.grpc",
            "grpc-all",
            "1.10.0"
          ),
          new Dependency(
            "io.buoyant",
            "linkerd-core_2.12",
            "1.4.3"
          )
        )
      )
      .withClassifiers(List("sources", "_"))
    val jars = CoursierSmall.fetch(settings)
    val (sources, classpath) =
      jars.partition(_.getFileName.toString.endsWith("-sources.jar"))
    List(
      Library(
        "suite",
        () => Classpath(classpath.map(AbsolutePath(_))),
        () => Classpath(sources.map(AbsolutePath(_)))
      )
    )
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
    val settings = new Settings()
      .withDependencies(List(new Dependency(organization, artifact, version)))
    def fetch(s: Settings): Classpath = {
      Classpath(CoursierSmall.fetch(s).map(AbsolutePath(_)))
    }
    Library(
      List(organization, artifact, version).mkString(":"),
      classpath = () => fetch(settings),
      sources = () => fetch(settings.withClassifiers(List("sources")))
    )
  }

  lazy val jdk: Library = {
    val bootClasspath = Classpath(
      sys.props
        .collectFirst { case (k, v) if k.endsWith(".boot.class.path") => v }
        .getOrElse("")
    ).entries.filter(_.isFile)
    Library(
      "JDK",
      () => Classpath(bootClasspath),
      () => Classpath(JdkSources().toList)
    )
  }
  lazy val scalaLibrary: Library = Library(
    "org.scala-lang",
    "scala-library",
    scala.util.Properties.versionNumberString
  )
}
