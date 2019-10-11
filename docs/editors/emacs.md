---
id: emacs
title: Emacs
---

Metals works in Emacs thanks to the the
[`lsp-mode`](https://github.com/emacs-lsp/lsp-mode) package.

![Emacs demo](https://i.imgur.com/KJQLMZ7.gif)

```scala mdoc:requirements

```

## Installation

To use Metals in Emacs, place this snippet in your Emacs configuration (for example .emacs.d/init.el) to load
`lsp-mode` along with its dependencies:

```el
(require 'package)

;; Add melpa to your packages repositories
(add-to-list 'package-archives '("melpa" . "https://melpa.org/packages/") t)

(package-initialize)

;; Install use-package if not already installed
(unless (package-installed-p 'use-package)
  (package-refresh-contents)
  (package-install 'use-package))

(require 'use-package)

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

(use-package lsp-mode
  ;; Optional - enable lsp-mode automatically in scala files
  :hook (scala-mode . lsp)
  :config (setq lsp-prefer-flymake nil))

(use-package lsp-ui)

;; Add company-lsp backend for metals
(use-package company-lsp)
```

> You may need to disable other packages like `ensime` or sbt server to prevent
> conflicts with Metals.

If you start Emacs now then it will fail since the `metals-emacs` binary does
not exist yet.

```scala mdoc:bootstrap:metals-emacs emacs

```

The `-Dmetals.client=emacs` flag is important since it configures Metals for
usage with Emacs.

```scala mdoc:editor:emacs

```

## Manually trigger build import

To manually trigger a build import, run `M-x lsp-metals-build-import`.

![Import build command](https://i.imgur.com/SvGXJDK.png)

## Run doctor

Run `M-x lsp-metals-doctor-run` to troubleshoot potential configuration problems
in your build.

![Run doctor command](https://i.imgur.com/yelm0jd.png)

### eglot

There is an alternative LSP client called
[eglot](https://github.com/joaotavora/eglot) that might be worth trying out if
you want to use an alternative to lsp-mode.

To configure Eglot with Metals:

```el
(require 'package)

;; Add melpa-stable to your packages repositories
(add-to-list 'package-archives '("melpa-stable" . "https://stable.melpa.org/packages/") t)

;; Install use-package if not already installed
(unless (package-installed-p 'use-package)
  (package-refresh-contents)
  (package-install 'use-package))

(require 'use-package)

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

```scala mdoc:generic

```
