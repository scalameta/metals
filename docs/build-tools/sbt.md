---
id: sbt
title: sbt
---

sbt is most commonly used build tool in the Scala community and works with
Metals out-of-the-box.

```scala mdoc:automatic-installation:sbt

```

![Import build](https://i.imgur.com/t5RJ3q6.png)

The Automatic build import process for sbt happens through
[Bloop](https://scalacenter.github.io/bloop/), a build server for Scala. Bloop
implements the [Build Server Protocol
(BSP)](https://build-server-protocol.github.io/docs/specification) that Metals
uses to learn the directory structure of your project, its library dependencies,
and to build or run your code. 

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

## sbt Build Server

As of sbt 1.4.1, Metals has integrated support for the sbt BSP server. If you'd
like to use the sbt server as an alternative to Bloop (which is the default),
then at any time while in an sbt workspace you can choose to switch in multiple
ways.

_Note_: that if you are unfamiliar with the features that the different build
servers may offer, then simply stick with the default (Bloop), which has great
integrated stable support in Metals.

### Generating a `.bsp/sbt.json` file if one doesn't exist

More than likely if you're using sbt >= 1.4.1, you'll have already seen this file
exist. However, if you're in a fresh workspace or it doesn't exist for some
reason, you can execute a `metals.generate-bsp-config` command via the command
palette, which will automatically detect that you're in a sbt workspace and
generate the necessary file. After the file generation, Metals will then
automatically connect to sbt. From this point on, you'll be using sbt instead of
Bloop as your build server.

### Connect to sbt build server

If your workspace already has a `.bsp/sbt.json` file, then you can switch from
using Bloop to sbt as a build server by executing a `metals.bsp-switch` command
from the command palette.  This command will recognize the `.bsp/sbt.json` file,
and then connect to the sbt build server. After the connection is made, you'll
be using sbt instead of Bloop as your build server.

### Switching back to Bloop

If you'd like to switch back to using Bloop as your build server, there are
multiple ways for you to do this.

1. Using the same `metals.bsp-switch` command as up above, and select "bloop".
2. Use the `metals.reset-choice` functionality and choose to reset the "Build
   Server Selection". Then follow this with the `metals.build-restart` command
   which will disconnect you from the sbt build server, and then connect you
   back to the default Bloop server.

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
