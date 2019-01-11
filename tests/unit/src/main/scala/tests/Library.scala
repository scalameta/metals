package tests

import com.geirsson.coursiersmall.CoursierSmall
import com.geirsson.coursiersmall.Dependency
import com.geirsson.coursiersmall.Settings
import scala.meta.internal.metals.JdkSources
import scala.meta.io.AbsolutePath
import scala.meta.io.Classpath

case class Library(
    name: String,
    classpath: () => Classpath,
    sources: () => Classpath
)

object Library {
  def apply(
      organization: String,
      artifact: String,
      version: String
  ): Library = {
    val settings = new Settings()
      .withDependencies(
        List(new Dependency(organization, artifact, version))
      )
    def fetch(settings: Settings): Classpath = {
      Classpath(CoursierSmall.fetch(settings).map(AbsolutePath(_)))
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
