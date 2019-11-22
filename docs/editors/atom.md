---
id: atom
title: Atom
---

Metals works with Atom thanks to the
[`ide-scala`](https://atom.io/packages/ide-scala) package.

![Atom demo](https://i.imgur.com/xPn2ATM.gif)

```scala mdoc:requirements

```

## Installing the packages

Once the requirements are satisfied, we can now proceed to install the following packages:

- [`ide-scala`](https://atom.io/packages/ide-scala): Protocol client to communicate with Metals. Install the package by searching for "ide-scala" or run the following command.

```sh
apm install ide-scala
```

[![Install Metals package](https://img.shields.io/badge/metals-atom-brightgreen.png)](atom://settings-view/show-package?package=ide-scala)

- [`language-scala`](https://atom.io/packages/language-scala): for syntax highlighting Scala and sbt source files.

```scala mdoc:editor:atom

```

```scala mdoc:command-palette:atom

```

## Using latest Metals SNAPSHOT

Update the "Metals version" setting to try out the latest pending Metals
features.

```scala mdoc:releases

```

Run the "Reload Window" command after updating the setting for the new version
to take effect.

```scala mdoc:generic

```
