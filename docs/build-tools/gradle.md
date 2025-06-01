---
id: gradle
title: Gradle
---

Gradle is a build tool that can be used easily with a large number of
programming languages including Scala. With it you can easily define your builds
for Groovy or Kotlin, which enables for a high degree of customization. You can
look up all the possible features on the [Gradle website](https://gradle.org/).

```scala mdoc:automatic-installation:Gradle
```
## Manual installation

In a highly customized workspaces it might not be possible to use automatic
import. In such cases it's quite simple to add the capability to generate the
needed Bloop config.

First we need to add the Bloop plugin dependency to the project. It should be
included in the buildscript section:

```groovy
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath 'ch.epfl.scala:gradle-bloop_2.12:@BLOOP_VERSION@'
    }
}
```

Secondly, we need to enable the plugin for all the projects we want to include.
It's easiest to define it for `allprojects`:

```groovy
allprojects {
   apply plugin: bloop.integrations.gradle.BloopPlugin
}
```

Now we can run `gradle bloopInstall`, which will create all of the Bloop
configuration files.

This will enable us to work with Metals and all features should work.

# Experimental Method

Gradle's Tooling API can be used to extract the Bloop files without needing any
change to the build files.  It works with the configuration cache unlike the
method above.

At the command line run:
```bash
coursier launch io.github.arthurm1.gradle.bsp:server:@GRADLE_BSP_VERSION@ -r https://repo.gradle.org/gradle/libs-releases -M com.microsoft.java.bs.core.BloopExporter
```