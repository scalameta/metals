---
id: version-0.4.4-emacs
title: Emacs
original_id: emacs
---

Metals works in Emacs thanks to the the
[`lsp-scala`](https://github.com/rossabaker/lsp-scala) package.

![Emacs demo](https://i.imgur.com/KJQLMZ7.gif)

> The Emacs LSP client has [several known issues](#known-issues). Most
> critically, diagnostics are not published for unopened buffers meaning compile
> errors can get lost.


## Requirements

**Java 8**. Metals does not work with Java 11 yet so make sure the JAVA_HOME
environment variable points to Java 8.

**macOS, Linux or Windows**. Metals is developed on macOS and every PR is
tested on Ubuntu+Windows.

**Scala 2.12 and 2.11**. Metals works only with Scala versions 2.12.8, 2.12.7, 2.12.6, 2.12.5, 2.12.4, 2.11.12, 2.11.11, 2.11.10 and 2.11.9.
Note that 2.10.x and 2.13.0-M5 are not supported.

## Installation

To use Metals in Emacs, place this snippet in your Emacs configuration to load
`lsp-scala` along with its dependencies:

```el
;; Add melpa to your packages repositories
(add-to-list 'package-archives '("melpa" . "https://melpa.org/packages/") t)

;; Enable defer and ensure by default for use-package
(setq use-package-always-defer t
      use-package-always-ensure t)

;; Enable scala-mode and sbt-mode
(use-package scala-mode
  :mode "\\.s\\(cala\\|bt\\)$")

(use-package sbt-mode
  :commands sbt-start sbt-command
  :config
  ;; WORKAROUND: https://github.com/ensime/emacs-sbt-mode/issues/31
  ;; allows using SPACE when in the minibuffer
  (substitute-key-definition
   'minibuffer-complete-word
   'self-insert-command
   minibuffer-local-completion-map))

;; Enable nice rendering of diagnostics like compile errors.
(use-package flycheck
  :init (global-flycheck-mode))

(use-package lsp-mode)

(use-package lsp-ui
  :hook (lsp-mode . lsp-ui-mode))

(use-package lsp-scala
  :after scala-mode
  :demand t
  ;; Optional - enable lsp-scala automatically in scala files
  :hook (scala-mode . lsp))
```

> You may need to disable other packages like `ensime` or sbt server to prevent
> conflicts with Metals.

If you start Emacs now then it will fail since the `metals-emacs` binary does
not exist yet.


Next, build a `metals-emacs` binary for the latest Metals release using the
[Coursier](https://github.com/coursier/coursier) command-line interface.

<table>
<thead>
<th>Version</th>
<th>Published</th>
<th>Resolver</th>
</thead>
<tbody>
<tr>
<td>0.4.4</td>
<td>02 Feb 2019 18:09</td>
<td><code>-r sonatype:releases</code></td>
</tr>
<tr>
<td>0.4.4+15-ac8fa735-SNAPSHOT</td>
<td>14 Feb 2019 16:56</td>
<td><code>-r sonatype:snapshots</code></td>
</tr>
</tbody>
</table>

```sh
# Make sure to use coursier v1.1.0-M9 or newer.
curl -L -o coursier https://git.io/coursier
chmod +x coursier
./coursier bootstrap \
  --java-opt -XX:+UseG1GC \
  --java-opt -XX:+UseStringDeduplication  \
  --java-opt -Xss4m \
  --java-opt -Xms100m \
  --java-opt -Dmetals.client=emacs \
  org.scalameta:metals_2.12:0.4.4 \
  -r bintray:scalacenter/releases \
  -r sonatype:snapshots \
  -o /usr/local/bin/metals-emacs -f
```

Make sure the generated `metals-emacs` binary is available on your `$PATH`.

The `-Dmetals.client=emacs` flag is important since it configures Metals for
usage with Emacs.


## Importing a build

The first time you open Metals in a new workspace it prompts you to import the build.
Click "Import build" to start the installation step.

![Import build](https://i.imgur.com/UdwMQFk.png)

- "Not now" disables this prompt for 2 minutes.
- "Don't show again" disables this prompt forever, use `rm -rf .metals/` to re-enable
  the prompt.
- Behind the scenes, Metals uses [Bloop](https://scalacenter.github.io/bloop/) to
  import sbt builds, but you don't need Bloop installed on your machine to run this step.

Once the import step completes, compilation starts for your open `*.scala`
files.

Once the sources have compiled successfully, you can navigate the codebase with
goto definition.

### Custom sbt launcher

By default, Metals runs an embedded `sbt-launch.jar` launcher that respects `.sbtopts` and `.jvmopts`.
However, the environment variables `SBT_OPTS` and `JAVA_OPTS` are not respected.


Update the server property `-Dmetals.sbt-script=/path/to/sbt` to use a custom
`sbt` script instead of the default Metals launcher if you need further
customizations like reading environment variables.


### Speeding up import

The "Import build" step can take a long time, especially the first time you
run it in a new build. The exact time depends on the complexity of the build and
if library dependencies need to be downloaded. For example, this step can take
everything from 10 seconds in small cached builds up to 10-15 minutes in large
uncached builds.

Consult the [Bloop documentation](https://scalacenter.github.io/bloop/docs/build-tools/sbt#speeding-up-build-export)
to learn how to speed up build import.

### Importing changes

When you change `build.sbt` or sources under `project/`, you will be prompted to
re-import the build.

![Import sbt changes](https://i.imgur.com/UFK0p8i.png)


## Manually trigger build import

To manually trigger a build import, run `M-x lsp-scala-build-import`.

![Import build command](https://i.imgur.com/SvGXJDK.png)

## Run doctor

Run `M-x lsp-scala-doctor-run` to troubleshoot potential configuration problems
in your build.

![Run doctor command](https://i.imgur.com/yelm0jd.png)

## Known issues

- `lsp-mode` blocks the UI during the `initialize` handshake so you may notice
  that opening `*.scala` file gets slower. Metals does as much as possible to
  move computation out of `initialize` but `lsp-mode` should ideally not freeze
  the UI during any LSP request/response cycle.
- `lsp-mode` does not publish diagnostics for unopened buffers so you only see
  compile errors for the open file.
- `lsp-mode` does not send a `shutdown` request and `exit` notification when
  closing Emacs meaning Metals can't properly clean up resources.
- `lsp-mode` does not respect the Metals server capabilities that are declared
  during the initialize handshake. The following warnings can be ignored in the
  logs:
  - `textDocument/hover is not supported`
  - `textDocument/documentSymbol is not supported`
- `lsp-mode` executes `workspace/executeCommand` commands within a specific
  timeout so long-running commands like "Import build" cause the following error
  to be reported in the logs:
  `lsp--send-wait: Timed out while waiting for a response from the language server`.
  Feel free to ignore this error.
- `flycheck` does not explicitly support Windows so diagnostics may not report
  correctly on Windows:
  http://www.flycheck.org/en/latest/user/installation.html#windows-support

### eglot

There is an alternative LSP client called
[eglot](https://github.com/joaotavora/eglot) that might be worth trying out to
see if it addresses the issues of lsp-mode.

To configure Eglot with Metals:

```el
;; Add melpa-stable to your packages repositories
(add-to-list 'package-archives '("melpa-stable" . "https://stable.melpa.org/packages/") t)

;; Enable defer and ensure by default for use-package
(setq use-package-always-defer t
      use-package-always-ensure t)

;; Enable scala-mode and sbt-mode
(use-package scala-mode
  :mode "\\.s\\(cala\\|bt\\)$")

(use-package sbt-mode
  :commands sbt-start sbt-command
  :config
  ;; WORKAROUND: https://github.com/ensime/emacs-sbt-mode/issues/31
  ;; allows using SPACE when in the minibuffer
  (substitute-key-definition
   'minibuffer-complete-word
   'self-insert-command
   minibuffer-local-completion-map))

(use-package eglot
  :pin melpa-stable
  :config
  (add-to-list 'eglot-server-programs '(scala-mode . ("metals-emacs")))
  ;; (optional) Automatically start metals for Scala files.
  :hook (scala-mode . eglot-ensure))
```


## Gitignore `.metals/` and `.bloop/`

The Metals server places logs and other files in the `.metals/` directory. The
Bloop compile server places logs and compilation artifacts in the `.bloop`
directory. It's recommended to ignore these directories from version control
systems like git.

```sh
# ~/.gitignore
.metals/
.bloop/
```

