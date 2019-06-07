---
id: gradle
title: Gradle
---

Gradle is a build tool that can be used easily with a large number of
programming languages including Scala. With it you can easily define your
builds for Groovy or Kotlin, which enables for a high degree of customization.
You can look up all the possible features on the
[Gradle website](https://gradle.org/).

## Automatic installation

The first time you open Metals in a new workspace it prompts you to import the
build. Select "Import build" to start automatic installation. After it's
finished you should be able edit and compile your code.

## Manual installation

In a highly customized workspaces it might not be possible to use automatic
import. In such cases there is a number of steps required to generate the needed
Bloop config.

First we need to add the Bloop plugin dependency to the project, it should be
included in the buildscript section:

```groovy
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath 'ch.epfl.scala:gradle-bloop_2.11:@@BLOOP_VERSION@@'
    }
}
```

Secondly, we need to enable the plugin for all the projects we want to include,
it's easiest to define it for `allprojects`:

```groovy
allprojects {
   apply plugin: bloop.integrations.gradle.BloopPlugin
}
```

Now we can run `gradle bloopInstall`, which will create a Bloop configuration
files. This will enable us to work with Metals and most features will work, but
for everything to work properly we need to also add the SemanticDB plugin. This
can be done by adding a couple of options to the scala compiler:

```groovy
allprojects {
    afterEvaluate {
        configurations {
            scalaCompilerPlugin
        }
        dependencies {
            scalaCompilerPlugin "org.scalameta:semanticdb-scalac_$scalaVersion:@@SCALAMETA_VERSION@@"
        }
        def pluginFile = project.configurations.scalaCompilerPlugin.find {
            it.name.contains("semanticdb")
        }
        if (!pluginFile) {
            throw new RuntimeException("SemanticDB plugin not found!")
        }
        tasks.withType(ScalaCompile) {
            def params = [
                '-Xplugin:' + pluginFile.absolutePath,
                '-P:semanticdb:synthetics:on',
                '-P:semanticdb:failures:warning',
                '-P:semanticdb:sourceroot:' + project.rootProject.projectDir,
                '-Yrangepos',
                '-Xplugin-require:semanticdb'
            ]
            if (scalaCompileOptions.additionalParameters)
                scalaCompileOptions.additionalParameters += params
            else
                scalaCompileOptions.additionalParameters = params
        }
    }
}
```

You need to also define `scalaVersion` which corresponds to the scala version in
your project. We also use afterEvaluate here so that we have scala dependency
and all build specific scala compiler options defined before adding the
SemanticDB plugin. It might be not needed in your project depending on the
specifics.

Now you can rerun `gradle bloopInstall` to have a properly configured SemanticDB
plugin in Bloop config.

This is much more complex than in case of the automatic installation, so it is
recommended to only do manual installation when having problems with the
automatic one. You can also always try to reach us on the Metals gitter channel
in case of any problems.
