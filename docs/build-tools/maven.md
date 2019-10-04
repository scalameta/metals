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
build. Select "Import build" to start automatic installation.

This will create all the needed Bloop files, however there will be a warning,
since the SemanticDB plugin was not added yet in the automatic import. This will
be added later. Most features should work without it, however some require
SemanticDB files to be provided alongside compiled data. To do that we need a
couple of steps that are explained in the manual installation section.

## Manual installation

For current Metals snapshots all you need to run the manual installation is:

`mvn ch.epfl.scala:maven-bloop_2.10:@BLOOP_VERSION@:bloopInstall -DdownloadSources=true`

However, for all versions before and including 0.7.6 we need a couple more
steps.

First, we need to add a couple of options to the Scala compiler in the
configuration section:

```xml
<plugin>
<groupId>net.alchim31.maven</groupId>
<artifactId>scala-maven-plugin</artifactId>
<version>4.0.2</version>
...
<configuration>
    <compilerPlugins>
        <compilerPlugin>
            <groupId>org.scalameta</groupId>
            <artifactId>semanticdb-scalac_${scala.version}</artifactId>
            <version>@SCALAMETA_VERSION@</version>
        </compilerPlugin>
    </compilerPlugins>
    <args>
        <arg>-P:semanticdb:synthetics:on</arg>
        <arg>-P:semanticdb:failures:warning</arg>
        <arg>-P:semanticdb:sourceroot:${maven.multiModuleProjectDirectory}</arg>
        <arg>-Yrangepos</arg>
        <arg>-Xplugin-require:semanticdb</arg>
    </args>
</configuration>
...
</plugin>
```

Next, we need to do run bloopInstall via maven, which can be done easily
through:

`mvn ch.epfl.scala:maven-bloop_2.10:@BLOOP_VERSION@:bloopInstall -DdownloadSources=true`

Everything should now be correctly configured and work even when reimporting the
project.

If you don't want to modify the `pom.xml` you can also run bloopInstall with an
additional parameter:

```
mvn ch.epfl.scala:maven-bloop_2.10:@BLOOP_VERSION@:bloopInstall -DdownloadSources=true -DaddScalacArgs=-Xplugin:/path/to/semanticdb-scalac.jar|-P:semanticdb:synthetics:on|-P:semanticdb:failures:warning|-P:semanticdb:sourceroot:/path/to/workspace|-Yrangepos|-Xplugin-require:semanticdb'
```

`-DaddScalacArgs` takes a string with additional scalac options separated by
`|`. You would need to download the correct plugin first and manually replace
`/path/to/semanticdb-scalac.jar` with the path to the semanticDB plugin jar.

If you choose this option though you should select "Don't show again" when
Metals prompts to import the build.
