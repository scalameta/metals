package scala.meta.metals.maven

import java.io.File
import java.{util => ju}

import scala.jdk.CollectionConverters._

import org.apache.maven.project.MavenProject
import org.codehaus.plexus.util.xml.Xpp3Dom

private[maven] object MavenSourceRoots {

  import MavenPluginSupport._

  def existingSources(
      roots: ju.List[String],
      project: MavenProject,
      isTest: Boolean,
  ): List[String] = {
    val declared = roots.asScala
      .map(new File(_))
      .filter(_.exists())
      .map(_.getAbsolutePath)
      .toList

    val scalaDir =
      new File(
        project.getBasedir,
        if (isTest) "src/test/scala" else "src/main/scala",
      )
    val scalaFallback =
      if (scalaDir.isDirectory && !declared.contains(scalaDir.getAbsolutePath))
        List(scalaDir.getAbsolutePath)
      else Nil

    val generatedParent = new File(
      project.getBasedir,
      if (isTest) "target/generated-test-sources"
      else "target/generated-sources",
    )
    val generated =
      if (generatedParent.isDirectory)
        generatedSourceRootsFromDirectory(
          generatedParent,
          isTest,
          declared.toSet,
        )
      else Nil

    (declared ++ scalaFallback ++ configuredGeneratedSourceRoots(
      project,
      isTest,
    ) ++ generated).distinct
  }

  private def generatedSourceRootsFromDirectory(
      parent: File,
      isTest: Boolean,
      declared: Set[String],
  ): List[String] = {
    val children = Option(parent.listFiles()).toList.flatten
      .flatMap(d => if (d.isDirectory) sourceRootCandidate(d, isTest) else None)
      .map(_.getAbsolutePath)
      .filterNot(declared)
    val parentRoot =
      if (
        !declared(parent.getAbsolutePath) && containsSourceFile(
          parent,
          1,
        )
      )
        List(parent.getAbsolutePath)
      else Nil
    parentRoot ++ children
  }

  private def sourceRootCandidate(dir: File, isTest: Boolean): Option[File] = {
    val generatedSourceSearchDepth = 15
    val sub = if (isTest) "src/test" else "src/main"
    Seq("java", "scala")
      .map(lang => new File(dir, s"$sub/$lang"))
      .find(d =>
        d.isDirectory && containsSourceFile(d, generatedSourceSearchDepth)
      )
      .orElse(
        if (containsSourceFile(dir, generatedSourceSearchDepth)) Some(dir)
        else None
      )
  }

  private def containsSourceFile(dir: File, maxDepth: Int): Boolean =
    maxDepth > 0 &&
      Option(dir.listFiles()).toList.flatten.exists {
        case f if f.isFile =>
          f.getName.endsWith(".java") || f.getName.endsWith(
            ".scala"
          ) || f.getName.endsWith(".kt") || f.getName.endsWith(".groovy")
        case d if d.isDirectory => containsSourceFile(d, maxDepth - 1)
        case _ => false
      }

  private def configuredGeneratedSourceRoots(
      project: MavenProject,
      isTest: Boolean,
  ): List[String] = {
    val plugins = effectivePlugins(project)
    val buildDir = project.getBuild.getDirectory

    def rootFromConfig(
        cfg: Option[Xpp3Dom],
        configPath: Seq[String],
    ): Option[String] =
      cfg
        .flatMap { dom =>
          configPath
            .foldLeft(Option(dom): Option[Xpp3Dom])((opt, key) =>
              opt.flatMap(d => Option(d.getChild(key)))
            )
            .flatMap(d => Option(d.getValue).filter(_.nonEmpty))
        }
        .map(v => absolutePath(interpolatePath(v, project), project))

    def generatorRoots(
        pluginKey: String,
        defaultDir: => String,
        configPath: Seq[String],
        goals: Set[String] = Set.empty,
        strictDefault: Boolean = false,
    ): List[String] =
      Option(plugins.get(pluginKey)).toList.flatMap { plugin =>
        val allExecs = plugin.getExecutions.asScala.toList
        val matching =
          if (goals.isEmpty) allExecs
          else allExecs.filter(_.getGoals.asScala.exists(goals))

        val explicit = (
          matching.flatMap(e =>
            rootFromConfig(mergedPluginConfiguration(plugin, e), configPath)
          ) ++
            rootFromConfig(
              Option(plugin.getConfiguration).map(_.asInstanceOf[Xpp3Dom]),
              configPath,
            )
        ).distinct

        if (explicit.nonEmpty) explicit
        else if (
          strictDefault && allExecs.nonEmpty && matching.isEmpty && goals.nonEmpty
        ) Nil
        else List(absolutePath(defaultDir, project))
      }

    val antlrRoots =
      if (!isTest)
        generatorRoots(
          Antlr4MavenPlugin,
          defaultDir = new File(buildDir, "generated-sources/antlr4").getPath,
          configPath = Seq("outputDirectory"),
          goals = Set("antlr4"),
        )
      else Nil

    val modelloRoots =
      if (!isTest)
        generatorRoots(
          ModelloMavenPlugin,
          defaultDir = new File(buildDir, "generated-sources/modello").getPath,
          configPath = Seq("outputDirectory"),
          goals = Set("java"),
        )
      else Nil

    val buildHelperRoots =
      Option(plugins.get(BuildHelperMavenPlugin)).toList.flatMap { plugin =>
        val goal = if (isTest) "add-test-source" else "add-source"
        plugin.getExecutions.asScala.toList
          .filter(_.getGoals.asScala.contains(goal))
          .flatMap(e => mergedPluginConfiguration(plugin, e))
          .flatMap(buildHelperSources(_, project))
      }

    val annotationRoots: List[String] = {
      val cfgKey =
        if (isTest) "generatedTestSourcesDirectory"
        else "generatedSourcesDirectory"
      val compilerCfg = Option(plugins.get(JavaCompilerPlugin))
        .flatMap(plugin => compilerPluginConfiguration(plugin, isTest))
      val apActive =
        !compilerCfg.flatMap(childText(_, "proc")).contains("none") &&
          compilerCfg.exists(cfg =>
            childText(cfg, cfgKey).isDefined ||
              Option(cfg.getChild("annotationProcessors")).isDefined ||
              Option(cfg.getChild("annotationProcessorPaths")).isDefined
          )
      if (apActive)
        generatorRoots(
          JavaCompilerPlugin,
          defaultDir = new File(
            buildDir,
            if (isTest) "generated-test-sources/test-annotations"
            else "generated-sources/annotations",
          ).getPath,
          configPath = Seq(cfgKey),
        )
      else Nil
    }

    val protobufRoots: List[String] = {
      val side =
        if (isTest) "generated-test-sources/protobuf"
        else "generated-sources/protobuf"
      val javaRoots = generatorRoots(
        ProtobufMavenPlugin,
        defaultDir = new File(buildDir, s"$side/java").getPath,
        configPath = Seq("outputDirectory"),
        goals =
          if (isTest) Set("test-compile", "test-compile-custom")
          else Set("compile", "compile-custom"),
        strictDefault = isTest,
      )
      if (javaRoots.nonEmpty)
        (javaRoots :+ absolutePath(
          new File(buildDir, s"$side/grpc-java").getPath,
          project,
        )).distinct
      else Nil
    }

    (antlrRoots ++ modelloRoots ++ buildHelperRoots ++ annotationRoots ++ protobufRoots).distinct
  }

  private def buildHelperSources(
      cfg: Xpp3Dom,
      project: MavenProject,
  ): List[String] =
    Option(cfg.getChild("sources")).toList.flatMap { sources =>
      sources.getChildren("source").toList.map { source =>
        absolutePath(interpolatePath(source.getValue, project), project)
      }
    }
}
