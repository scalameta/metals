package tests

import coursierapi.Dependency
import coursierapi.Fetch
import scala.meta.internal.metals.JdkSources
import scala.meta.internal.metals.PackageIndex
import scala.meta.internal.mtags
import scala.meta.io.AbsolutePath
import scala.meta.io.Classpath
import scala.collection.JavaConverters._

case class Library(
    name: String,
    classpath: Classpath,
    sources: Classpath
)

object Library {
  def jdk: Library =
    Library(
      "JDK",
      Classpath(PackageIndex.bootClasspath),
      Classpath(JdkSources().get :: Nil)
    )
  def cats: Seq[AbsolutePath] =
    fetch("org.typelevel", "cats-core_2.12", "2.0.0-M4")
  def all: List[Library] = {
    val fetch = Fetch
      .create()
      .withMainArtifacts()
      .withClassifiers(Set("sources", "_").asJava)
      .withDependencies(
        List(
          Dependency.of("com.lihaoyi", "acyclic_2.12", "0.1.8"),
          Dependency.of("com.lihaoyi", "scalaparse_2.12", "2.1.0"),
          Dependency
            .of("com.typesafe.akka", "akka-cluster_2.12", "2.5.19"),
          Dependency.of("com.typesafe.akka", "akka-stream_2.12", "2.5.19"),
          Dependency
            .of("com.typesafe.akka", "akka-testkit_2.12", "2.5.19"),
          Dependency.of("io.buoyant", s"linkerd-core_2.12", "1.4.3"),
          Dependency.of("io.grpc", "grpc-all", "1.10.0"),
          Dependency.of("org.apache.flink", "flink-parent", "1.4.1"),
          Dependency.of("org.apache.kafka", "kafka_2.12", "1.0.0"),
          Dependency.of("org.apache.spark", "spark-sql_2.11", "2.2.1"),
          Dependency
            .of("org.eclipse.jetty", "jetty-servlet", "9.3.11.v20160721"),
          Dependency.of("org.scalameta", "scalameta_2.12", "4.1.4"),
          Dependency.of(
            "org.scala-lang",
            "scala-compiler",
            mtags.BuildInfo.scalaCompilerVersion
          )
        ): _*
      )
    val jars = fetch
      .fetch()
      .asScala
      .map(_.toPath)
    val (sources, classpath) =
      jars.partition(_.getFileName.toString.endsWith("-sources.jar"))
    List(
      Library(
        "suite",
        Classpath(classpath.map(AbsolutePath(_)).toList),
        Classpath(sources.map(AbsolutePath(_)).toList)
      )
    )
  }

  def fetch(org: String, artifact: String, version: String): Seq[AbsolutePath] =
    Fetch
      .create()
      .withDependencies(
        Dependency.of(org, artifact, version).withTransitive(false)
      )
      .fetch()
      .asScala
      .map(f => AbsolutePath(f.toPath))
}
