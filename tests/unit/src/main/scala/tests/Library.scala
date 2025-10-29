package tests

import scala.jdk.CollectionConverters._

import scala.meta.internal.metals.JdkSources
import scala.meta.internal.metals.PackageIndex
import scala.meta.internal.metals.ScalaVersions
import scala.meta.internal.mtags
import scala.meta.io.AbsolutePath
import scala.meta.io.Classpath

import coursierapi.Dependency
import coursierapi.Fetch

case class Library(
    name: String,
    classpath: Classpath,
    sources: Classpath,
) {
  def ++(other: Library): Library =
    Library(
      s"$name++${other.name}",
      classpath ++ other.classpath,
      sources ++ other.sources,
    )
  def asModules: List[mtags.DependencyModule] = {
    for {
      jar <- this.classpath.entries
      sourcesFilename = jar.toNIO.getFileName.toString
        .replace(".jar", "-sources.jar")
      sources = this.sources.entries.find(
        _.toNIO.getFileName.toString == sourcesFilename
      )
      version = jar.toNIO.getParent().getFileName().toString()
      name = jar.toNIO.getParent().getParent().getFileName().toString()
      org = jar.toNIO.getParent.getParent.getParent
        .toString()
        .split("maven2/", 2)(1)
        .replace("/", ".")
      coordinates = mtags.MavenCoordinates(org, name, version)
    } yield mtags.DependencyModule(coordinates, jar, sources)
  }
}

object Library {
  def jdk: Library =
    Library(
      "JDK",
      Classpath(PackageIndex.bootClasspath.map(AbsolutePath.apply)),
      Classpath(JdkSources().right.get :: Nil),
    )

  def catsDependency: Dependency =
    Dependency.of("org.typelevel", "cats-core_2.12", "2.0.0-M4")
  def catsSources: Seq[AbsolutePath] = fetchSources(catsDependency)
  def cats: Seq[AbsolutePath] = fetch(catsDependency)

  def scala3: Library = {
    val binaryVersion =
      ScalaVersions.scalaBinaryVersionFromFullVersion(BuildInfoVersions.scala3)
    val dependencies = List(
      Dependency.of(
        "org.scala-lang",
        s"scala3-compiler_$binaryVersion",
        BuildInfoVersions.scala3,
      ),
      Dependency.of(
        "org.scala-lang",
        s"scala3-library_$binaryVersion",
        BuildInfoVersions.scala3,
      ),
    )
    fetchSources("scala3-suite", dependencies)
  }

  def xnio: Library =
    fetchSources(
      "xnio",
      List(Dependency.of("org.jboss.xnio", "xnio-nio", "3.8.8.Final")),
    )
  def xnio2: Library =
    fetchSources(
      "xnio",
      List(Dependency.of("org.jboss.xnio", "xnio-nio", "3.8.17.Final")),
    )
  def springbootStarterWeb: Library =
    fetchSources(
      "springboot-starter-web",
      List(
        Dependency.of(
          "org.springframework.boot",
          "spring-boot-starter-web",
          "2.7.13",
        )
      ),
    )

  def damlrxjavaSources: List[AbsolutePath] =
    fetchSources(
      "daml-rxjava",
      List(Dependency.of("com.daml", "bindings-rxjava", "2.0.0")),
    ).sources.entries
      .filter(_.toString.endsWith("bindings-rxjava-2.0.0-sources.jar"))

  def allScala2: List[Library] = {
    List(allScala2Library)
  }
  def allScala2Library: Library = {
    import mtags.BuildInfo.scalaCompilerVersion

    val dependencies = List(
      Dependency.of("com.lihaoyi", "acyclic_2.12", "0.1.8"),
      Dependency.of("com.lihaoyi", "scalaparse_2.12", "2.1.0"),
      Dependency.of("com.typesafe.akka", "akka-cluster_2.12", "2.5.19"),
      Dependency.of("com.typesafe.akka", "akka-stream_2.12", "2.5.19"),
      Dependency.of("com.typesafe.akka", "akka-testkit_2.12", "2.5.19"),
      Dependency.of("io.buoyant", "linkerd-core_2.12", "1.4.3"),
      Dependency.of("io.grpc", "grpc-all", "1.10.0"),
      Dependency.of("org.apache.flink", "flink-parent", "1.4.1"),
      Dependency.of("org.apache.kafka", "kafka_2.12", "1.0.0"),
      Dependency.of("org.apache.spark", "spark-sql_2.11", "2.2.1"),
      Dependency.of("org.eclipse.jetty", "jetty-servlet", "9.3.11.v20160721"),
      Dependency.of("org.scalameta", "scalameta_2.12", "4.1.4"),
      Dependency.of("org.scala-lang", "scala-compiler", scalaCompilerVersion),
    )
    fetchSources("scala2-suite", dependencies)
  }

  def fetchSources(name: String, deps: List[Dependency]): Library = {
    val fetch = Fetch
      .create()
      .withMainArtifacts()
      .addClassifiers("sources")
      .withDependencies(
        deps: _*
      )
    val jars = fetch
      .fetch()
      .asScala
      .map(_.toPath)
    val (sources, classpath) =
      jars.partition(_.getFileName.toString.endsWith("-sources.jar"))

    Library(
      name,
      Classpath(classpath.map(AbsolutePath(_)).toList),
      Classpath(sources.map(AbsolutePath(_)).toList),
    )
  }

  def fetchSources(dependency: Dependency): Seq[AbsolutePath] =
    fetch(dependency, Set("sources"))

  def fetch(
      dependency: Dependency,
      classifiers: Set[String] = Set.empty,
  ): Seq[AbsolutePath] =
    Fetch
      .create()
      .withDependencies(dependency.withTransitive(false))
      .withClassifiers(classifiers.asJava)
      .fetch()
      .asScala
      .toSeq
      .map(f => AbsolutePath(f.toPath))
}
