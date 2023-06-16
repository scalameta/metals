---
id: proxy
title: Proxy and mirrors
---

There are several parts inside Metals and Bloop that require resolving
additional dependencies from the internet. Inside environments behind a proxy or
with private artifact repositories Metals might not be able to start, because it
cannot easily download the needed dependencies.

There are multiple ways of fixing these issues, but not all of them work in all
situations. While we are trying to make it as painless as possible to setup
everything, there are some steps that might need to be done manually.

Everything inside Metals uses Coursier to download its dependencies:

- The Visual Studio Code and `nvim-metals` extensions both use Coursier to
  download Metals server, although they do it slightly different. The VS Code
  extension has a Coursier boostrap file, which is used to is to download a full
  Coursier version. It's used to keep the extension size down to a minimum,
  whereas `nvim-metals` requires Coursier to be installed on the users machine.
- Metals uses Coursier api to download dependencies needed for a particular
  Scala version
- Bloop uses Coursier api to download the SemanticDB plugin

You can find some more information about particular topics on
[Coursier's website](https://get-coursier.io/docs/overview), but we will try to
summarize the most useful solutions here.

## Mirrors

If Maven Central is not available for your workspace it might be best to use
Coursier's mirrors. Especially since Coursier boostrap tries to download the
core of Coursier from Maven Central directly, which can only be changed using
the mirrors. Not all functionalities are yet available for the bootstrapped
Coursier version, including custom repositories, so mirrors are really the only
way to fix this.

There are different ways to setup mirrors:

### Via global properties file

Create `mirror.properties` file with the contents:

```
central.from=https://repo1.maven.org/maven2
central.to=http://exmaple.com:8080/nexus/content/groups/public
```

You need to replace the uri after `central.to=` with your private repository.

The location of the mirror file varies depending on the operating system:

- Windows:
  `C:\Users\<user_name>\AppData\Roaming\Coursier\config\mirror.properties`
- Linux: `~/.config/coursier/mirror.properties`
- MacOS: `~/Library/Preferences/Coursier/mirror.properties`

This solution will work in most cases for all previously mentioned usages of
Coursier, which is especially important in the case of the Bloop server, which
when started by Metals will not have any system properties forwarded.

### Via environment variable

You can set the environment variable `COURSIER_MIRRORS` with the location of the
file containing the mirror definition. That environment variable will need to
available either locally or globally for the Metals server. In case of the
Visual Studio Code extension it means you will need to start it from command
line with that variable in scope.

### Via properties

You can also use properties to specify the location of the mirror file, for
example:

```
-Dcoursier.mirrors=~/.config/coursier/mirror.properties
```

This property needs to be added to the Metals server and Coursier invocation.
However, this solution will not work for Bloop, because properties are not
forwarded to the invocation of the Bloop server. You will need to use a
different method for Bloop in this case or you can run the build server manually
with everything specified separately.

## Custom artifact repositories (Maven or Ivy resolvers)

In case you need to add custom repositories to resolve Metals server artifacts
you can use the `COURSIER_REPOSITORIES` environment variable. This will tell
Coursier to try to download artifacts from your private artifact repository.
This is also available as a setting in the Metals Visual Studio Code and
`nvim-metals` extensions.

## Proxy settings

In some cases, workspaces might require a proxy in order to resolve the needed
artifacts. Depending on the way Metals server is started, proxy settings can be
specified using properties inside a `.jvmopts` file and
`metals.serverProperties` for Visual Studio Code or `serverProperties` in your
settings table for `nvim-metals`, or via properties for Coursier and Metals
invocations.

However, because proxy properties might vary between workspaces and a Bloop
server must work for multiple clients at the same time they are not forwarded to
the Bloop itself. To make sure that Bloop uses the correct proxy settings, you
can specify them by copying the correct `.jvmopts` file to the `~/.bloop`
directory, which will make them global.

If you are using a manually installed Bloop server, each time you run `bloop`
from commandline, the proxy settings will be reapplied according to the current
workspace's `.jvmopts`. This should not be an issue, since those settings are
required only to download the compiler bridges and SemanticDB plugin.

**Important** Each setting needs to be on a separate line.

## Troubleshooting

There has already been a couple of issues surrounding this topic, if you are
still having issues you can take a look at some of them:

- https://github.com/scalameta/metals/issues/1315
- https://github.com/scalameta/metals/issues/1301
- https://github.com/scalameta/metals/issues/1306
- https://github.com/scalameta/metals/issues/1362

### The server did not start, got FailedToOpenBspConnection

```
error: The command bsp --protocol tcp --port 44559 returned with an error
>
error: The launcher failed to establish a bsp connection, aborting...
ERROR Failed to connect with build server, no functionality will work.
java.lang.RuntimeException: The server did not start, got FailedToOpenBspConnection
	at bloop.launcher.LauncherMain.failPromise$1(Launcher.scala:92)
	at bloop.launcher.LauncherMain.runLauncher(Launcher.scala:119)
	at scala.meta.internal.metals.BloopServers$$anon$1.run(BloopServers.scala:101)
	at java.util.concurrent.Executors$RunnableAdapter.call(Executors.java:515)
	at java.util.concurrent.FutureTask.run(FutureTask.java:264)
	at java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1128)
	at java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:628)
	at java.lang.Thread.run(Thread.java:834)
```

```
Exception in thread "bloop-server-background" java.io.IOException: Cannot run program "java" (in directory "<some path here ...>"): error=2, No such file or directory
        at java.lang.ProcessBuilder.start(ProcessBuilder.java:1048)
```

This might mean that you don't have your java installation on your PATH and
Bloop was unable to start.

### Timeout waiting for 'build/initialize' response

```
INFO  tracing is disabled for protocol BSP, to enable tracing of incoming and outgoing JSON messages create an empty file at C:\Users\tgodzik\AppData\Local\scalameta\metals\cache\bsp.trace.json
ERROR Timeout waiting for 'build/initialize' response
ERROR Failed to connect with build server, no functionality will work.
java.util.concurrent.TimeoutException
	at java.util.concurrent.CompletableFuture.timedGet(CompletableFuture.java:1886)
	at java.util.concurrent.CompletableFuture.get(CompletableFuture.java:2021)
	at scala.meta.internal.metals.BuildServerConnection$.initialize(BuildServerConnection.scala:259)
	at scala.meta.internal.metals.BuildServerConnection$.$anonfun$fromSockets$1(BuildServerConnection.scala:203)
	at scala.util.Success.$anonfun$map$1(Try.scala:255)
	at scala.util.Success.map(Try.scala:213)
	at scala.concurrent.Future.$anonfun$map$1(Future.scala:292)
	at scala.concurrent.impl.Promise.liftedTree1$1(Promise.scala:33)
	at scala.concurrent.impl.Promise.$anonfun$transform$1(Promise.scala:33)
	at scala.concurrent.impl.CallbackRunnable.run(Promise.scala:64)
	at java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1128)
	at java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:628)
	at java.lang.Thread.run(Thread.java:834)

WARN  Stopped configuration of SemanticDB in Scala 2.13.1 projects: Error downloading org.scalameta:semanticdb-scalac_2.13.1:4.3.0
  not found: C:\Users\tgodzik\.ivy2\local\org.scalameta\semanticdb-scalac_2.13.1\4.3.0\ivys\ivy.xml
  download error: Caught java.net.ConnectException: Connection timed out: connect (Connection timed out: connect) while downloading https://repo1.maven.org/maven2/org/scalameta/semanticdb-scalac_2.13.1/4.3.0/semanticdb-scalac_2.13.1-4.3.0.pom
  not found: https://dl.bintray.com/scalacenter/releases/org/scalameta/semanticdb-scalac_2.13.1/4.3.0/semanticdb-scalac_2.13.1-4.3.0.pom
```

This might mean you need to specify proxy settings for Bloop or add custom
repositories as specified above.

### Mirrors still do not work for all dependencies

In some specific configurations it might be needed to define additional mirrors
like:

```
jcenter.from=https://repo1.maven.org/maven2
jcenter.to=https://artifactory.mycomany.com/maven2

typesafe.from=https://repo.typesafe.com/typesafe/ivy-releases
typesafe.to=https://artifactory.mycompany.com/typesafe-ivy-releases/
```

The number of repositories to add depends on how the company's infrastructure is
set up. The key is to keep adding mirror entries until you've fixed each resolve
error. In case of sbt you can find out what the repos are named like
typesafe.from|to by checking
[the official documentation](https://www.scala-sbt.org/1.x/docs/Resolvers.html#Predefined+resolvers)
and any custom resolvers your workspace has defined.
