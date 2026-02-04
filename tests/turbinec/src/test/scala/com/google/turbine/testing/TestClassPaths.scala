package com.google.turbine.testing

import com.google.common.base.Splitter
import com.google.common.collect.ImmutableList
import com.google.turbine.binder.ClassPath
import com.google.turbine.binder.ClassPathBinder
import com.google.turbine.binder.JimageClassBinder
import com.google.turbine.options.LanguageVersion
import com.google.turbine.options.TurbineOptions
import java.io.File
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Optional
import scala.jdk.CollectionConverters._

object TestClassPaths {
  private val classPathSplitter =
    Splitter.on(File.pathSeparatorChar).omitEmptyStrings()

  val bootClasspath: ImmutableList[Path] =
    classPathSplitter
      .splitToStream(Optional.ofNullable(System.getProperty("sun.boot.class.path")).orElse(""))
      .map((path: String) => Paths.get(path))
      .filter((path: Path) => Files.exists(path))
      .collect(ImmutableList.toImmutableList())

  val turbineBootClasspath: ClassPath = getTurbineBootclasspath()

  private def getTurbineBootclasspath(): ClassPath = {
    try {
      if (!bootClasspath.isEmpty) {
        ClassPathBinder.bindClasspath(bootClasspath)
      } else {
        JimageClassBinder.bindDefault()
      }
    } catch {
      case e: IOException =>
        e.printStackTrace()
        throw new UncheckedIOException(e)
    }
  }

  def optionsWithBootclasspath(): TurbineOptions.Builder = {
    val options = TurbineOptions.builder()
    if (!bootClasspath.isEmpty) {
      val entries = bootClasspath.asScala.map(_.toString).asJava
      options.setBootClassPath(ImmutableList.copyOf(entries))
    } else {
      options.setLanguageVersion(LanguageVersion.fromJavacopts(ImmutableList.of("--release", "8")))
    }
    options
  }
}
