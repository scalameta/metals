---
id: maven
title: Maven
---

Maven is one of the most common build tools in the JVM ecosystem and it also
works with Scala through the
[scala-maven-plugin](https://davidb.github.io/scala-maven-plugin/usage.html).
The [scalor-maven-plugin](https://github.com/random-maven/scalor-maven-plugin)
is not currently supported and requires a new plugin for bloop to be
implemented.

```scala mdoc:automatic-installation:Maven
```

## Manual installation

Currently, all you need to run the manual installation is:

`mvn ch.epfl.scala:bloop-maven-plugin:@BLOOP_MAVEN_VERSION@:bloopInstall -DdownloadSources=true`

If you choose this option though you should select "Don't show again" when
Metals prompts to import the build.
