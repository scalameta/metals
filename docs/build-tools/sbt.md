---
id: sbt
title: sbt
---

sbt is most commonly used build tool in the Scala community and works with
Metals out-of-the-box.

```scala mdoc:automatic-installation:sbt

```

![Import build](https://i.imgur.com/t5RJ3q6.png)

## Install without Bloop

Automatic build import for sbt happens through
[Bloop](https://scalacenter.github.io/bloop/), a compile server for Scala. Bloop
implements the
[Build Server Protocol (BSP)](https://github.com/scalacenter/bsp/blob/master/docs/bsp.md)
that Metals uses to learn the directory structure of your project and its
library dependencies. sbt does not implement BSP so Metals is not able to import
sbt builds without Bloop.

## Manual installation

> It's recommended to use automatic installation over manual installation since
> manual installation requires several independent steps that make it harder to
> stay up-to-date with the latest Metals version.

Instead of using automatic build import, you can manually install sbt-bloop and
generate the Bloop JSON files directly from your sbt shell. This approach may
speed up build import by avoiding Metals from starting sbt in a separate
process.

First, install the Bloop plugin globally or inside your `project` directory:

```scala
// One of:
//   ~/.sbt/0.13/plugins/plugins.sbt
//   ~/.sbt/1.0/plugins/plugins.sbt
resolvers += Resolver.sonatypeRepo("snapshots")
addSbtPlugin("ch.epfl.scala" % "sbt-bloop" % "@BLOOP_VERSION@")
```

Next, run:

```
sbt -Dbloop.export-jar-classifiers=sources bloopInstall
```

to generate the Bloop JSON configuration files. You can also set the
`bloopExportJarClassifiers` setting inside your main build.sbt file, but using
the above command will do it automatically for you in the current sbt session.

Finally, once `bloopInstall` is finished, execute the "Connect to build server"
command (id: `build.connect`) command to tell Metals to establish a connection
with the Bloop build server.

## Troubleshooting

Before reporting an issue, check if your problem is solved with one of the
following tips.

### Waiting for lock on .ivy2/.sbt.ivy.lock

Metals run sbt in a separate process and this error happens where there are two
sbt processes resolving dependencies at the same time.

### Not valid key: metalsEnable

This error might indicate that you have an old version of `sbt-metals` installed
in your project.

```
[error] Not a valid key: metalsEnable (similar: scalafixEnabled)
[error] metalsEnable
[error]             ^
```

Try to remove any usage of `sbt-metals` in your build.
