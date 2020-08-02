package tests

import java.nio.file.Files
import java.nio.file.Paths

import scala.util.Try

import scala.meta.internal.builds.NewProjectProvider
import scala.meta.internal.metals.InitializationOptions
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsInputBoxParams
import scala.meta.internal.metals.MetalsInputBoxResult
import scala.meta.internal.metals.MetalsQuickPickItem
import scala.meta.internal.metals.RecursivelyDelete
import scala.meta.internal.metals.ServerCommands
import scala.meta.io.AbsolutePath

import munit.TestOptions
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.ShowMessageRequestParams

class NewProjectLspSuite extends BaseLspSuite("new-project") {

  override def initializationOptions: Option[InitializationOptions] =
    Some(
      InitializationOptions.Default
        .copy(inputBoxProvider = Some(true), openNewWindowProvider = Some(true))
    )

  def scalatestTemplate(name: String = "scalatest-example"): String =
    s"""|/$name/build.sbt
        |lazy val root = (project in file(".")).
        |  settings(
        |    inThisBuild(List(
        |      organization := "com.example",
        |      scalaVersion := "2.13.1"
        |    )),
        |    name := "scalatest-example"
        |  )
        |
        |libraryDependencies += "org.scalatest" %% "scalatest" % "3.1.0" % Test
        |
        |
        |/$name/project/build.properties
        |sbt.version=1.3.10
        |
        |
        |/$name/src/main/scala/CubeCalculator.scala
        |object CubeCalculator extends App {
        |  def cube(x: Int) = {
        |    x * x * x
        |  }
        |}
        |
        |/$name/src/test/scala/CubeCalculatorTest.scala
        |class CubeCalculatorTest extends org.scalatest.funsuite.AnyFunSuite {
        |  test("CubeCalculator.cube") {
        |    assert(CubeCalculator.cube(3) === 27)
        |  }
        |}
        |
        |""".stripMargin

  check("basic-template")(
    pickedProject = Some("scala/scalatest-example.g8"),
    name = None,
    expectedContent = scalatestTemplate()
  )

  check("custom-name")(
    pickedProject = Some("scala/scalatest-example.g8"),
    name = Some("my-custom-name"),
    expectedContent = scalatestTemplate("my-custom-name")
  )

  check("custom-template")(
    pickedProject = None,
    name = Some("my-custom-name"),
    customTemplate = Some("scala/scalatest-example.g8"),
    expectedContent = scalatestTemplate("my-custom-name")
  )

  check("website-template")(
    pickedProject = None,
    name = None,
    templateFromg8Site = Some("scala/scalatest-example.g8"),
    expectedContent = scalatestTemplate()
  )

  test("template-regex") {
    val text =
      """|# Templates:
         |- [jimschubert/finatra.g8](https://github.com/jimschubert/finatra.g8)
         |(A simple Finatra 2.5 template with sbt-revolver and sbt-native-packager)
         |
         |- [jimschubert/finatra.g8](https://github.com/jimschubert/finatra.g8)
         |A simple Finatra 2.5 template with sbt-revolver and sbt-native-packager
         |- [lagom/lagom-java.g8](https://github.com/lagom/lagom-java.g8/)
         |(A [Lagom](https://www.lagomframework.com/) Java seed template for sbt)
         |Some other text
         |
         |- [jimschubert/finatra.g8](https://github.com/jimschubert/finatra.g8)
         |
         |- [earldouglas/xsbt-web-plugin.g8](https://github.com/earldouglas/xsbt-web-plugin.g8) (Seed Template for [xsbt-web-plugin](https://github.com/earldouglas/xsbt-web-plugin))
         |Some other text
         |- [elbaulp/elbaulp-scala.g8](https://github.com/elbaulp/elbaulp-scala.g8) (Simple Scala project with Property Checks, ScalaTest and log4j)
         |- [adinapoli/sbt-revolver.g8](https://github.com/adinapoli/scala-sbt-revolver.g8)
         |(**2012**. Generic sbt project with sbt-revolver)
         |""".stripMargin

    val obtained = NewProjectProvider.templatesFromText(text, "")

    assertEquals(
      obtained,
      List(
        MetalsQuickPickItem(
          id = "jimschubert/finatra.g8",
          label = "jimschubert/finatra.g8",
          description =
            "A simple Finatra 2.5 template with sbt-revolver and sbt-native-packager"
        ),
        MetalsQuickPickItem(
          id = "jimschubert/finatra.g8",
          label = "jimschubert/finatra.g8",
          description =
            "A simple Finatra 2.5 template with sbt-revolver and sbt-native-packager"
        ),
        MetalsQuickPickItem(
          id = "lagom/lagom-java.g8",
          label = "lagom/lagom-java.g8",
          description =
            "A [Lagom](https://www.lagomframework.com/) Java seed template for sbt"
        ),
        MetalsQuickPickItem(
          id = "jimschubert/finatra.g8",
          label = "jimschubert/finatra.g8",
          description = ""
        ),
        MetalsQuickPickItem(
          id = "earldouglas/xsbt-web-plugin.g8",
          label = "earldouglas/xsbt-web-plugin.g8",
          description =
            "Seed Template for [xsbt-web-plugin](https://github.com/earldouglas/xsbt-web-plugin)"
        ),
        MetalsQuickPickItem(
          id = "elbaulp/elbaulp-scala.g8",
          label = "elbaulp/elbaulp-scala.g8",
          description =
            "Simple Scala project with Property Checks, ScalaTest and log4j"
        ),
        MetalsQuickPickItem(
          id = "adinapoli/sbt-revolver.g8",
          label = "adinapoli/sbt-revolver.g8",
          description = "**2012**. Generic sbt project with sbt-revolver"
        )
      )
    )
  }

  test("full-page") {
    val obtained = NewProjectProvider.templatesFromText(
      NewProjectLspSuite.stableWikiPage,
      ""
    )
    assertEquals(
      obtained.size,
      161,
      "We expected 161 templates to be extracted from the sample wiki page."
    )
  }

  private def check(testName: TestOptions)(
      pickedProject: Option[String],
      name: Option[String],
      expectedContent: String,
      customTemplate: Option[String] = None,
      templateFromg8Site: Option[String] = None
  ): Unit =
    test(testName) {

      val tmpDirectory =
        AbsolutePath(Files.createTempDirectory("metals")).dealias

      def isSelectProject(params: ShowMessageRequestParams): Boolean =
        params.getMessage() == Messages.NewScalaProject.selectTheTemplate

      def isEnterTemplate(params: MetalsInputBoxParams): Boolean =
        params.prompt == Messages.NewScalaProject.enterG8Template

      def isOpenWindow(params: ShowMessageRequestParams): Boolean =
        params.getMessage() == Messages.NewScalaProject
          .askForNewWindowParams()
          .getMessage()

      def isChooseName(params: MetalsInputBoxParams): Boolean =
        params.prompt == Messages.NewScalaProject.enterName

      def onSelectProject(findAction: String => Option[MessageActionItem]) = {
        if (customTemplate.isDefined) {
          findAction(NewProjectProvider.custom.id)
        } else if (templateFromg8Site.isDefined) {
          findAction(NewProjectProvider.more.id)
            .orElse(findAction(templateFromg8Site.get))
        } else {
          findAction(pickedProject.getOrElse(""))
        }
      }

      def onDirectorySelect(
          params: ShowMessageRequestParams,
          findAction: String => Option[MessageActionItem]
      ) = {
        val file =
          Try(Paths.get(params.getMessage())).map(AbsolutePath.apply).toOption
        file.flatMap { path =>
          if (path == tmpDirectory) {
            val res = findAction("ok")
            res
          } else {
            val tmpRoot = tmpDirectory.root
            if (tmpRoot == path.root) {
              val next =
                tmpDirectory.toRelative(path).toNIO.iterator().next().filename
              findAction(next)
            } else {
              findAction(tmpRoot.get.toString()).orElse(findAction(".."))
            }

          }
        }
      }

      client.showMessageRequestHandler = { params =>
        val findAction = { (name: String) =>
          params.getActions().asScala.find(_.getTitle() == name)
        }
        if (isSelectProject(params)) {
          onSelectProject(findAction)
        } else if (isOpenWindow(params)) {
          findAction("Yes")
        } else {
          onDirectorySelect(params, findAction)
        }
      }

      client.inputBoxHandler = { params =>
        if (isChooseName(params)) {
          Some(
            new MetalsInputBoxResult(value = name.getOrElse(params.value))
          )
        } else if (isEnterTemplate(params)) {
          Some(
            new MetalsInputBoxResult(value = customTemplate.get)
          )
        } else {
          None
        }
      }

      def ignoreVersions(text: String) =
        text.replaceAll("\\d+\\.\\d+\\.\\d+", "1.0.0").replace("\r\n", "\n")

      def directoryOutput(dir: AbsolutePath) = {
        dir.listRecursive.toList
          .sortBy(_.toString())
          .collect {
            case file if (file.isFile) =>
              s"""|/${file
                .toRelative(tmpDirectory)
                .toString()
                .replace('\\', '/')}
                  |${file.readText}
                  |""".stripMargin
          }
          .mkString("\n")
      }

      val testFuture = for {
        _ <- server.initialize(s"""
                                  |/metals.json
                                  |{
                                  |  "a": { }
                                  |}
                                  |""".stripMargin)
        _ <-
          server
            .executeCommand(
              ServerCommands.NewScalaProject.id
            )
        output = directoryOutput(tmpDirectory)
      } yield assertDiffEqual(
        ignoreVersions(output),
        ignoreVersions(expectedContent),
        // note(@tgodzik) The template is pretty stable for last couple of years except for versions.
        "This test is based on https://github.com/scala/scalatest-example.g8, it might have changed."
      )

      testFuture.onComplete {
        case _ =>
          RecursivelyDelete(tmpDirectory)
      }
      testFuture
    }

}

object NewProjectLspSuite {
  val stableWikiPage: String =
    """|
       |
       |## Templates from official sources
       |
       |Here are some templates maintained by the developers of the projects that are up to date.
       |
       |#### foundweekends
       |
       |- [foundweekends/giter8.g8](https://github.com/foundweekends/giter8.g8)
       |(A template for Giter8 templates)
       |
       |#### Scala
       |
       |- [scala/scala-seed.g8](https://github.com/scala/scala-seed.g8)
       |(Seed template for Scala)
       |- [scala/hello-world.g8](https://github.com/scala/hello-world.g8)
       |(A minimal Scala application)
       |- [scala/scalatest-example.g8](https://github.com/scala/scalatest-example.g8)
       |(A template for trying out ScalaTest)
       |
       |#### Functional Programming (Various)
       |
       |- [justinhj/fp-starter-pack.g8](https://github.com/justinhj/fp-starter-pack.g8)
       |(A template for Cats, Zio, Monix and Fs2 setup + ammonite REPL)
       |
       |#### ZIO
       |- [Clover-Group/zio-template.g8](https://github.com/Clover-Group/zio-template.g8)
       |(A minimal ZIO application)
       |- [Clover-Group/zio-cats.g8](https://github.com/Clover-Group/zio-cats.g8)
       |(A minimal ZIO application with Cats integration)
       |- [ScalaConsultants/zio-akka-quickstart.g8](https://github.com/ScalaConsultants/zio-akka-quickstart.g8)
       |(Basic Scala application build using ZIO, Akka HTTP and Slick)
       |
       |#### Dotty
       |
       |- [lampepfl/dotty.g8](https://github.com/lampepfl/dotty.g8)
       |(simple dotty-compiled sbt project template)
       |- [lampepfl/dotty-cross.g8](https://github.com/lampepfl/dotty-cross.g8)
       |(Dotty and Scala 2 cross-compiled sbt template)
       |
       |#### Akka
       |
       |- [akka/akka-quickstart-scala.g8](https://github.com/akka/akka-quickstart-scala.g8)
       |(Akka Quickstart with Scala)
       |- [akka/akka-quickstart-java.g8](https://github.com/akka/akka-quickstart-java.g8)
       |(Akka Quickstart with Java)
       |- [akka/akka-http-quickstart-scala.g8](https://github.com/akka/akka-http-quickstart-scala.g8)
       |(Akka HTTP Quickstart in Scala)
       |- [akka/akka-http-quickstart-java.g8](https://github.com/akka/akka-http-quickstart-java.g8)
       |(Akka HTTP Quickstart in Java)
       |
       |#### Play
       |
       |- [playframework/play-scala-seed.g8](https://github.com/playframework/play-scala-seed.g8)
       |(Play Scala Seed Template)
       |- [playframework/play-java-seed.g8](https://github.com/playframework/play-java-seed.g8)
       |(Play Java Seed template)
       |
       |#### Lagom
       |
       |- [lagom/lagom-scala.g8](https://github.com/lagom/lagom-scala.g8/)
       |(A [Lagom](https://www.lagomframework.com/) Scala seed template for sbt)
       |- [lagom/lagom-java.g8](https://github.com/lagom/lagom-java.g8/)
       |(A [Lagom](https://www.lagomframework.com/) Java seed template for sbt)
       |
       |#### Scala Native
       |
       |- [scala-native/scala-native.g8](https://github.com/scala-native/scala-native.g8)
       |(Scala Native)
       |- [portable-scala/sbt-crossproject.g8](https://github.com/portable-scala/sbt-crossproject.g8)
       |(sbt-crosspoject)
       |
       |#### Scalafix
       |
       |- [scalacenter/scalafix.g8](https://github.com/scalacenter/scalafix.g8)
       |(Template to start with scalafix rewrite development)
       |
       |#### http4s
       |
       |- [http4s/http4s.g8](https://github.com/http4s/http4s.g8)
       |(http4s services)
       |
       |#### Unfiltered
       |
       |- [unfiltered/unfiltered.g8](https://github.com/unfiltered/unfiltered.g8)
       |([Unfiltered](http://unfiltered.ws/) application)
       |- [unfiltered/unfiltered-netty.g8](http://github.com/unfiltered/unfiltered-netty.g8)
       |([Unfiltered](http://unfiltered.ws/) with Netty)
       |- [unfiltered/unfiltered-war.g8](https://github.com/unfiltered/unfiltered-war.g8)
       |([Unfiltered](http://unfiltered.ws/) template to use with servlet containers)
       |- [unfiltered/unfiltered-scalate.g8](https://github.com/unfiltered/unfiltered-scalate.g8)
       |([Unfiltered](http://unfiltered.ws/) with Scalate and Jetty)
       |- [unfiltered/unfiltered-gae.g8](https://github.com/unfiltered/unfiltered-gae.g8)
       |([Unfiltered](http://unfiltered.ws/) with Google App Engine‎)
       |- [unfiltered/unfiltered-oauth-server.g8](https://github.com/unfiltered/unfiltered-oauth-server.g8)
       |([Unfiltered](http://unfiltered.ws/) OAuth server)
       |- [unfiltered/unfiltered-oauth2-server.g8](https://github.com/unfiltered/unfiltered-oauth2-server.g8)
       |([Unfiltered](http://unfiltered.ws/) OAuth2 server)
       |
       |#### Scalatra
       |
       |- [scalatra/scalatra.g8](https://github.com/scalatra/scalatra.g8)
       |(Basic Scalatra project template)
       |
       |#### Spark
       |
       |- [holdenk/sparkProjectTemplate.g8](https://github.com/holdenk/sparkProjectTemplate.g8)
       |(Template for Scala [Apache Spark](http://www.spark-project.org) project).
       |
       |#### Spark Job Server
       |
       |- [spark-jobserver/spark-jobserver.g8](https://github.com/spark-jobserver/spark-jobserver.g8)
       |(Template for Spark Jobserver)
       |
       |#### lift-ng
       |
       |- [joescii/lift-ng.g8](https://github.com/joescii/lift-ng.g8)
       |(A template for getting off the ground with Scala, Lift, and AngularJS)
       |
       |#### ScalaFX
       |
       |- [scalafx/scalafx.g8](https://github.com/scalafx/scalafx.g8)
       |(Creates a ScalaFX project with build support of sbt and dependencies.)
       |
       |- [sfxcode/sapphire-sbt.g8](https://github.com/sfxcode/sapphire-sbt.g8)
       |(Creates MVC ScalaFX App based on [sapphire-core](https://sfxcode.github.io/sapphire-core)
       |
       |#### Kiama
       |
       |- [inkytonik/kiama.g8](https://github.com/inkytonik/kiama.g8)
       |(Kiama-based Scala projects)
       |
       |#### sbt-typescript
       |
       |- [joost-de-vries/play-angular-typescript.g8](https://github.com/joost-de-vries/play-angular-typescript.g8)
       |(Play Typescript Angular2 application)
       |- [joost-de-vries/play-reactjs-typescript.g8](https://github.com/joost-de-vries/play-reactjs-typescript.g8)
       |(Play Typescript ReactJs Typescript)
       |
       |#### React
       |- [ddanielbee/typescript-react.g8](https://github.com/ddanielbee/typescript-react.g8)(React + Typescript application)
       |
       |#### Udash
       |
       |- [UdashFramework/udash.g8](https://github.com/UdashFramework/udash.g8)
       |(Udash with configured Scala.js, Spring, Jetty and sbt-native-packager)
       |
       |#### Gatling
       |
       |- [gatling/gatling.g8](https://github.com/gatling/gatling.g8) (Gatling performance test suite)
       |
       |#### xsbt-web-plugin
       |
       |- [earldouglas/xsbt-web-plugin.g8](https://github.com/earldouglas/xsbt-web-plugin.g8) (Seed Template for [xsbt-web-plugin](https://github.com/earldouglas/xsbt-web-plugin))
       |## Third-party and/or old templates
       |
       |Giter8 has been around since 2010, so some templates are fairly new while others are outdated. We've put last updated year on some of them (e.g. **2012**).
       |
       |#### sbt plugin
       |
       |- [sbt/sbt-autoplugin.g8](https://github.com/sbt/sbt-autoplugin.g8)
       |(sbt 0.13.5+ AutoPlugin)
       |- [akiomik/sbt-plugin.g8](https://github.com/akiomik/sbt-plugin.g8)
       |(**2013**. A template for sbt plugins)
       |
       |#### Play
       |
       |- [ict-group/ict-play-template.g8](https://github.com/ict-group/ict-play-template.g8)
       |(A Play, Scalikejdbc, Swagger REST CRUD template)
       |- [xuwei-k/play.g8](https://github.com/xuwei-k/play.g8)
       |(Play project template for Scala users)
       |- [guardian/scala-play.g8](https://github.com/guardian/scala-play.g8)
       |(Play g8 template)
       |- [lloydmeta/ctdi-play.g8](https://github.com/lloydmeta/ctdi-play.g8)
       |(Compile-time DI Play template. Includes test harnesses.)
       |- [lloydmeta/slim-play.g8](https://github.com/lloydmeta/slim-play.g8)
       |(A slim Play app that is almost Sinatra-like.)
       |- [muya/play-sbt-scala-seed.g8.g8](https://github.com/muya/play-sbt-scala-seed.g8)
       |(Scala Play Framework template with SBT Layout)
       |- [sameergarg/scala-play-macwire-reactivemongo.g8](https://github.com/sameergarg/scala-play-macwire-reactivemongo.g8)
       |(**2014**. Play 2.3, Scala, macwire and reactive mongo)
       |- [tysonjh/playJavaBootstrap.g8](https://github.com/tysonjh/playJavaBootstrap.g8)
       |(**2014**. Play 2.2, Java, with Twitter Bootstrap)
       |- [mbseid/play-mongo-securesocial.g8](https://github.com/mbseid/play-mongo-securesocial.g8)
       |(**2013**. Play 2.1 application. Comes rolled with Salat driver for MongoDB, and SecureSocial for authentication.)
       |- [polentino/play-gradle-template.g8](https://github.com/polentino/play-gradle-template.g8) **2020** Play 2.6 template that will setup also Liquibase, H2/MySQL, Swagger-UI and, if enabled, also Typescript client API generation from swagger definition and inclusion in a simple Angular project to showcase its usage.
       |
       |#### Akka
       |
       |- [orhanbalci/akka-http-microservice.g8](https://github.com/orhanbalci/akka-http-microservice.g8)
       |(Akka Http Microservice template)
       |- [silvaren/akka-http.g8](https://github.com/silvaren/akka-http.g8)
       |(Akka HTTP microservice with easy start/stop controls)
       |- [ScalaWilliam/akka-stream-kafka-template.g8](https://github.com/ScalaWilliam/akka-stream-kafka-template.g8)
       |(Akka Streams and Kafka)
       |- [shigemk2/minimal-akka-scala.g8](https://github.com/shigemk2/minimal-akka-scala.g8)
       |(very simple and minimal akka-scala template)
       |- [mhamrah/sbt.g8](https://github.com/mhamrah/sbt.g8) 
       |(A slimmer version of [ymasory's sbt](https://github.com/ymasory/sbt.g8) project with typesafe config, logback, scalatest and akka. Features a [spray](http://spray.io) option via `g8 mhamrah/sbt -b spray`.)
       |- [cfeduke/akka-scala-sbt.g8](https://github.com/cfeduke/akka-scala-sbt.g8)
       |(**2014**. Akka 2.2, Scala)
       |- [yannick-cw/elm-akka-http.g8](https://github.com/yannick-cw/elm-akka-http.g8)
       |(Elm running on Akka-Http)
       |- [liquidarmour/akka-http-docker.g8](https://github.com/liquidarmour/akka-http-docker.g8)
       |(An Akka HTTP application ready to release and run in Docker)
       |- [innFactory/bootstrap-akka-http.g8](https://github.com/innFactory/bootstrap-akka-http.g8)
       |(**2017** A docker ready akka HTTP template with slick, in-memory postgres for tests, swagger-doc, swagger-ui, hickaricp, Flyway Migration and AWS Cognito Auth)
       |- [innFactory/bootstrap-akka-graphql.g8](https://github.com/innFactory/bootstrap-akka-graphql.g8)
       |(**2017** Like the http microservice template but with sangria graphql support)
       |- [niqdev/akka-seed.g8](https://github.com/niqdev/akka-seed.g8) (**2018** Akka HTTP template)
       |- [Bunyod/akka-http-tapir.g8](https://github.com/Bunyod/akka-http-tapir.g8) (2019 Akka HTTP Swagger REST template. Libs: tapir, pureconfig, cats, circe, jwt, etc)
       |
       |#### Fast data / Spark / Flink
       |
       |- [imarios/frameless.g8](https://github.com/imarios/frameless.g8)
       |(A simple [frameless](https://github.com/adelbertc/frameless) template to start with more expressive types for [Spark](https://github.com/apache/spark))
       |- [tillrohrmann/flink-project.g8](https://github.com/tillrohrmann/flink-project.g8)
       |([Flink](http://flink.apache.org) project.)
       |- [nttdata-oss/basic-spark-project.g8](https://github.com/nttdata-oss/basic-spark-project.g8)
       |([Spark](https://spark.incubator.apache.org/) basic project.)
       |
       |#### Scala.js
       |
       |- [Daxten/bay-scalajs.g8](https://github.com/Daxten/bay-scalajs.g8)
       |(A Scala.js template with scaffolding, postgres, scalajs-react and play-framework in the backen)
       |- [vmunier/play-scalajs.g8](https://github.com/vmunier/play-scalajs.g8)
       |(Template to get started with [Play](https://www.playframework.com/) and [Scala.js](https://www.scala-js.org/))
       |- [vmunier/akka-http-scalajs.g8](https://github.com/vmunier/akka-http-scalajs.g8)
       |(Template to get started with [Akka HTTP](https://doc.akka.io/docs/akka-http/current/scala/http/introduction.html) and [Scala.js](https://www.scala-js.org/))
       |- [VEINHORN/chrome-extension.g8](https://github.com/VEINHORN/chrome-extension.g8)
       |(Template to get started with [Chrome API](https://developer.chrome.com/extensions/getstarted) and [Scala.js](https://www.scala-js.org/))
       |- [dborisenko/scalajs-react-play-material-ui.g8](https://github.com/dborisenko/scalajs-react-play-material-ui.g8)
       |(Client-server application template with Scala.js, React, Material-UI and Play)
       |- [dborisenko/scalajs-react-play-elemental-ui.g8](https://github.com/dborisenko/scalajs-react-play-elemental-ui.g8)
       |(Client-server application template with Scala.js, React, Elemental-UI and Play)
       |- [shadaj/create-react-scala-app.g8](https://github.com/shadaj/create-react-scala-app.g8)
       |(Write React apps in Scala just like you would in ES6)
       |
       |#### Android / mobile
       |
       |- [taisukeoe/android-pfn-app.g8](https://github.com/taisukeoe/android-pfn-app.g8)
       |(Android project in Scala with pfn/android-sdk-plugin)
       |- [aafa/rest-android-scala.g8](https://github.com/aafa/rest-android-scala.g8)
       |(sbt project for Android REST client based on Retrofit with Macroid and Robolectric for testing)
       |- [ajhager/libgdx-sbt-project.g8](https://github.com/ajhager/libgdx-sbt-project.g8)
       |(**2014**. sbt project for developing Scala games using libGDX)
       |- [chototsu/mmstemplandroid.g8](https://github.com/chototsu/mmstemplandroid.g8)
       |(**2013**. Template for MikMikuStudio(Android version).)
       |- [gseitz/android-sbt-project.g8](http://github.com/gseitz/android-sbt-project.g8)
       |(**2011**. sbt project for Android)
       |
       |#### Unfiltered
       |
       |- [xuwei-k/unfiltered-heroku.g8](https://github.com/xuwei-k/unfiltered-heroku.g8)
       |(Unfiltered heroku)
       |- [davececere/unfiltered-squeryl-war.g8](https://github.com/davececere/unfiltered-squeryl-war.g8)
       |(**2013**. Full persistent REST api of a single resource. SBT, Scala, Unfiltered, Squeryl, .war build artifact)
       |- [akollegger/unfiltered-neo4j-on-heroku.g8](https://github.com/akollegger/unfiltered-neo4j-on-heroku.g8)
       |(**2012**. Unfiltered, dispatch to Neo4j, host on Heroku)
       |- [mindcandy/unfiltered-rest-gatling.g8](https://github.com/mindcandy/unfiltered-rest-gatling.g8)
       |(**2012** .sbt project for Unfiltered with Netty, Gatling load testing, and Specs2)
       |- [softprops/unfiltered.g8](http://github.com/softprops/unfiltered.g8)
       |(**2012**. sbt project for Unfiltered with Jetty)
       |- [chrislewis/unfiltered-gae.g8](http://github.com/chrislewis/unfiltered-gae.g8)
       |(**2011**. sbt project for Unfiltered on Google App Engine)
       |
       |#### Scalatra
       |
       |- [takezoe/scalatra-twirl.g8](https://github.com/takezoe/scalatra-twirl.g8)
       |(A web application using Scalatra with Twirl template)
       |- [takezoe/scalatra-scalajs.g8](https://github.com/takezoe/scalatra-scalajs.g8)
       |(A application using Scalatra and Scala.js)
       |- [dkrieg/scalatra-angularjs-seed.g8](https://github.com/dkrieg/scalatra-angularjs-seed.g8.git)
       |(**2013**. Template for web app with Scalatra, AngularJS, AngularUI-Boostrap, CoffeeScript, Less, Jasmine, Scala 2.10.0, sbt 0.12.2.)
       |- [JanxSpirit/scalatra-mongodb.g8](https://github.com/JanxSpirit/scalatra-mongodb.g8)
       |(**2012**. A web application using Scalatra, Casbah and MongoDB - builds with SBT 0.11.x)
       |- [jrevault/scalatra-squeryl.g8](https://github.com/jrevault/scalatra-squeryl.g8)
       |(**2012**. sbt, Scalatra, Squeryl with usage samples, includes several plugins)
       |
       |#### Lift
       |
       |- [eltimn/lift-mongo.g8](https://github.com/eltimn/lift-mongo.g8)
       |(Template to get a Lift-MongoDB webapp up and running quickly.)
       |- [temon/griya-basic.g8](https://github.com/temon/griya-basic.g8)
       |(A template for start your Lift project with Spec2)
       |- [mads379/lift-blank.g8](https://github.com/mads379/lift-blank.g8)
       |(**2012**. Lift minimalistic project)
       |
       |#### Base templates / ScalaTest / Specs2
       |
       |- [aafa/bintray-sbt-template.g8](https://github.com/aafa/bintray-sbt-template.g8)
       |(A Bintray sbt project)
       |- [anvie/multiproject.g8](https://github.com/anvie/multiproject.g8)
       |(A multi project sbt)
       |- [anvie/sbt-simple-project.g8](https://github.com/anvie/sbt-simple-project.g8)
       |(A simple sbt build with a single project.)
       |- [chriscoffey/multi-project.g8](https://github.com/chriscoffey/multi-project.g8)
       |(A multi-project build with packaging and dependencies)
       |- [chrislewis/basic-project.g8](https://github.com/chrislewis/basic-project.g8)
       |(Bootstrapped sbt project with simple configuration)
       |- [DevInsideYou/cat.g8](https://github.com/DevInsideYou/cat.g8)
       |(A template for Clean Architecture)
       |- [djspiewak/base.g8](https://github.com/djspiewak/base.g8)
       |(My cool template)
       |- [fayimora/basic-scala-project.g8](https://github.com/fayimora/basic-scala-project.g8)
       |(A simple Scala project with ScalaTest.)
       |- [ferhtaydn/sbt-skeleton.g8](https://github.com/ferhtaydn/sbt-skeleton.g8)
       |(A simple sbt Scala project with ScalaTest and ScalaCheck.)
       |- [harshad-deo/sbtbase.g8](https://github.com/harshad-deo/sbtbase.g8)
       |(sbt project with linting, code formatting and a few useful plugins)
       |- [j5ik2o/sbt-with-scalafmt.g8](https://github.com/j5ik2o/sbt-with-scalafmt.g8)
       |(sbt with Scalafmt)
       |- [kurochan/java-seed.g8](https://github.com/kurochan/java-seed.g8)
       |(A simple hello world app in Java.)
       |- [lewismj/sbt-project.g8](https://github.com/lewismj/sbt-project.g8)
       |(Modern Scala 2.12 project template, incorporates tut, microsites and default layout, 'core', 'tests', 'doc' etc...)
       |- [lewismj/sbt-template.g8](https://github.com/lewismj/sbt-template.g8)
       |(Multi-module project with application (Scala 2.12, no use of Build.scala))
       |- [lloydmeta/seed-scala.g8](https://github.com/lloydmeta/seed-scala.g8)
       |(Scala skeleton project w/ Scalatest, DocTest, Scalafmt, Wartremover, Coursier and scalac options configured)
       |- [pmandera/basic-scala-project.g8](https://github.com/pmandera/basic-scala-project.g8)
       |(Simple scala project. Fork of [fayimora/basic-scala-project.g8](https://github.com/fayimora/basic-scala-project.g8) using specs2 instead of ScalaTest.)
       |- [rlazoti/scala-sbt.g8](https://github.com/rlazoti/scala-sbt.g8)
       |(An app with ScalaTest, Scala 2.10.2, sbt 0.12.3, sbt-eclipse and sbt-package-dist plugins.)
       |- [kasured/scala-sbt.g8](https://github.com/kasured/scala-sbt.g8)(Giter8 template ships with Scala, SBT, Scalatest, Scala Logging and Cats Core. Package name and versions go with the sensible default values)
       |- [adinapoli/sbt-revolver.g8](https://github.com/adinapoli/scala-sbt-revolver.g8)
       |(**2012**. Generic sbt project with sbt-revolver)
       |- [akiomik/scala-migrations.g8](https://github.com/akiomik/scala-migrations.g8)
       |(**2013**. A template for sbt projects using [scala-migrations](https://code.google.com/p/scala-migrations/).)
       |- [ctranxuan/sbt-multi-modules.g8](https://github.com/ctranxuan/sbt-multi-modules.g8)
       |(**2013**. A template for sbt multi-modules project)
       |- [joescii/scala-oss.g8](https://github.com/joescii/scala-oss.g8)
       |(**2014**. A template for creating an open-source Scala project.)
       |- [ymasory/sbt-code-quality.g8](https://github.com/ymasory/sbt-code-quality.g8)
       |(**2013**. CheckStyle and PMD for Java projects)
       |- [ymasory/sbt.g8](https://github.com/ymasory/sbt.g8)
       |(**2013**. Generic sbt project for projects using GitHub)
       |- [elbaulp/elbaulp-scala.g8](https://github.com/elbaulp/elbaulp-scala.g8) (Simple Scala project with Property Checks, ScalaTest and log4j)
       |
       |#### Scala Native
       |
       |- [jokade/scalanative-cocoa-seed.g8](https://github.com/jokade/scalanative-cocoa-seed.g8)
       |(Template for [Cocoa projects with scala-native](https://github.com/jokade/scalanative-cocoa))
       |
       |#### Others
       |
       |- [adinapoli/scalaz-revolver.g8](https://github.com/adinapoli/scalaz-revolver.g8.git)
       |(Generic sbt project with scalaz 7 and sbt-revolver)
       |- [bneil/finch-skeleton.g8](https://github.com/bneil/finch-skeleton.g8)
       |(A project that centers on finagle/finch using circe, scalaz, scalacheck and shapeless)
       |- [bsamaripa/http4s.g8](https://github.com/bsamaripa/http4s.g8)
       |(A simple [http4s](https://github.com/http4s/http4s) template)
       |- [edinhodzic/kyriakos-rest-micro-service-spray.g8](https://github.com/edinhodzic/kyriakos-rest-micro-service-spray.g8)
       |(REST CRUD microservice based on Sbt, Spray, MongoDb, Kamon metrics)
       |- [guardian/lambda-scala-boilerplate.g8](https://github.com/guardian/lambda-scala-boilerplate.g8)
       |(Scala AWS lambda g8 template)
       |- [jimschubert/finatra.g8](https://github.com/jimschubert/finatra.g8)
       |(A simple Finatra 2.5 template with sbt-revolver and sbt-native-packager)
       |- [joescii/presentera.g8](https://github.com/joescii/presentera.g8)
       |(A template for making awesome presentations with Presentera)
       |- [jmhofer/scalatron-bot.g8](https://github.com/jmhofer/scalatron-bot.g8)
       |(A template for Scalatron bots.)
       |- [julien-truffaut/presentation.g8](https://github.com/julien-truffaut/presentation.g8)
       |(A template using tut and remark.js)
       |- [mefellows/respite-sbt.g8](https://github.com/mefellows/respite-sbt.g8)
       |(sbt project for the [Respite](https://github.com/mefellows/respite) REST micro-framework)
       |- [polymorphic/gatling-simulation-template.g8](https://github.com/polymorphic/gatling-simulation-template.g8)
       |(Gatling simulations)
       |- [ripla/vaadin-scala.g8](https://github.com/ripla/vaadin-scala.g8)
       |(Template to easily get started with a Scala Vaadin project using the Scaladin add-on.)
       |- [anvie/spray-rest-sbt-0.11.2.g8](https://github.com/anvie/spray-rest-sbt-0.11.2.g8)
       |(**2012**. sbt template for Spray Rest sbt 0.11.2 included eclipse and idea plugins.)
       |- [chototsu/mmstempl.g8](https://github.com/chototsu/mmstempl.g8)
       |(**2013**. Template for MikMikuStudio.)
       |- [codesolid/cassandra.g8](https://github.com/codesolid/cassandra.g8)
       |(**2014**. giter8 project featuring Cassandra in a simple ScalaTest project, with TypeSafe configuration)
       |- [exu/phalcon.g8.git](https://exu@github.com/exu/phalcon.g8.git)
       |(**2012**. CPhalcon basic template)
       |- [gfrison/proto-app.g8](https://github.com/gfrison/proto-app.g8)
       |(**2013**. Archetype of any Java or Groovy standalone application based on Spring (Grails DSL) and Gradle)
       |- [giTorto/template-openRefine-extension.g8](https://github.com/giTorto/template-openRefine-extension.g8)
       |(**2014**. Template to easily get started with an OpenRefine extension.)
       |- [jackywyz/sbtweb-app.g8](https://github.com/jackywyz/sbtweb-app.g8)
       |(**2014**. sbt web project basic template)
       |- [jeffling/sbt-finagle.g8](https://github.com/jeffling/sbt-finagle.g8)
       |(**2013**. Finagle Thrift template (with scrooge) with subprojects for the interface and server for easy deploying and publishing.)
       |- [jimschubert/finatra-app.g8](https://github.com/jimschubert/finatra-app.g8)
       |(**2014**. A template for a Finatra 1.5.3 Application using the bower/Bootstrap template provided by Finatra)
       |- [jrudolph/scalac-plugin.g8](http://github.com/jrudolph/scalac-plugin.g8)
       |(**2012**. sbt project for Scala Compiler Plugins)
       |- [marceloemanoel/gradle-plugin-template.g8](https://github.com/marceloemanoel/gradle-plugin-template.g8)
       |(**2013**. Gradle plugin template with info for deploying to Maven Central.)
       |- [matlockx/simple-gradle-scala.g8](https://github.com/matlockx/simple-gradle-scala.g8)
       |(**2013**. A Scala project with Gradle and Heroku support.)
       |- [mesosphere/scala-sbt-mesos-framework.g8](https://github.com/mesosphere/scala-sbt-mesos-framework.g8)
       |(**2014**. A bare-bones Apache Mesos framework project in Scala.)
       |- [n8han/giter8.g8](http://github.com/n8han/giter8.g8)
       |(**2014**. sbt project for new giter8 templates)
       |- [non/caliper.g8](https://github.com/non/caliper.g8)
       |(**2013**. Template for writing micro-benchmarks using Caliper.)
       |- [omphe/ansible_project.g8](https://github.com/omphe/ansible_project.g8)
       |(**2014**. A template for [Ansible](http://www.ansible.com/home) projects according to [best-practice directory layouts](http://docs.ansible.com/playbooks_best_practices.html#directory-layout))
       |- [omphe/cr_scala_cli.g8](https://github.com/omphe/cr_scala_cli.g8)
       |(**2014**. Simple Scala CLI app template with Scallop option handling)
       |- [philcali/lwjgl-project.g8](https://github.com/philcali/lwjgl.g8)
       |(**2012**. sbt project with LWJGL integration)
       |- [semberal/slick-sbt-project.g8](https://github.com/semberal/slick-sbt-project.g8)
       |(**2014**. A template for Slick database access library with BoneCP connection pool and Typesafe config)
       |- [Jannyboy11/ScalaDemoPlugin.g8](https://github.com/Jannyboy11/ScalaDemoPlugin.g8)
       |(**2018**. A template for Bukkit plugins in Scala)
       |- [softprops/chrome-plugin.g8](https://github.com/softprops/chrome-plugin.g8)
       |(**2012**. Google Chrome Plugin g8 template)
       |- [tboloo/scala-jna-project.g8](https://github.com/tboloo/scala-jna-project.g8)
       |(**2014**. A sample scala project for accessing native libraries with [JNA](https://github.com/twall/jna))
       |- [tysonjh/scalaMacro.g8](https://github.com/tysonjh/scalaMacro.g8)
       |(**2014**. A template for Scala Macros using the Macro Paradise plugin.)
       |- [tysonjh/sprayTwirlTemplate.g8](https://github.com/tysonjh/sprayTwirlTemplate.g8)
       |(**2014**. A template for spray-can with twirl, templating, assembly, revolver (Scala))
       |- [spotify/scio.g8](https://github.com/spotify/scio.g8)
       |(**2017**. A template for [Scio](https://github.com/spotify/scio))
       |- [yarenty/h2o-spark-seed.g8](https://github.com/yarenty/h2o-spark-seed.g8)
       |(**2017**. Starting template for Sparkling-Water(H2O) Machine Learning framework on Spark (Scala))
       |- [yarenty/tensorflow-scala-seed.g8](https://github.com/yarenty/tensorflow-scala-seed.g8)
       |(**2017**. Template for *production like project* of Tensorflow using scala.)
       |- [Biacco42/scala-jfx-jre-pack.g8](https://github.com/Biacco42/scala-jfx-jre-pack.g8)
       |(**2020**. A template for OpenJFX. This also includes a release command that builds a package composed of your application jar, minimized JRE, and launch script.)
       |
       |### Searching GitHub
       |
       |Searching GitHub with [g8](https://github.com/search?o=desc&q=g8&s=stars&type=Repositories) as keyword, you can discover interesting templates.
       |""".stripMargin

}
