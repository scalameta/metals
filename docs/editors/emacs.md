---
id: emacs
title: Emacs
---

Metals works in Emacs thanks to the
[`lsp-mode`](https://github.com/emacs-lsp/lsp-mode) package (another option is the [Eglot](#eglot) package).

![Emacs demo](https://i.imgur.com/KJQLMZ7.gif)

```scala mdoc:requirements

```

## Installation

To use Metals in Emacs, place this snippet in your Emacs configuration (for example .emacs.d/init.el) to load
`lsp-mode` along with its dependencies:

```elisp
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
;; Keep auto-save/backup files separate from source code:  https://github.com/scalameta/metals/issues/1027
(setq use-package-always-defer t
      use-package-always-ensure t
      backup-directory-alist `((".*" . ,temporary-file-directory))
      auto-save-file-name-transforms `((".*" ,temporary-file-directory t)))

;; Enable scala-mode for highlighting, indentation and motion commands
(use-package scala-mode
  :interpreter ("scala" . scala-mode))

;; Enable sbt mode for executing sbt commands
(use-package sbt-mode
  :commands sbt-start sbt-command
  :config
  ;; WORKAROUND: https://github.com/ensime/emacs-sbt-mode/issues/31
  ;; allows using SPACE when in the minibuffer
  (substitute-key-definition
   'minibuffer-complete-word
   'self-insert-command
   minibuffer-local-completion-map)
   ;; sbt-supershell kills sbt-mode:  https://github.com/hvesalai/emacs-sbt-mode/issues/152
   (setq sbt:program-options '("-Dsbt.supershell=false")))

;; Enable nice rendering of diagnostics like compile errors.
(use-package flycheck
  :init (global-flycheck-mode))

(use-package lsp-mode
  ;; Optional - enable lsp-mode automatically in scala files
  ;; You could also swap out lsp for lsp-deffered in order to defer loading
  :hook  (scala-mode . lsp)
         (lsp-mode . lsp-lens-mode)
  :config
  ;; Uncomment following section if you would like to tune lsp-mode performance according to
  ;; https://emacs-lsp.github.io/lsp-mode/page/performance/
  ;; (setq gc-cons-threshold 100000000) ;; 100mb
  ;; (setq read-process-output-max (* 1024 1024)) ;; 1mb
  ;; (setq lsp-idle-delay 0.500)
  ;; (setq lsp-log-io nil)
  ;; (setq lsp-completion-provider :capf)
  (setq lsp-prefer-flymake nil))

;; Add metals backend for lsp-mode
(use-package lsp-metals)

;; Enable nice rendering of documentation on hover
;;   Warning: on some systems this package can reduce your emacs responsiveness significally.
;;   (See: https://emacs-lsp.github.io/lsp-mode/page/performance/)
;;   In that case you have to not only disable this but also remove from the packages since
;;   lsp-mode can activate it automatically.
(use-package lsp-ui)

;; lsp-mode supports snippets, but in order for them to work you need to use yasnippet
;; If you don't want to use snippets set lsp-enable-snippet to nil in your lsp-mode settings
;; to avoid odd behavior with snippets and indentation
(use-package yasnippet)

;; Use company-capf as a completion provider.
;;
;; To Company-lsp users:
;;   Company-lsp is no longer maintained and has been removed from MELPA.
;;   Please migrate to company-capf.
(use-package company
  :hook (scala-mode . company-mode)
  :config
  (setq lsp-completion-provider :capf))

;; Posframe is a pop-up tool that must be manually installed for dap-mode
(use-package posframe)

;; Use the Debug Adapter Protocol for running tests and debugging
(use-package dap-mode
  :hook
  (lsp-mode . dap-mode)
  (lsp-mode . dap-ui-mode))
```

> You may need to disable other packages like `ensime` or sbt server to prevent
> conflicts with Metals.

Next you have to install metals server. Emacs can do it for you when `lsp-mode`
is enabled in a scala buffer or via `lsp-install-server` command. Also you can
do it manually executing `coursier install metals` and configuring `$PATH`
variable properly.

```scala mdoc:editor:emacs

```

## LSP Tips

### Show navigable stack trace

You can annotate your stack trace with code lenses (which requires the
following bit of configuration mentioned earlier: `(lsp-mode . lsp-lens-mode)`). 
These allow you to run actions from your code.

One of these actions allow you to navigate your stack trace.

You can annotate any stack trace by marking a stack trace with your
region and using `M-x lsp-metals-analyze-stacktrace` on it.

This will open a new Scala buffer that has code lenses annotations:
just click on the small "open" annotation to navigate to the source
code relative to your stack trace.

This will work as long as the buffer you are marking your stack trace
on exists within the project directory tracked by `lsp-mode`, because
`lsp-metals-analyze-stacktrace` needs the `lsp` workspace to find the
location of your errors.

Note that if you try to do that from `sbt-mode`, you may get an error
unless you patch `lsp-find-workspace` with the following:

```elisp
(defun lsp-find-workspace (server-id &optional file-name)
    "Find workspace for SERVER-ID for FILE-NAME."
    (-when-let* ((session (lsp-session))
                 (folder->servers (lsp-session-folder->servers session))
                 (workspaces (if file-name
                                 (let* ((folder (lsp-find-session-folder session file-name))
                                        (folder-last-char (substring folder (- (length folder) 1) (length folder)))
                                        (key (if (string= folder-last-char "/") (substring folder 0 (- (length folder) 1)) folder)))
                                   (gethash key folder->servers))
                               (lsp--session-workspaces session))))

      (--first (eq (lsp--client-server-id (lsp--workspace-client it)) server-id) workspaces)))
```

The above shall become unnecessary once [this issue](https://github.com/emacs-lsp/lsp-mode/issues/2610) is solved.


### Reference

- [Yurii Ostapchuk at #ScalaUA​ - How I learned to stop worrying and love LSP (and Emacs :))](https://www.youtube.com/watch?v=x7ey0ifcqAg&feature=youtu.be)


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

```elisp
(require 'package)

;; Add melpa-stable to your packages repositories
(add-to-list 'package-archives '("melpa-stable" . "https://stable.melpa.org/packages/") t)

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
  :interpreter ("scala" . scala-mode))

;; Enable sbt mode for executing sbt commands
(use-package sbt-mode
  :commands sbt-start sbt-command
  :config
  ;; WORKAROUND: https://github.com/ensime/emacs-sbt-mode/issues/31
  ;; allows using SPACE when in the minibuffer
  (substitute-key-definition
   'minibuffer-complete-word
   'self-insert-command
   minibuffer-local-completion-map)
   ;; sbt-supershell kills sbt-mode:  https://github.com/hvesalai/emacs-sbt-mode/issues/152
   (setq sbt:program-options '("-Dsbt.supershell=false")))

(use-package eglot
  :pin melpa-stable
  ;; (optional) Automatically start metals for Scala files.
  :hook (scala-mode . eglot-ensure))
```

If you start Emacs now then it will fail since the `metals-emacs` binary does
not exist yet.

```scala mdoc:bootstrap:metals-emacs emacs

```

The `-Dmetals.client=emacs` flag is important since it configures Metals for
usage with Emacs.

```scala mdoc:generic

```

```scala mdoc:worksheet
```

```scala mdoc:scalafix

```