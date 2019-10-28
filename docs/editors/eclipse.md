---
id: eclipse
title: Eclipse
---

Metals works with Eclipse thanks to the
[`metals-eclipse`](https://github.com/scalameta/metals-eclipse) plugin.

**Notice** Eclipse integration is still under development and might lack some of
the features

![Eclipse demo](https://i.imgur.com/SxD6PcJ.gif)

```scala mdoc:requirements

```

## Installing the plugin

In you eclipse installation go to install new software and point the repository
to:

```
http://scalameta.org/metals-eclipse/update/
```

![Install plugin](https://i.imgur.com/PHqyJNL.gif)

## Running commands

All commands including `Import build` can be run currently via browser interface
under `http://127.0.0.1:5031/`. We recommend having it open to import the build
that is required for Metals to work properly.

Additionally, import build message should pop out when opening a new workspace
or it can be run via Metals Tree View that needs to be activated separately.
These features currently still need some polishing, but are usable.

```scala mdoc:generic

```
