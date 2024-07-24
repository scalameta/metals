"use strict";(self.webpackChunk=self.webpackChunk||[]).push([[7389],{7433:(e,n,s)=>{s.r(n),s.d(n,{assets:()=>r,contentTitle:()=>i,default:()=>p,frontMatter:()=>o,metadata:()=>l,toc:()=>c});var t=s(4848),a=s(8453);const o={id:"emacs",title:"Emacs"},i=void 0,l={id:"editors/emacs",title:"Emacs",description:"Metals works in Emacs thanks to the",source:"@site/target/docs/editors/emacs.md",sourceDirName:"editors",slug:"/editors/emacs",permalink:"/metals/docs/editors/emacs",draft:!1,unlisted:!1,editUrl:"https://github.com/scalameta/metals/edit/main/docs/editors/emacs.md",tags:[],version:"current",frontMatter:{id:"emacs",title:"Emacs"},sidebar:"docs",previous:{title:"Sublime Text",permalink:"/metals/docs/editors/sublime"},next:{title:"Helix",permalink:"/metals/docs/editors/helix"}},r={},c=[{value:"Requirements",id:"requirements",level:2},{value:"Installation",id:"installation",level:2},{value:"Importing a build",id:"importing-a-build",level:2},{value:"Custom sbt launcher",id:"custom-sbt-launcher",level:3},{value:"Speeding up import",id:"speeding-up-import",level:3},{value:"Importing changes",id:"importing-changes",level:3},{value:"LSP Tips",id:"lsp-tips",level:2},{value:"Show navigable stack trace",id:"show-navigable-stack-trace",level:3},{value:"Reference",id:"reference",level:3},{value:"Manually trigger build import",id:"manually-trigger-build-import",level:2},{value:"Run doctor",id:"run-doctor",level:2},{value:"eglot",id:"eglot",level:3},{value:"Files and Directories to include in your Gitignore",id:"files-and-directories-to-include-in-your-gitignore",level:2},{value:"Worksheets",id:"worksheets",level:2},{value:"Getting started with Worksheets",id:"getting-started-with-worksheets",level:3},{value:"Evaluations",id:"evaluations",level:3},{value:"Using dependencies in worksheets",id:"using-dependencies-in-worksheets",level:3},{value:"Troubleshooting",id:"troubleshooting",level:3},{value:"Running scalafix rules",id:"running-scalafix-rules",level:2}];function d(e){const n={a:"a",blockquote:"blockquote",code:"code",em:"em",h2:"h2",h3:"h3",img:"img",li:"li",p:"p",pre:"pre",strong:"strong",ul:"ul",...(0,a.R)(),...e.components};return(0,t.jsxs)(t.Fragment,{children:[(0,t.jsxs)(n.p,{children:["Metals works in Emacs thanks to the\n",(0,t.jsx)(n.a,{href:"https://github.com/emacs-lsp/lsp-mode",children:(0,t.jsx)(n.code,{children:"lsp-mode"})})," package (another option is the ",(0,t.jsx)(n.a,{href:"#eglot",children:"Eglot"})," package)."]}),"\n",(0,t.jsx)(n.p,{children:(0,t.jsx)(n.img,{src:"https://i.imgur.com/KJQLMZ7.gif",alt:"Emacs demo"})}),"\n",(0,t.jsx)(n.h2,{id:"requirements",children:"Requirements"}),"\n",(0,t.jsxs)(n.p,{children:[(0,t.jsx)(n.strong,{children:"Java 11, 17 provided by OpenJDK or Oracle"}),". Eclipse OpenJ9 is not\nsupported, please make sure the ",(0,t.jsx)(n.code,{children:"JAVA_HOME"})," environment variable\npoints to a valid Java 11 or 17 installation."]}),"\n",(0,t.jsxs)(n.p,{children:[(0,t.jsx)(n.strong,{children:"macOS, Linux or Windows"}),". Metals is developed on many operating systems and\nevery PR is tested on Ubuntu, Windows and MacOS."]}),"\n",(0,t.jsxs)(n.p,{children:[(0,t.jsx)(n.strong,{children:"Scala 2.13, 2.12, 2.11 and Scala 3"}),". Metals supports these Scala versions:"]}),"\n",(0,t.jsxs)(n.ul,{children:["\n",(0,t.jsxs)(n.li,{children:["\n",(0,t.jsxs)(n.p,{children:[(0,t.jsx)(n.strong,{children:"Scala 2.11"}),":\n2.11.12"]}),"\n"]}),"\n",(0,t.jsxs)(n.li,{children:["\n",(0,t.jsxs)(n.p,{children:[(0,t.jsx)(n.strong,{children:"Scala 2.12"}),":\n2.12.12, 2.12.13, 2.12.14, 2.12.15"]}),"\n"]}),"\n"]}),"\n",(0,t.jsx)(n.p,{children:"Scala 3 versions from 3.3.4 are automatically supported by Metals."}),"\n",(0,t.jsx)(n.p,{children:"Any older Scala versions will no longer get bugfixes, but should still\nwork properly with newest Metals."}),"\n",(0,t.jsx)(n.p,{children:"Note that 2.11.x support is deprecated and it will be removed in future releases.\nIt's recommended to upgrade to Scala 2.12 or Scala 2.13"}),"\n",(0,t.jsx)(n.h2,{id:"installation",children:"Installation"}),"\n",(0,t.jsxs)(n.p,{children:["To use Metals in Emacs, place this snippet in your Emacs configuration (for example .emacs.d/init.el) to load\n",(0,t.jsx)(n.code,{children:"lsp-mode"})," along with its dependencies:"]}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{className:"language-elisp",children:'(require \'package)\n\n;; Add melpa to your packages repositories\n(add-to-list \'package-archives \'("melpa" . "https://melpa.org/packages/") t)\n\n(package-initialize)\n\n;; Install use-package if not already installed\n(unless (package-installed-p \'use-package)\n  (package-refresh-contents)\n  (package-install \'use-package))\n\n(require \'use-package)\n\n;; Enable defer and ensure by default for use-package\n;; Keep auto-save/backup files separate from source code:  https://github.com/scalameta/metals/issues/1027\n(setq use-package-always-defer t\n      use-package-always-ensure t\n      backup-directory-alist `((".*" . ,temporary-file-directory))\n      auto-save-file-name-transforms `((".*" ,temporary-file-directory t)))\n\n;; Enable scala-mode for highlighting, indentation and motion commands\n(use-package scala-mode\n  :interpreter ("scala" . scala-mode))\n\n;; Enable sbt mode for executing sbt commands\n(use-package sbt-mode\n  :commands sbt-start sbt-command\n  :config\n  ;; WORKAROUND: https://github.com/ensime/emacs-sbt-mode/issues/31\n  ;; allows using SPACE when in the minibuffer\n  (substitute-key-definition\n   \'minibuffer-complete-word\n   \'self-insert-command\n   minibuffer-local-completion-map)\n   ;; sbt-supershell kills sbt-mode:  https://github.com/hvesalai/emacs-sbt-mode/issues/152\n   (setq sbt:program-options \'("-Dsbt.supershell=false")))\n\n;; Enable nice rendering of diagnostics like compile errors.\n(use-package flycheck\n  :init (global-flycheck-mode))\n\n(use-package lsp-mode\n  ;; Optional - enable lsp-mode automatically in scala files\n  ;; You could also swap out lsp for lsp-deffered in order to defer loading\n  :hook  (scala-mode . lsp)\n         (lsp-mode . lsp-lens-mode)\n  :config\n  ;; Uncomment following section if you would like to tune lsp-mode performance according to\n  ;; https://emacs-lsp.github.io/lsp-mode/page/performance/\n  ;; (setq gc-cons-threshold 100000000) ;; 100mb\n  ;; (setq read-process-output-max (* 1024 1024)) ;; 1mb\n  ;; (setq lsp-idle-delay 0.500)\n  ;; (setq lsp-log-io nil)\n  ;; (setq lsp-completion-provider :capf)\n  (setq lsp-prefer-flymake nil)\n  ;; Makes LSP shutdown the metals server when all buffers in the project are closed.\n  ;; https://emacs-lsp.github.io/lsp-mode/page/settings/mode/#lsp-keep-workspace-alive\n  (setq lsp-keep-workspace-alive nil))\n\n;; Add metals backend for lsp-mode\n(use-package lsp-metals)\n\n;; Enable nice rendering of documentation on hover\n;;   Warning: on some systems this package can reduce your emacs responsiveness significally.\n;;   (See: https://emacs-lsp.github.io/lsp-mode/page/performance/)\n;;   In that case you have to not only disable this but also remove from the packages since\n;;   lsp-mode can activate it automatically.\n(use-package lsp-ui)\n\n;; lsp-mode supports snippets, but in order for them to work you need to use yasnippet\n;; If you don\'t want to use snippets set lsp-enable-snippet to nil in your lsp-mode settings\n;; to avoid odd behavior with snippets and indentation\n(use-package yasnippet)\n\n;; Use company-capf as a completion provider.\n;;\n;; To Company-lsp users:\n;;   Company-lsp is no longer maintained and has been removed from MELPA.\n;;   Please migrate to company-capf.\n(use-package company\n  :hook (scala-mode . company-mode)\n  :config\n  (setq lsp-completion-provider :capf))\n\n;; Posframe is a pop-up tool that must be manually installed for dap-mode\n(use-package posframe)\n\n;; Use the Debug Adapter Protocol for running tests and debugging\n(use-package dap-mode\n  :hook\n  (lsp-mode . dap-mode)\n  (lsp-mode . dap-ui-mode))\n'})}),"\n",(0,t.jsxs)(n.blockquote,{children:["\n",(0,t.jsxs)(n.p,{children:["You may need to disable other packages like ",(0,t.jsx)(n.code,{children:"ensime"})," or sbt server to prevent\nconflicts with Metals."]}),"\n"]}),"\n",(0,t.jsxs)(n.p,{children:["Next you have to install metals server. Emacs can do it for you when ",(0,t.jsx)(n.code,{children:"lsp-mode"}),"\nis enabled in a scala buffer or via ",(0,t.jsx)(n.code,{children:"lsp-install-server"})," command. Also you can\ndo it manually executing ",(0,t.jsx)(n.code,{children:"coursier install metals"})," and configuring ",(0,t.jsx)(n.code,{children:"$PATH"}),"\nvariable properly."]}),"\n",(0,t.jsx)(n.h2,{id:"importing-a-build",children:"Importing a build"}),"\n",(0,t.jsxs)(n.p,{children:['The first time you open Metals in a new workspace it prompts you to import the build.\nType "Import build" or press ',(0,t.jsx)(n.code,{children:"Tab"}),' and select "Import build" to start the installation step.']}),"\n",(0,t.jsx)(n.p,{children:(0,t.jsx)(n.img,{src:"https://i.imgur.com/UdwMQFk.png",alt:"Import build"})}),"\n",(0,t.jsxs)(n.ul,{children:["\n",(0,t.jsx)(n.li,{children:'"Not now" disables this prompt for 2 minutes.'}),"\n",(0,t.jsxs)(n.li,{children:['"Don\'t show again" disables this prompt forever, use ',(0,t.jsx)(n.code,{children:"rm -rf .metals/"})," to re-enable\nthe prompt."]}),"\n",(0,t.jsxs)(n.li,{children:["Use ",(0,t.jsx)(n.code,{children:"tail -f .metals/metals.log"})," to watch the build import progress."]}),"\n",(0,t.jsxs)(n.li,{children:["Behind the scenes, Metals uses ",(0,t.jsx)(n.a,{href:"https://scalacenter.github.io/bloop/",children:"Bloop"})," to\nimport sbt builds, but you don't need Bloop installed on your machine to run this step."]}),"\n"]}),"\n",(0,t.jsxs)(n.p,{children:["Once the import step completes, compilation starts for your open ",(0,t.jsx)(n.code,{children:"*.scala"}),"\nfiles."]}),"\n",(0,t.jsx)(n.p,{children:"Once the sources have compiled successfully, you can navigate the codebase with\ngoto definition."}),"\n",(0,t.jsx)(n.h3,{id:"custom-sbt-launcher",children:"Custom sbt launcher"}),"\n",(0,t.jsxs)(n.p,{children:["By default, Metals runs an embedded ",(0,t.jsx)(n.code,{children:"sbt-launch.jar"})," launcher that respects ",(0,t.jsx)(n.code,{children:".sbtopts"})," and ",(0,t.jsx)(n.code,{children:".jvmopts"}),".\nHowever, the environment variables ",(0,t.jsx)(n.code,{children:"SBT_OPTS"})," and ",(0,t.jsx)(n.code,{children:"JAVA_OPTS"})," are not respected."]}),"\n",(0,t.jsxs)(n.p,{children:["Update the server property ",(0,t.jsx)(n.code,{children:"-Dmetals.sbt-script=/path/to/sbt"})," to use a custom\n",(0,t.jsx)(n.code,{children:"sbt"})," script instead of the default Metals launcher if you need further\ncustomizations like reading environment variables."]}),"\n",(0,t.jsx)(n.h3,{id:"speeding-up-import",children:"Speeding up import"}),"\n",(0,t.jsx)(n.p,{children:'The "Import build" step can take a long time, especially the first time you\nrun it in a new build. The exact time depends on the complexity of the build and\nif library dependencies need to be downloaded. For example, this step can take\neverything from 10 seconds in small cached builds up to 10-15 minutes in large\nuncached builds.'}),"\n",(0,t.jsxs)(n.p,{children:["Consult the ",(0,t.jsx)(n.a,{href:"https://scalacenter.github.io/bloop/docs/build-tools/sbt#speeding-up-build-export",children:"Bloop documentation"}),"\nto learn how to speed up build import."]}),"\n",(0,t.jsx)(n.h3,{id:"importing-changes",children:"Importing changes"}),"\n",(0,t.jsxs)(n.p,{children:["When you change ",(0,t.jsx)(n.code,{children:"build.sbt"})," or sources under ",(0,t.jsx)(n.code,{children:"project/"}),", you will be prompted to\nre-import the build."]}),"\n",(0,t.jsx)(n.p,{children:(0,t.jsx)(n.img,{src:"https://i.imgur.com/UFK0p8i.png",alt:"Import sbt changes"})}),"\n",(0,t.jsx)(n.h2,{id:"lsp-tips",children:"LSP Tips"}),"\n",(0,t.jsx)(n.h3,{id:"show-navigable-stack-trace",children:"Show navigable stack trace"}),"\n",(0,t.jsxs)(n.p,{children:["You can annotate your stack trace with code lenses (which requires the\nfollowing bit of configuration mentioned earlier: ",(0,t.jsx)(n.code,{children:"(lsp-mode . lsp-lens-mode)"}),").\nThese allow you to run actions from your code."]}),"\n",(0,t.jsx)(n.p,{children:"One of these actions allow you to navigate your stack trace."}),"\n",(0,t.jsxs)(n.p,{children:["You can annotate any stack trace by marking a stack trace with your\nregion and using ",(0,t.jsx)(n.code,{children:"M-x lsp-metals-analyze-stacktrace"})," on it."]}),"\n",(0,t.jsx)(n.p,{children:'This will open a new Scala buffer that has code lenses annotations:\njust click on the small "open" annotation to navigate to the source\ncode relative to your stack trace.'}),"\n",(0,t.jsxs)(n.p,{children:["This will work as long as the buffer you are marking your stack trace\non exists within the project directory tracked by ",(0,t.jsx)(n.code,{children:"lsp-mode"}),", because\n",(0,t.jsx)(n.code,{children:"lsp-metals-analyze-stacktrace"})," needs the ",(0,t.jsx)(n.code,{children:"lsp"})," workspace to find the\nlocation of your errors."]}),"\n",(0,t.jsxs)(n.p,{children:["Note that if you try to do that from ",(0,t.jsx)(n.code,{children:"sbt-mode"}),", you may get an error\nunless you patch ",(0,t.jsx)(n.code,{children:"lsp-find-workspace"})," with the following:"]}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{className:"language-elisp",children:'(defun lsp-find-workspace (server-id &optional file-name)\n    "Find workspace for SERVER-ID for FILE-NAME."\n    (-when-let* ((session (lsp-session))\n                 (folder->servers (lsp-session-folder->servers session))\n                 (workspaces (if file-name\n                                 (let* ((folder (lsp-find-session-folder session file-name))\n                                        (folder-last-char (substring folder (- (length folder) 1) (length folder)))\n                                        (key (if (string= folder-last-char "/") (substring folder 0 (- (length folder) 1)) folder)))\n                                   (gethash key folder->servers))\n                               (lsp--session-workspaces session))))\n\n      (--first (eq (lsp--client-server-id (lsp--workspace-client it)) server-id) workspaces)))\n'})}),"\n",(0,t.jsxs)(n.p,{children:["The above shall become unnecessary once ",(0,t.jsx)(n.a,{href:"https://github.com/emacs-lsp/lsp-mode/issues/2610",children:"this issue"})," is solved."]}),"\n",(0,t.jsx)(n.h3,{id:"reference",children:"Reference"}),"\n",(0,t.jsxs)(n.ul,{children:["\n",(0,t.jsx)(n.li,{children:(0,t.jsx)(n.a,{href:"https://www.youtube.com/watch?v=x7ey0ifcqAg&feature=youtu.be",children:"Yurii Ostapchuk at #ScalaUA\u200b - How I learned to stop worrying and love LSP (and Emacs :))"})}),"\n"]}),"\n",(0,t.jsx)(n.h2,{id:"manually-trigger-build-import",children:"Manually trigger build import"}),"\n",(0,t.jsxs)(n.p,{children:["To manually trigger a build import, run ",(0,t.jsx)(n.code,{children:"M-x lsp-metals-build-import"}),"."]}),"\n",(0,t.jsx)(n.p,{children:(0,t.jsx)(n.img,{src:"https://i.imgur.com/SvGXJDK.png",alt:"Import build command"})}),"\n",(0,t.jsx)(n.h2,{id:"run-doctor",children:"Run doctor"}),"\n",(0,t.jsxs)(n.p,{children:["Run ",(0,t.jsx)(n.code,{children:"M-x lsp-metals-doctor-run"})," to troubleshoot potential configuration problems\nin your build."]}),"\n",(0,t.jsx)(n.p,{children:(0,t.jsx)(n.img,{src:"https://i.imgur.com/yelm0jd.png",alt:"Run doctor command"})}),"\n",(0,t.jsx)(n.h3,{id:"eglot",children:"eglot"}),"\n",(0,t.jsxs)(n.p,{children:["There is an alternative LSP client called\n",(0,t.jsx)(n.a,{href:"https://github.com/joaotavora/eglot",children:"eglot"})," that might be worth trying out if\nyou want to use an alternative to lsp-mode."]}),"\n",(0,t.jsx)(n.p,{children:"To configure Eglot with Metals:"}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{className:"language-elisp",children:"(require 'package)\n\n;; Add melpa-stable to your packages repositories\n(add-to-list 'package-archives '(\"melpa-stable\" . \"https://stable.melpa.org/packages/\") t)\n\n(package-initialize)\n\n;; Install use-package if not already installed\n(unless (package-installed-p 'use-package)\n  (package-refresh-contents)\n  (package-install 'use-package))\n\n(require 'use-package)\n\n;; Enable defer and ensure by default for use-package\n(setq use-package-always-defer t\n      use-package-always-ensure t)\n\n;; Enable scala-mode and sbt-mode\n(use-package scala-mode\n  :interpreter (\"scala\" . scala-mode))\n\n;; Enable sbt mode for executing sbt commands\n(use-package sbt-mode\n  :commands sbt-start sbt-command\n  :config\n  ;; WORKAROUND: https://github.com/ensime/emacs-sbt-mode/issues/31\n  ;; allows using SPACE when in the minibuffer\n  (substitute-key-definition\n   'minibuffer-complete-word\n   'self-insert-command\n   minibuffer-local-completion-map)\n   ;; sbt-supershell kills sbt-mode:  https://github.com/hvesalai/emacs-sbt-mode/issues/152\n   (setq sbt:program-options '(\"-Dsbt.supershell=false\")))\n\n(use-package eglot\n  :pin melpa-stable\n  ;; (optional) Automatically start metals for Scala files.\n  :hook (scala-mode . eglot-ensure))\n"})}),"\n",(0,t.jsxs)(n.p,{children:["If you start Emacs now then it will fail since the ",(0,t.jsx)(n.code,{children:"metals-emacs"})," binary does\nnot exist yet."]}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{className:"language-sh",children:"coursier bootstrap org.scalameta:metals_2.13:1.3.4 -o metals -f\n"})}),"\n",(0,t.jsx)(n.p,{children:"(optional) It's recommended to enable JVM string de-duplication and provide a\ngenerous stack size and memory options."}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{className:"language-sh",children:"coursier bootstrap \\\n  --java-opt -XX:+UseG1GC \\\n  --java-opt -XX:+UseStringDeduplication  \\\n  --java-opt -Xss4m \\\n  --java-opt -Xms100m \\\n  org.scalameta:metals_2.13:1.3.4 -o metals -f\n"})}),"\n",(0,t.jsxs)(n.p,{children:["The ",(0,t.jsx)(n.code,{children:"-Dmetals.client=emacs"})," flag is important since it configures Metals for\nusage with Emacs."]}),"\n",(0,t.jsx)(n.h2,{id:"files-and-directories-to-include-in-your-gitignore",children:"Files and Directories to include in your Gitignore"}),"\n",(0,t.jsxs)(n.p,{children:["The Metals server places logs and other files in the ",(0,t.jsx)(n.code,{children:".metals"})," directory. The\nBloop compile server places logs and compilation artifacts in the ",(0,t.jsx)(n.code,{children:".bloop"}),"\ndirectory. The Bloop plugin that generates Bloop configuration is added in the\n",(0,t.jsx)(n.code,{children:"metals.sbt"})," file, which is added at ",(0,t.jsx)(n.code,{children:"project/metals.sbt"})," as well as further\n",(0,t.jsx)(n.code,{children:"project"})," directories depending on how deep ",(0,t.jsx)(n.code,{children:"*.sbt"})," files need to be supported.\nTo support each ",(0,t.jsx)(n.code,{children:"*.sbt"})," file Metals needs to create an additional file at\n",(0,t.jsx)(n.code,{children:"./project/project/metals.sbt"})," relative to the sbt file.\nWorking with Ammonite scripts will place compiled scripts into the ",(0,t.jsx)(n.code,{children:".ammonite"})," directory.\nIt's recommended to exclude these directories and files\nfrom version control systems like git."]}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{className:"language-sh",children:"# ~/.gitignore\n.metals/\n.bloop/\n.ammonite/\nmetals.sbt\n"})}),"\n",(0,t.jsx)(n.h2,{id:"worksheets",children:"Worksheets"}),"\n",(0,t.jsxs)(n.p,{children:["Worksheets are a great way to explore an api, try out an idea, or code\nup an example and quickly see the evaluated expression or result. Behind\nthe scenes worksheets are powered by the great work done in\n",(0,t.jsx)(n.a,{href:"https://scalameta.org/mdoc/",children:"mdoc"}),"."]}),"\n",(0,t.jsx)(n.h3,{id:"getting-started-with-worksheets",children:"Getting started with Worksheets"}),"\n",(0,t.jsxs)(n.p,{children:["To get started with a worksheet you can either use the ",(0,t.jsx)(n.code,{children:"metals.new-scala-file"}),"\ncommand and select ",(0,t.jsx)(n.em,{children:"Worksheet"})," or create a file called ",(0,t.jsx)(n.code,{children:"*.worksheet.sc"}),".\nThis format is important since this is what tells Metals that it's meant to be\ntreated as a worksheet and not just a Scala script. Where you create the\nscript also matters. If you'd like to use classes and values from your\nproject, you need to make sure the worksheet is created inside of your sources next to any existing Scala files.\ndirectory. You can still create a worksheet in other places, but you will\nonly have access to the standard library and your dependencies."]}),"\n",(0,t.jsx)(n.h3,{id:"evaluations",children:"Evaluations"}),"\n",(0,t.jsx)(n.p,{children:"After saving you'll see the result of the expression as a comment as the end of the line.\nYou may not see the full result for example if it's too long, so you are also\nable to hover on the comment to expand."}),"\n",(0,t.jsxs)(n.p,{children:["Keep in mind that you don't need to wrap your code in an ",(0,t.jsx)(n.code,{children:"object"}),". In worksheets\neverything can be evaluated at the top level."]}),"\n",(0,t.jsx)(n.h3,{id:"using-dependencies-in-worksheets",children:"Using dependencies in worksheets"}),"\n",(0,t.jsx)(n.p,{children:"You are able to include an external dependency in your worksheet by including\nit in one of the following two ways."}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{className:"language-scala",children:"// $dep.`organisation`::artifact:version` style\nimport $dep.`com.lihaoyi::scalatags:0.7.0`\n\n// $ivy.`organisation::artifact:version` style\nimport $ivy.`com.lihaoyi::scalatags:0.7.0`\n"})}),"\n",(0,t.jsxs)(n.p,{children:[(0,t.jsx)(n.code,{children:"::"})," is the same as ",(0,t.jsx)(n.code,{children:"%%"})," in sbt, which will append the current Scala binary version\nto the artifact name."]}),"\n",(0,t.jsxs)(n.p,{children:["You can also import ",(0,t.jsx)(n.code,{children:"scalac"})," options in a special ",(0,t.jsx)(n.code,{children:"$scalac"})," import like below:"]}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{className:"language-scala",children:"import $scalac.`-Ywarn-unused`\n"})}),"\n",(0,t.jsx)(n.h3,{id:"troubleshooting",children:"Troubleshooting"}),"\n",(0,t.jsx)(n.p,{children:"Since worksheets are not standard Scala files, you may run into issues with some constructs.\nFor example, you may see an error like this:"}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{children:"value classes may not be a member of another class - mdoc\n"})}),"\n",(0,t.jsx)(n.p,{children:"This means that one of the classes defined in the worksheet extends AnyVal, which is\nnot currently supported. You can work around this by moving the class to a separate file or removing\nthe AnyVal parent."}),"\n",(0,t.jsx)(n.h2,{id:"running-scalafix-rules",children:"Running scalafix rules"}),"\n",(0,t.jsxs)(n.p,{children:["Scalafix allows users to specify some refactoring and linting rules that can be applied to your\ncodebase. Please checkout the ",(0,t.jsx)(n.a,{href:"https://scalacenter.github.io/scalafix",children:"scalafix website"})," for more information."]}),"\n",(0,t.jsxs)(n.p,{children:["Since Metals v0.11.7 it's now possible to run scalafix rules using a special\ncommand ",(0,t.jsx)(n.code,{children:"metals.scalafix-run"}),".\nThis should run all the rules defined in your ",(0,t.jsx)(n.code,{children:".scalafix.conf"})," file. All built-in rules\nand the ",(0,t.jsx)(n.a,{href:"https://scalacenter.github.io/scalafix/docs/rules/community-rules.html#hygiene-rules",children:"community hygiene ones"})," can\nbe run without any additional settings. However, for all the other rules users need to\nadd an additional dependency in the ",(0,t.jsx)(n.code,{children:"metals.scalafixRulesDependencies"})," user setting.\nThose rules need to be in form of strings such as ",(0,t.jsx)(n.code,{children:"com.github.liancheng::organize-imports:0.6.0"}),", which\nfollows the same convention as ",(0,t.jsx)(n.a,{href:"https://get-coursier.io/",children:"coursier dependencies"}),"."]}),"\n",(0,t.jsx)(n.p,{children:"A sample scalafix configuration can be seen below:"}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{className:"language-hocon",children:'rules = [\n  OrganizeImports,\n  ExplicitResultTypes,\n  RemoveUnused\n]\n\nRemoveUnused.imports = false\n\nOrganizeImports.groupedImports = Explode\nOrganizeImports.expandRelative = true\nOrganizeImports.removeUnused = true\nOrganizeImports.groups = [\n  "re:javax?\\."\n  "scala."\n  "scala.meta."\n  "*"\n]\n\n'})})]})}function p(e={}){const{wrapper:n}={...(0,a.R)(),...e.components};return n?(0,t.jsx)(n,{...e,children:(0,t.jsx)(d,{...e})}):d(e)}},8453:(e,n,s)=>{s.d(n,{R:()=>i,x:()=>l});var t=s(6540);const a={},o=t.createContext(a);function i(e){const n=t.useContext(o);return t.useMemo((function(){return"function"==typeof e?e(n):{...n,...e}}),[n,e])}function l(e){let n;return n=e.disableParentContext?"function"==typeof e.components?e.components(a):e.components||a:i(e.components),t.createElement(o.Provider,{value:n},e.children)}}}]);