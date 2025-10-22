---
authors: ckipp
title: sbt BSP support
---

If you've been following the
[sbt releases](https://github.com/sbt/sbt/releases), 1.4.x introduced some great
new features, one of those being BSP support. This effort was initiated by
community members and proposed to the
[Scala Center Advisory Board](https://github.com/scalacenter/advisoryboard/blob/master/proposals/023-bsp.md).
Then thanks to the work of [Adrien Piquerez](https://twitter.com/adrienpi2) and
[Eugene Yokota](https://twitter.com/eed3si9n) BSP support became a reality in
1.4.0. You can read more about the reason behind the work and some of the
details of the implementation in this blog post:
[BSP Support in sbt 1.4](https://www.scala-lang.org/blog/2020/10/27/bsp-in-sbt.html).

The Metals team is also happy to announce that as of the 0.9.5 release, the
process to use other BSP servers like sbt is now much smoother. In some ways,
BSP is an implementation detail abstracted away from a user, and you may not
directly interact at all with your build server, even though it's pivotal for
the Metals experience. Therefore, we'd like to answer some common questions
about what this means to you as a user, explain some of the default choices
Metals makes for you, and show you how to explore the sbt BSP server.

## What is BSP?

First off, you may be wondering what BSP is. The
[Build Server Protocol](https://build-server-protocol.github.io/) (BSP) is a
_Protocol for IDEs and build tools to communicate about compilation, running,
testing, debugging, and much more._ If you're familiar with the
[Language Server Protocol](https://microsoft.github.io/language-server-protocol/)
(LSP), BSP is complementary to LSP and inspired by it. Where LSP allows your
editor to abstract over various programming languages by a shared way to
communicate to a language server, BSP allows IDE's to abstract over various
build servers. A typical example of this can be illustrated like so:

![LSP BSP example](https://github.com/scalameta/gh-pages-images/blob/master/metals/2020-11-06-sbt-BSP-support/0RRUDlU.png?raw=true)

You have your editor (Emacs) communicating with Metals via LSP, and then Metals
communicating with a BSP server (Bloop) via BSP. This communication over BSP can
be about compiling your code, running your code, defining sources in your
workspace, etc. You can read all about the various communication types
[here in the protocol](https://build-server-protocol.github.io/docs/specification#actual-protocol).

[Bloop](https://scalacenter.github.io/bloop/) was the first server to implement
BSP, and it's the default build server for all build tools that Metals supports
at the moment. When you open a fresh project, you're prompted to import your
build, and this import process is running a form of "Bloop Install" to write
your build definition to `.bloop/` for Bloop to read and use. Then as you
continue to edit, diagnostics and other information are flowing back and forth
from Metals to Bloop. Hopefully this gives you a brief picture of what BSP is,
and how it's used in Metals.

## What does sbt BSP support mean for Metals?

Another question you may have is "what does sbt BSP support mean for Metals"?
This means a couple different things. Up until this point, if you wanted to use
sbt BSP, you needed to clear your `.metals/` and `.bloop` directories, and then
start sbt with a specific flag before connecting to it. None of this is
necessary anymore. In order for
[BSP discovery](https://build-server-protocol.github.io/docs/server-discovery.html)
to happen, you need a `.bsp/*json` file with instructions on how to
start/connect to the build server. A new command has been added to Metals (which
we'll go over down below) that can generate this file for you if it doesn't
exist, and then you will be automatically connected to sbt. If this file already
exist, then we provide a simple way for you to "switch" build servers and for
your choice to be remembered. We also automatically include an sbt plugin to
ensure the correct semantic information is produced by the compiler. So to
summarize, Metals now has the ability to generate the necessary `.bsp/sbt.json`
file if it doesn't exist, and to also switch back and forth from using Bloop or
sbt as your build server.

## What's the difference between Bloop and sbt BSP?

At this point you may be asking, "what's the difference?". For an average user,
there may not be a ton of difference, however I'd like to outline a couple
things that may be relevant to you.

- Bloop supports the
  [Debug Adapter Protocol](https://microsoft.github.io/debug-adapter-protocol/)
  (DAP) and sbt doesn't. So you'll notice when using sbt as a build server, you
  won't have the `run` or `debug` code lenses directly in your editor like you
  do with Bloop.
- Bloop writes all the necessary information about your build it needs to disk
  whereas sbt loads it into memory. If you're a user that works for long periods
  of time on a single project, then this may not matter to you at all. However,
  if you jump in and out of projects, without having sbt shell running, then you
  will pay the cost of loading up your build every time.
- Bloop offers sbt file support that enables completions and hover, whereas this
  is not yet available in the sbt BSP implementation.
- sbt gives you access to the full task graph, so for example if you're using
  `BuildInfo`, and you compile with Bloop, your `BuildInfo` won't get generated.
  However, sources will get generated when using sbt server. This can help avoid
  any potential inconsistencies.

While there are some more differences, these are probably the main ones you'll
notice as a user. These are both great tools, and you'll have to explore more to
see what is the best fit for you. For now, Metals defaults to using Bloop mainly
because we believe it provides a richer feature set and because pretty much all
of our testing includes Bloop as a build server.

## How do I try it out?

Now to the good stuff. There are two main ways that you can start using sbt
server in Metals.

### No `.bsp/sbt.json` exists

If no `.bsp/sbt.json` exists yet, you can generate it with the new
`metals.generate-bsp-config` command. At any time you can execute this command
and Metals will ensure that you're on the minimum required sbt version (1.4.1),
generate the `.bsp/sbt.json` file for you, include the necessary plugin, and
then auto-connect to the sbt build server. For example in VS Code this looks
like this:

### `metals.generate-bsp-config`

![generate-bsp-config](https://github.com/scalameta/gh-pages-images/blob/master/metals/2020-11-06-sbt-BSP-support/kBNbtzI.gif?raw=true)

### `.bsp/sbt.json` already exists

If you've ran sbt on your project already, your `.bsp/sbt.json` file will
already exist. In this case, you can simply use the `metals.bsp-switch` command
and choose sbt. For example, using `coc-metals`, it looks like this:

### `metals.bsp-switch`

![bsp-switch](https://github.com/scalameta/gh-pages-images/blob/master/metals/2020-11-06-sbt-BSP-support/6tY2ofL.gif?raw=true)

### Switching back to Bloop

After you do a `metals.bsp-switch` or `metals-generate-bsp-config`, your choice
will be remembered. So the next time that you open your workspace, you will
automatically connect to the last build server you had chosen. If you'd like to
go back to your previous build server, you can simply use the
`metals.bsp-switch` command again to choose your previous server. For editors
that have an html doctor, you can also reset your build server choice in the
Doctor.

### Doctor reset

![Doctor](https://github.com/scalameta/gh-pages-images/blob/master/metals/2020-11-06-sbt-BSP-support/YEGfEGB.png?raw=true)

## Conclusion

We hope you enjoy this easier way to use sbt server with Metals. If you're
curious about the implementation details, much of the work was done in
[this pr](https://github.com/scalameta/metals/pull/2154). As always please don't
hesitate to ask questions on our various channels, submit issues, or create new
feature requests.
