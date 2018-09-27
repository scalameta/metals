---
id: troubleshooting
title: Troubleshooting
---

* To view errors logs in VSCode, go to Help > Toggle Developer Tools > Console.

* If SymbolIndexerTest.classpath tests fail with missing definitions for `List`
  or `CharRef`, try to run `metalsSetup` from the sbt shell and then re-run.

* If you get the following error

      org.fusesource.leveldbjni.internal.NativeDB$DBException: IO error: lock /path/to/Library/Cache/metals

  that means you are running two metals instances that are competing for the
  same lock on the global cache. Try to turn off your editor (vscode/atom) while
  running the test suite locally. We hope to address this in the future by for
  example moving the cache to each workspace directory or use an alternative
  storing mechanism.

* If you encounter `Error: Channel has been closed` in VSCode console and nothing seems to be working, open Settings
(<kbd>CMD</kbd> + <kbd>,</kbd> on macOS)
and make sure the `"metals.serverVersion"` setting points to full version string of the server you have installed, or another existing version of the server (either locally or remotely published).
