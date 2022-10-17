---
id: faq
title: Frequently asked questions
---

This is a collection of frequently asked questions. We do our best to ensure
everything is included in the docs in the relevant places, but sometimes it's
either hard to explain in the context of the docs or there just isn't a good
enough place to stick it. This is an attempt to document some of the most common
questions that we see related to Metals.

## How do I get more debugging information?

If you are using VS Code add `-Dmetals.loglevel=debug` to the
`metals.serverProperties` setting, otherwise just add that property as an
additional parameter for starting Metals server.

## I'm using Scala 2.13.x but doctor shows me `*-build` and `*-build-build` at 2.12.x

![build-build-doctor](https://i.imgur.com/mgnRXse.png)

When using sbt and Metals you get completions and hovers in your sbt build
files. This is done by extra BSP connections with Bloop. So your build is
actually a build target as well as your meta-build. This is why for example you
see nested `project/project/metals.sbt` files. The version of Scala that sbt
uses is 2.12.12 at this moment, so you will see that reflected in Doctor since
the `*-build` and `*-build-build` represent your build and meta-build. If you
see that it's even an older version of Scala, like 2.12.7, this can be fixed by
bumping your sbt version so you don't get a warning about unsupported versions
of Scala in your project.

Note: _If you use sbt as your BSP server as well as your build tool, you won't
see this in the Doctor since sbt does not yet have sbt file support._

## Why do I see this popup about Bloop when I upgrade?

![update-bloop](https://i.imgur.com/0rtoIxy.png)

Sometimes when you update to the latest version of Metals you will see this
popup. Remember that [Bloop](https://scalacenter.github.io/bloop/) runs in the
background on your machine. Once it's started, even if you close Metals, it
doesn't shut down. So let's say you start Metals and no Bloop is running. Metals
will then start Bloop with the current version that Metals knows about. For
example, let's say it starts 1.4.4. Later that day a new version of Metals comes
out that uses Bloop 1.4.5. When you update, Metals detects that 1.4.4 is
running, and then you see the prompt in the image saying that it needs a newer
version. Probably to either include a bug fix from Bloop, a new feature, etc.
Why doesn't Metals just shut down Bloop and restart automatically? That's a
great question, and one that we're exploring. However, it's a bit tricky since
currently there is no way for Metals to know if it started Bloop or if you
manually started Bloop. In the latter situation, we wouldn't want to shut down
the server that you may have manually started at a specific version. For the
vast majority of users, when you see this update, just click **Turn off old
server**.

## Worksheets

Make sure to check out the `worksheet` section on your editor page. For example,
you can find information on worksheets in
[VS Code here](../editors/vscode.md#worksheets).

### How do I run a worksheet?

Keep in mind that worksheets work a bit differently than you may be used to with
Metals. Whereas in other editors you may need to _run_ your worksheet to see the
results, worksheets in Metals evaluate on save, so there is no need to _run_
your worksheet.

### I don't see evaluations

If you don't see any evaluations, make sure the name of your file is
`*.worksheet.sc`.

### I can't access symbols from my workspace in my worksheet

If you find you can't include a package, class, or really any symbol from your
workspace, keep in mind that where you create your worksheets matter. If you
have created your worksheet in a `src` directory, you should have full access to
your classpath and your worksheet Scala version should match that of your
project. If you create your worksheet in the root of your project, you will be
on the default Scala version (2.12.12), and you won't have access to anything
except for the standard lib on your classpath.

## Ammonite scripts

### How do I use Scala 2.x.x for my script?

Under the hood Metals uses
[alexarchambault/ammonite-runner](https://github.com/alexarchambault/ammonite-runner)
to help with Ammonite support. One of the features of `ammonite-runner` is that
it allows you to specify the version of Scala for your script in a comment
before you code.

```scala
// scala 2.13.3
```

### Can I use a specific version of Ammonite?

Sure, the same as the above, but instead of specifying the version of Scala,
specify the version of Ammonite.

```scala
// ammonite 2.2.0
```

## Why do I get a warning to save my file when I try to organize imports?

![organize-imports-warning](https://i.imgur.com/g8d82bV.png)

You'll currently see this if you try to use your editor action to organize
imports. You'll also see this if you use the following setting in VS Code.

```js
"editor.codeActionsOnSave": {
  "source.organizeImports": true
}
```

This happens because [Scalafix](https://scalacenter.github.io/scalafix/)(which
is used for this feature) needs your code saved before it can organize your
imports. There are some conversations going on about how to achieve this in the
future, but for now you'll need to save your file before using this feature.

For context: [#2120](https://github.com/scalameta/metals/issues/2120)

## Can't connect to sbt BSP on Apple M1

It might help to add an additional sbt setting to `$HOME/.sbt/1.0/global.sbt`

```
serverConnectionType := ConnectionType.Tcp,
```

then reload Metals. More on global settings
[here](https://www.scala-sbt.org/1.x/docs/Global-Settings.html).

For context:

- [#2798](https://github.com/scalameta/metals/issues/2798)
- [#6162](https://github.com/sbt/sbt/issues/6162)
