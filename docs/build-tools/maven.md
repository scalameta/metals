---
id: maven
title: Maven
---

Maven is one of the most common build tools in the JVM ecosystem and it also
allows for using scala through the
[scala-maven-plugin](https://davidb.github.io/scala-maven-plugin/usage.html).
The [scalor-maven-plugin](https://github.com/random-maven/scalor-maven-plugin)
is not currently supported and requires a new plugin for bloop to be
implemented.

## Automatic installation

The first time you open Metals in a new workspace it prompts you to import the
build. Select "Import build" to start automatic installation. This will create
all the needed Bloop config files and all the features should work properly.

## Manual installation

Currently, all you need to run the manual installation is:

`mvn ch.epfl.scala:maven-bloop_2.10:@BLOOP_VERSION@:bloopInstall -DdownloadSources=true`

If you choose this option though you should select "Don't show again" when
Metals prompts to import the build.
