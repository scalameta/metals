"use strict";(self.webpackChunk=self.webpackChunk||[]).push([[6520],{3905:(e,t,n)=>{n.d(t,{Zo:()=>c,kt:()=>m});var a=n(7294);function s(e,t,n){return t in e?Object.defineProperty(e,t,{value:n,enumerable:!0,configurable:!0,writable:!0}):e[t]=n,e}function o(e,t){var n=Object.keys(e);if(Object.getOwnPropertySymbols){var a=Object.getOwnPropertySymbols(e);t&&(a=a.filter((function(t){return Object.getOwnPropertyDescriptor(e,t).enumerable}))),n.push.apply(n,a)}return n}function l(e){for(var t=1;t<arguments.length;t++){var n=null!=arguments[t]?arguments[t]:{};t%2?o(Object(n),!0).forEach((function(t){s(e,t,n[t])})):Object.getOwnPropertyDescriptors?Object.defineProperties(e,Object.getOwnPropertyDescriptors(n)):o(Object(n)).forEach((function(t){Object.defineProperty(e,t,Object.getOwnPropertyDescriptor(n,t))}))}return e}function i(e,t){if(null==e)return{};var n,a,s=function(e,t){if(null==e)return{};var n,a,s={},o=Object.keys(e);for(a=0;a<o.length;a++)n=o[a],t.indexOf(n)>=0||(s[n]=e[n]);return s}(e,t);if(Object.getOwnPropertySymbols){var o=Object.getOwnPropertySymbols(e);for(a=0;a<o.length;a++)n=o[a],t.indexOf(n)>=0||Object.prototype.propertyIsEnumerable.call(e,n)&&(s[n]=e[n])}return s}var r=a.createContext({}),p=function(e){var t=a.useContext(r),n=t;return e&&(n="function"==typeof e?e(t):l(l({},t),e)),n},c=function(e){var t=p(e.components);return a.createElement(r.Provider,{value:t},e.children)},u={inlineCode:"code",wrapper:function(e){var t=e.children;return a.createElement(a.Fragment,{},t)}},d=a.forwardRef((function(e,t){var n=e.components,s=e.mdxType,o=e.originalType,r=e.parentName,c=i(e,["components","mdxType","originalType","parentName"]),d=p(n),m=s,h=d["".concat(r,".").concat(m)]||d[m]||u[m]||o;return n?a.createElement(h,l(l({ref:t},c),{},{components:n})):a.createElement(h,l({ref:t},c))}));function m(e,t){var n=arguments,s=t&&t.mdxType;if("string"==typeof e||s){var o=n.length,l=new Array(o);l[0]=d;var i={};for(var r in t)hasOwnProperty.call(t,r)&&(i[r]=t[r]);i.originalType=e,i.mdxType="string"==typeof e?e:s,l[1]=i;for(var p=2;p<o;p++)l[p]=n[p];return a.createElement.apply(null,l)}return a.createElement.apply(null,n)}d.displayName="MDXCreateElement"},6761:(e,t,n)=>{n.r(t),n.d(t,{contentTitle:()=>r,default:()=>d,frontMatter:()=>i,metadata:()=>p,toc:()=>c});var a=n(7462),s=n(3366),o=(n(7294),n(3905)),l=["components"],i={id:"emacs",title:"Emacs"},r=void 0,p={unversionedId:"editors/emacs",id:"editors/emacs",title:"Emacs",description:"Metals works in Emacs thanks to the",source:"@site/target/docs/editors/emacs.md",sourceDirName:"editors",slug:"/editors/emacs",permalink:"/metals/docs/editors/emacs",editUrl:"https://github.com/scalameta/metals/edit/main/docs/editors/emacs.md",tags:[],version:"current",frontMatter:{id:"emacs",title:"Emacs"},sidebar:"docs",previous:{title:"Sublime Text",permalink:"/metals/docs/editors/sublime"},next:{title:"Online IDEs",permalink:"/metals/docs/editors/online-ides"}},c=[{value:"Requirements",id:"requirements",children:[],level:2},{value:"Installation",id:"installation",children:[],level:2},{value:"Importing a build",id:"importing-a-build",children:[{value:"Custom sbt launcher",id:"custom-sbt-launcher",children:[],level:3},{value:"Speeding up import",id:"speeding-up-import",children:[],level:3},{value:"Importing changes",id:"importing-changes",children:[],level:3}],level:2},{value:"LSP Tips",id:"lsp-tips",children:[{value:"Show navigable stack trace",id:"show-navigable-stack-trace",children:[],level:3},{value:"Reference",id:"reference",children:[],level:3}],level:2},{value:"Manually trigger build import",id:"manually-trigger-build-import",children:[],level:2},{value:"Run doctor",id:"run-doctor",children:[{value:"eglot",id:"eglot",children:[],level:3}],level:2},{value:"Files and Directories to include in your Gitignore",id:"files-and-directories-to-include-in-your-gitignore",children:[],level:2},{value:"Worksheets",id:"worksheets",children:[{value:"Getting started with Worksheets",id:"getting-started-with-worksheets",children:[],level:3},{value:"Evaluations",id:"evaluations",children:[],level:3},{value:"Using dependencies in worksheets",id:"using-dependencies-in-worksheets",children:[],level:3}],level:2},{value:"Running scalafix rules",id:"running-scalafix-rules",children:[],level:2}],u={toc:c};function d(e){var t=e.components,n=(0,s.Z)(e,l);return(0,o.kt)("wrapper",(0,a.Z)({},u,n,{components:t,mdxType:"MDXLayout"}),(0,o.kt)("p",null,"Metals works in Emacs thanks to the\n",(0,o.kt)("a",{parentName:"p",href:"https://github.com/emacs-lsp/lsp-mode"},(0,o.kt)("inlineCode",{parentName:"a"},"lsp-mode"))," package."),(0,o.kt)("p",null,(0,o.kt)("img",{parentName:"p",src:"https://i.imgur.com/KJQLMZ7.gif",alt:"Emacs demo"})),(0,o.kt)("h2",{id:"requirements"},"Requirements"),(0,o.kt)("p",null,(0,o.kt)("strong",{parentName:"p"},"Java 8, 11, 17 provided by OpenJDK or Oracle"),". Eclipse OpenJ9 is not\nsupported, please make sure the ",(0,o.kt)("inlineCode",{parentName:"p"},"JAVA_HOME")," environment variable\npoints to a valid Java 8, 11 or 17 installation."),(0,o.kt)("p",null,(0,o.kt)("strong",{parentName:"p"},"macOS, Linux or Windows"),". Metals is developed on many operating systems and\nevery PR is tested on Ubuntu, Windows and MacOS."),(0,o.kt)("p",null,(0,o.kt)("strong",{parentName:"p"},"Scala 2.13, 2.12, 2.11 and Scala 3"),". Metals supports these Scala versions:"),(0,o.kt)("ul",null,(0,o.kt)("li",{parentName:"ul"},(0,o.kt)("p",{parentName:"li"},(0,o.kt)("strong",{parentName:"p"},"Scala 2.13"),":\n2.13.8, 2.13.7, 2.13.6, 2.13.5, 2.13.4, 2.13.3, 2.13.2, 2.13.1")),(0,o.kt)("li",{parentName:"ul"},(0,o.kt)("p",{parentName:"li"},(0,o.kt)("strong",{parentName:"p"},"Scala 2.12"),":\n2.12.16, 2.12.15, 2.12.14, 2.12.13, 2.12.12, 2.12.11, 2.12.10, 2.12.9, 2.12.8")),(0,o.kt)("li",{parentName:"ul"},(0,o.kt)("p",{parentName:"li"},(0,o.kt)("strong",{parentName:"p"},"Scala 2.11"),":\n2.11.12")),(0,o.kt)("li",{parentName:"ul"},(0,o.kt)("p",{parentName:"li"},(0,o.kt)("strong",{parentName:"p"},"Scala 3"),":\n3.2.0-RC1, 3.1.3, 3.1.2, 3.1.1, 3.1.0, 3.0.2, 3.0.1, 3.0.0"))),(0,o.kt)("p",null,"Note that 2.11.x support is deprecated and it will be removed in future releases.\nIt's recommended to upgrade to Scala 2.12 or Scala 2.13"),(0,o.kt)("h2",{id:"installation"},"Installation"),(0,o.kt)("p",null,"To use Metals in Emacs, place this snippet in your Emacs configuration (for example .emacs.d/init.el) to load\n",(0,o.kt)("inlineCode",{parentName:"p"},"lsp-mode")," along with its dependencies:"),(0,o.kt)("pre",null,(0,o.kt)("code",{parentName:"pre",className:"language-el"},'(require \'package)\n\n;; Add melpa to your packages repositories\n(add-to-list \'package-archives \'("melpa" . "https://melpa.org/packages/") t)\n\n(package-initialize)\n\n;; Install use-package if not already installed\n(unless (package-installed-p \'use-package)\n  (package-refresh-contents)\n  (package-install \'use-package))\n\n(require \'use-package)\n\n;; Enable defer and ensure by default for use-package\n;; Keep auto-save/backup files separate from source code:  https://github.com/scalameta/metals/issues/1027\n(setq use-package-always-defer t\n      use-package-always-ensure t\n      backup-directory-alist `((".*" . ,temporary-file-directory))\n      auto-save-file-name-transforms `((".*" ,temporary-file-directory t)))\n\n;; Enable scala-mode for highlighting, indentation and motion commands\n(use-package scala-mode\n  :interpreter\n    ("scala" . scala-mode))\n\n;; Enable sbt mode for executing sbt commands\n(use-package sbt-mode\n  :commands sbt-start sbt-command\n  :config\n  ;; WORKAROUND: https://github.com/ensime/emacs-sbt-mode/issues/31\n  ;; allows using SPACE when in the minibuffer\n  (substitute-key-definition\n   \'minibuffer-complete-word\n   \'self-insert-command\n   minibuffer-local-completion-map)\n   ;; sbt-supershell kills sbt-mode:  https://github.com/hvesalai/emacs-sbt-mode/issues/152\n   (setq sbt:program-options \'("-Dsbt.supershell=false"))\n)\n\n;; Enable nice rendering of diagnostics like compile errors.\n(use-package flycheck\n  :init (global-flycheck-mode))\n\n(use-package lsp-mode\n  ;; Optional - enable lsp-mode automatically in scala files\n  :hook  (scala-mode . lsp)\n         (lsp-mode . lsp-lens-mode)\n  :config\n  ;; Uncomment following section if you would like to tune lsp-mode performance according to\n  ;; https://emacs-lsp.github.io/lsp-mode/page/performance/\n  ;;       (setq gc-cons-threshold 100000000) ;; 100mb\n  ;;       (setq read-process-output-max (* 1024 1024)) ;; 1mb\n  ;;       (setq lsp-idle-delay 0.500)\n  ;;       (setq lsp-log-io nil)\n  ;;       (setq lsp-completion-provider :capf)\n  (setq lsp-prefer-flymake nil))\n\n;; Add metals backend for lsp-mode\n(use-package lsp-metals)\n\n;; Enable nice rendering of documentation on hover\n;;   Warning: on some systems this package can reduce your emacs responsiveness significally.\n;;   (See: https://emacs-lsp.github.io/lsp-mode/page/performance/)\n;;   In that case you have to not only disable this but also remove from the packages since\n;;   lsp-mode can activate it automatically.\n(use-package lsp-ui)\n\n;; lsp-mode supports snippets, but in order for them to work you need to use yasnippet\n;; If you don\'t want to use snippets set lsp-enable-snippet to nil in your lsp-mode settings\n;;   to avoid odd behavior with snippets and indentation\n(use-package yasnippet)\n\n;; Use company-capf as a completion provider.\n;;\n;; To Company-lsp users:\n;;   Company-lsp is no longer maintained and has been removed from MELPA.\n;;   Please migrate to company-capf.\n(use-package company\n  :hook (scala-mode . company-mode)\n  :config\n  (setq lsp-completion-provider :capf))\n\n;; Use the Debug Adapter Protocol for running tests and debugging\n(use-package posframe\n  ;; Posframe is a pop-up tool that must be manually installed for dap-mode\n  )\n(use-package dap-mode\n  :hook\n  (lsp-mode . dap-mode)\n  (lsp-mode . dap-ui-mode)\n  )\n')),(0,o.kt)("blockquote",null,(0,o.kt)("p",{parentName:"blockquote"},"You may need to disable other packages like ",(0,o.kt)("inlineCode",{parentName:"p"},"ensime")," or sbt server to prevent\nconflicts with Metals.")),(0,o.kt)("p",null,"Next you have to install metals server. Emacs can do it for you when ",(0,o.kt)("inlineCode",{parentName:"p"},"lsp-mode"),"\nis enabled in a scala buffer or via ",(0,o.kt)("inlineCode",{parentName:"p"},"lsp-install-server")," command. Also you can\ndo it manually executing ",(0,o.kt)("inlineCode",{parentName:"p"},"coursier install metals")," and configuring ",(0,o.kt)("inlineCode",{parentName:"p"},"$PATH"),"\nvariable properly."),(0,o.kt)("h2",{id:"importing-a-build"},"Importing a build"),(0,o.kt)("p",null,'The first time you open Metals in a new workspace it prompts you to import the build.\nType "Import build" or press ',(0,o.kt)("inlineCode",{parentName:"p"},"Tab"),' and select "Import build" to start the installation step.'),(0,o.kt)("p",null,(0,o.kt)("img",{parentName:"p",src:"https://i.imgur.com/UdwMQFk.png",alt:"Import build"})),(0,o.kt)("ul",null,(0,o.kt)("li",{parentName:"ul"},'"Not now" disables this prompt for 2 minutes.'),(0,o.kt)("li",{parentName:"ul"},'"Don\'t show again" disables this prompt forever, use ',(0,o.kt)("inlineCode",{parentName:"li"},"rm -rf .metals/")," to re-enable\nthe prompt."),(0,o.kt)("li",{parentName:"ul"},"Use ",(0,o.kt)("inlineCode",{parentName:"li"},"tail -f .metals/metals.log")," to watch the build import progress."),(0,o.kt)("li",{parentName:"ul"},"Behind the scenes, Metals uses ",(0,o.kt)("a",{parentName:"li",href:"https://scalacenter.github.io/bloop/"},"Bloop")," to\nimport sbt builds, but you don't need Bloop installed on your machine to run this step.")),(0,o.kt)("p",null,"Once the import step completes, compilation starts for your open ",(0,o.kt)("inlineCode",{parentName:"p"},"*.scala"),"\nfiles."),(0,o.kt)("p",null,"Once the sources have compiled successfully, you can navigate the codebase with\ngoto definition."),(0,o.kt)("h3",{id:"custom-sbt-launcher"},"Custom sbt launcher"),(0,o.kt)("p",null,"By default, Metals runs an embedded ",(0,o.kt)("inlineCode",{parentName:"p"},"sbt-launch.jar")," launcher that respects ",(0,o.kt)("inlineCode",{parentName:"p"},".sbtopts")," and ",(0,o.kt)("inlineCode",{parentName:"p"},".jvmopts"),".\nHowever, the environment variables ",(0,o.kt)("inlineCode",{parentName:"p"},"SBT_OPTS")," and ",(0,o.kt)("inlineCode",{parentName:"p"},"JAVA_OPTS")," are not respected."),(0,o.kt)("p",null,"Update the server property ",(0,o.kt)("inlineCode",{parentName:"p"},"-Dmetals.sbt-script=/path/to/sbt")," to use a custom\n",(0,o.kt)("inlineCode",{parentName:"p"},"sbt")," script instead of the default Metals launcher if you need further\ncustomizations like reading environment variables."),(0,o.kt)("h3",{id:"speeding-up-import"},"Speeding up import"),(0,o.kt)("p",null,'The "Import build" step can take a long time, especially the first time you\nrun it in a new build. The exact time depends on the complexity of the build and\nif library dependencies need to be downloaded. For example, this step can take\neverything from 10 seconds in small cached builds up to 10-15 minutes in large\nuncached builds.'),(0,o.kt)("p",null,"Consult the ",(0,o.kt)("a",{parentName:"p",href:"https://scalacenter.github.io/bloop/docs/build-tools/sbt#speeding-up-build-export"},"Bloop documentation"),"\nto learn how to speed up build import."),(0,o.kt)("h3",{id:"importing-changes"},"Importing changes"),(0,o.kt)("p",null,"When you change ",(0,o.kt)("inlineCode",{parentName:"p"},"build.sbt")," or sources under ",(0,o.kt)("inlineCode",{parentName:"p"},"project/"),", you will be prompted to\nre-import the build."),(0,o.kt)("p",null,(0,o.kt)("img",{parentName:"p",src:"https://i.imgur.com/UFK0p8i.png",alt:"Import sbt changes"})),(0,o.kt)("h2",{id:"lsp-tips"},"LSP Tips"),(0,o.kt)("h3",{id:"show-navigable-stack-trace"},"Show navigable stack trace"),(0,o.kt)("p",null,"You can annotate your stack trace with code lenses (which requires the\nfollowing bit of configuration mentioned earlier: ",(0,o.kt)("inlineCode",{parentName:"p"},"(lsp-mode . lsp-lens-mode)"),").\nThese allow you to run actions from your code."),(0,o.kt)("p",null,"One of these actions allow you to navigate your stack trace."),(0,o.kt)("p",null,"You can annotate any stack trace by marking a stack trace with your\nregion and using ",(0,o.kt)("inlineCode",{parentName:"p"},"M-x lsp-metals-analyze-stacktrace")," on it."),(0,o.kt)("p",null,'This will open a new Scala buffer that has code lenses annotations:\njust click on the small "open" annotation to navigate to the source\ncode relative to your stack trace.'),(0,o.kt)("p",null,"This will work as long as the buffer your are marking your stack trace\non exists within the project directory tracked by ",(0,o.kt)("inlineCode",{parentName:"p"},"lsp-mode"),", because\n",(0,o.kt)("inlineCode",{parentName:"p"},"lsp-metals-analyze-stacktrace")," needs the ",(0,o.kt)("inlineCode",{parentName:"p"},"lsp")," workspace to find the\nlocation of your errors."),(0,o.kt)("p",null,"Note that if you try to do that from ",(0,o.kt)("inlineCode",{parentName:"p"},"sbt-mode"),", you may get an error\nunless you patch ",(0,o.kt)("inlineCode",{parentName:"p"},"lsp-find-workspace")," with the following:"),(0,o.kt)("pre",null,(0,o.kt)("code",{parentName:"pre",className:"language-el"},'(defun lsp-find-workspace (server-id &optional file-name)\n    "Find workspace for SERVER-ID for FILE-NAME."\n    (-when-let* ((session (lsp-session))\n                 (folder->servers (lsp-session-folder->servers session))\n                 (workspaces (if file-name\n                                 (let* ((folder (lsp-find-session-folder session file-name))\n                                        (folder-last-char (substring folder (- (length folder) 1) (length folder)))\n                                        (key (if (string= folder-last-char "/") (substring folder 0 (- (length folder) 1)) folder)))\n                                   (gethash key folder->servers))\n                               (lsp--session-workspaces session))))\n\n      (--first (eq (lsp--client-server-id (lsp--workspace-client it)) server-id) workspaces)))\n')),(0,o.kt)("p",null,"The above shall become unnecessary once ",(0,o.kt)("a",{parentName:"p",href:"https://github.com/emacs-lsp/lsp-mode/issues/2610"},"this issue")," is solved."),(0,o.kt)("h3",{id:"reference"},"Reference"),(0,o.kt)("ul",null,(0,o.kt)("li",{parentName:"ul"},(0,o.kt)("a",{parentName:"li",href:"https://www.youtube.com/watch?v=x7ey0ifcqAg&feature=youtu.be"},"Yurii Ostapchuk at #ScalaUA\u200b - How I learned to stop worrying and love LSP (and Emacs :))"))),(0,o.kt)("h2",{id:"manually-trigger-build-import"},"Manually trigger build import"),(0,o.kt)("p",null,"To manually trigger a build import, run ",(0,o.kt)("inlineCode",{parentName:"p"},"M-x lsp-metals-build-import"),"."),(0,o.kt)("p",null,(0,o.kt)("img",{parentName:"p",src:"https://i.imgur.com/SvGXJDK.png",alt:"Import build command"})),(0,o.kt)("h2",{id:"run-doctor"},"Run doctor"),(0,o.kt)("p",null,"Run ",(0,o.kt)("inlineCode",{parentName:"p"},"M-x lsp-metals-doctor-run")," to troubleshoot potential configuration problems\nin your build."),(0,o.kt)("p",null,(0,o.kt)("img",{parentName:"p",src:"https://i.imgur.com/yelm0jd.png",alt:"Run doctor command"})),(0,o.kt)("h3",{id:"eglot"},"eglot"),(0,o.kt)("p",null,"There is an alternative LSP client called\n",(0,o.kt)("a",{parentName:"p",href:"https://github.com/joaotavora/eglot"},"eglot")," that might be worth trying out if\nyou want to use an alternative to lsp-mode."),(0,o.kt)("p",null,"To configure Eglot with Metals:"),(0,o.kt)("pre",null,(0,o.kt)("code",{parentName:"pre",className:"language-el"},"(require 'package)\n\n;; Add melpa-stable to your packages repositories\n(add-to-list 'package-archives '(\"melpa-stable\" . \"https://stable.melpa.org/packages/\") t)\n\n(package-initialize)\n\n;; Install use-package if not already installed\n(unless (package-installed-p 'use-package)\n  (package-refresh-contents)\n  (package-install 'use-package))\n\n(require 'use-package)\n\n;; Enable defer and ensure by default for use-package\n(setq use-package-always-defer t\n      use-package-always-ensure t)\n\n;; Enable scala-mode and sbt-mode\n(use-package scala-mode\n  :interpreter\n    (\"scala\" . scala-mode))\n\n;; Enable sbt mode for executing sbt commands\n(use-package sbt-mode\n  :commands sbt-start sbt-command\n  :config\n  ;; WORKAROUND: https://github.com/ensime/emacs-sbt-mode/issues/31\n  ;; allows using SPACE when in the minibuffer\n  (substitute-key-definition\n   'minibuffer-complete-word\n   'self-insert-command\n   minibuffer-local-completion-map)\n   ;; sbt-supershell kills sbt-mode:  https://github.com/hvesalai/emacs-sbt-mode/issues/152\n   (setq sbt:program-options '(\"-Dsbt.supershell=false\"))\n)\n\n(use-package eglot\n  :pin melpa-stable\n  ;; (optional) Automatically start metals for Scala files.\n  :hook (scala-mode . eglot-ensure))\n")),(0,o.kt)("p",null,"If you start Emacs now then it will fail since the ",(0,o.kt)("inlineCode",{parentName:"p"},"metals-emacs")," binary does\nnot exist yet."),(0,o.kt)("p",null,"Next, build a ",(0,o.kt)("inlineCode",{parentName:"p"},"metals-emacs")," binary for the latest Metals release using the\n",(0,o.kt)("a",{parentName:"p",href:"https://github.com/coursier/coursier"},"Coursier")," command-line interface."),(0,o.kt)("table",null,(0,o.kt)("thead",null,(0,o.kt)("tr",null,(0,o.kt)("th",null,"Version"),(0,o.kt)("th",null,"Published"),(0,o.kt)("th",null,"Resolver"))),(0,o.kt)("tbody",null,(0,o.kt)("tr",null,(0,o.kt)("td",null,"0.11.7"),(0,o.kt)("td",null,"04 Jul 2022 15:27"),(0,o.kt)("td",null,(0,o.kt)("code",null,"-r sonatype:releases"))),(0,o.kt)("tr",null,(0,o.kt)("td",null,"0.11.7+13-b0fcd238-SNAPSHOT"),(0,o.kt)("td",null,"07 Jul 2022 12:23"),(0,o.kt)("td",null,(0,o.kt)("code",null,"-r sonatype:snapshots"))))),(0,o.kt)("pre",null,(0,o.kt)("code",{parentName:"pre",className:"language-sh"},"# Make sure to use coursier v1.1.0-M9 or newer.\ncurl -L -o coursier https://git.io/coursier-cli\nchmod +x coursier\n./coursier bootstrap \\\n  --java-opt -Xss4m \\\n  --java-opt -Xms100m \\\n  --java-opt -Dmetals.client=emacs \\\n  org.scalameta:metals_2.13:0.11.7 \\\n  -r bintray:scalacenter/releases \\\n  -r sonatype:snapshots \\\n  -o /usr/local/bin/metals-emacs -f\n")),(0,o.kt)("p",null,"Make sure the generated ",(0,o.kt)("inlineCode",{parentName:"p"},"metals-emacs")," binary is available on your ",(0,o.kt)("inlineCode",{parentName:"p"},"$PATH"),"."),(0,o.kt)("p",null,"You can check version of your binary by executing ",(0,o.kt)("inlineCode",{parentName:"p"},"metals-emacs -version"),"."),(0,o.kt)("p",null,"Configure the system properties ",(0,o.kt)("inlineCode",{parentName:"p"},"-Dhttps.proxyHost=\u2026 -Dhttps.proxyPort=\u2026"),"\nif you are behind an HTTP proxy."),(0,o.kt)("p",null,"The ",(0,o.kt)("inlineCode",{parentName:"p"},"-Dmetals.client=emacs")," flag is important since it configures Metals for\nusage with Emacs."),(0,o.kt)("h2",{id:"files-and-directories-to-include-in-your-gitignore"},"Files and Directories to include in your Gitignore"),(0,o.kt)("p",null,"The Metals server places logs and other files in the ",(0,o.kt)("inlineCode",{parentName:"p"},".metals")," directory. The\nBloop compile server places logs and compilation artifacts in the ",(0,o.kt)("inlineCode",{parentName:"p"},".bloop"),"\ndirectory. The Bloop plugin that generates Bloop configuration is added in the\n",(0,o.kt)("inlineCode",{parentName:"p"},"metals.sbt")," file, which is added at ",(0,o.kt)("inlineCode",{parentName:"p"},"project/metals.sbt")," as well as further\n",(0,o.kt)("inlineCode",{parentName:"p"},"project")," directories depending on how deep ",(0,o.kt)("inlineCode",{parentName:"p"},"*.sbt")," files need to be supported.\nTo support each ",(0,o.kt)("inlineCode",{parentName:"p"},"*.sbt")," file Metals needs to create an additional file at\n",(0,o.kt)("inlineCode",{parentName:"p"},"./project/project/metals.sbt")," relative to the sbt file.\nWorking with Ammonite scripts will place compiled scripts into the ",(0,o.kt)("inlineCode",{parentName:"p"},".ammonite")," directory.\nIt's recommended to exclude these directories and files\nfrom version control systems like git."),(0,o.kt)("pre",null,(0,o.kt)("code",{parentName:"pre",className:"language-sh"},"# ~/.gitignore\n.metals/\n.bloop/\n.ammonite/\nmetals.sbt\n")),(0,o.kt)("h2",{id:"worksheets"},"Worksheets"),(0,o.kt)("p",null,"Worksheets are a great way to explore an api, try out an idea, or code\nup an example and quickly see the evaluated expression or result. Behind\nthe scenes worksheets are powered by the great work done in\n",(0,o.kt)("a",{parentName:"p",href:"https://scalameta.org/mdoc/"},"mdoc"),"."),(0,o.kt)("h3",{id:"getting-started-with-worksheets"},"Getting started with Worksheets"),(0,o.kt)("p",null,"To get started with a worksheet you can either use the ",(0,o.kt)("inlineCode",{parentName:"p"},"metals.new-scala-file"),"\ncommand and select ",(0,o.kt)("em",{parentName:"p"},"Worksheet")," or create a file called ",(0,o.kt)("inlineCode",{parentName:"p"},"*.worksheet.sc"),".\nThis format is important since this is what tells Metals that it's meant to be\ntreated as a worksheet and not just a Scala script. Where you create the\nscript also matters. If you'd like to use classes and values from your\nproject, you need to make sure the worksheet is created inside of your ",(0,o.kt)("inlineCode",{parentName:"p"},"src"),"\ndirectory. You can still create a worksheet in other places, but you will\nonly have access to the standard library and your dependencies."),(0,o.kt)("h3",{id:"evaluations"},"Evaluations"),(0,o.kt)("p",null,"After saving you'll see the result of the expression as a comment as the end of the line.\nYou may not see the full result for example if it's too long, so you are also\nable to hover on the comment to expand."),(0,o.kt)("p",null,"Keep in mind that you don't need to wrap your code in an ",(0,o.kt)("inlineCode",{parentName:"p"},"object"),". In worksheets\neverything can be evaluated at the top level."),(0,o.kt)("h3",{id:"using-dependencies-in-worksheets"},"Using dependencies in worksheets"),(0,o.kt)("p",null,"You are able to include an external dependency in your worksheet by including\nit in one of the following two ways."),(0,o.kt)("pre",null,(0,o.kt)("code",{parentName:"pre",className:"language-scala"},"// $dep.`organisation`::artifact:version` style\nimport $dep.`com.lihaoyi::scalatags:0.7.0`\n\n// $ivy.`organisation::artifact:version` style\nimport $ivy.`com.lihaoyi::scalatags:0.7.0`\n")),(0,o.kt)("p",null,(0,o.kt)("inlineCode",{parentName:"p"},"::")," is the same as ",(0,o.kt)("inlineCode",{parentName:"p"},"%%")," in sbt, which will append the current Scala binary version\nto the artifact name."),(0,o.kt)("p",null,"You can also import ",(0,o.kt)("inlineCode",{parentName:"p"},"scalac")," options in a special ",(0,o.kt)("inlineCode",{parentName:"p"},"$scalac")," import like below:"),(0,o.kt)("pre",null,(0,o.kt)("code",{parentName:"pre",className:"language-scala"},"import $scalac.`-Ywarn-unused`\n")),(0,o.kt)("h2",{id:"running-scalafix-rules"},"Running scalafix rules"),(0,o.kt)("p",null,"Scalafix allows users to specify some refactoring and linting rules that can be applied to your\ncodebase. Please checkout the ",(0,o.kt)("a",{parentName:"p",href:"https://scalacenter.github.io/scalafix"},"scalafix website")," for more information."),(0,o.kt)("p",null,"Since Metals v0.11.7 it's now possible to run scalafix rules using a special\ncommand ",(0,o.kt)("inlineCode",{parentName:"p"},"metals.scalafix-run"),".\nThis should run all the rules defined in your ",(0,o.kt)("inlineCode",{parentName:"p"},".scalafix.conf")," file. All built-in rules\nand the ",(0,o.kt)("a",{parentName:"p",href:"https://scalacenter.github.io/scalafix/docs/rules/community-rules.html#hygiene-rules"},"community hygiene ones")," can\nbe run without any additional settings. However, for all the other rules users need to\nadd an additional dependency in the ",(0,o.kt)("inlineCode",{parentName:"p"},"metals.scalafixRulesDependencies")," user setting.\nThose rules need to be in form of strings such as ",(0,o.kt)("inlineCode",{parentName:"p"},"com.github.liancheng::organize-imports:0.6.0"),", which\nfollows the same convention as ",(0,o.kt)("a",{parentName:"p",href:"https://get-coursier.io/"},"coursier dependencies"),"."),(0,o.kt)("p",null,"A sample scalafix configuration can be seen below:"),(0,o.kt)("pre",null,(0,o.kt)("code",{parentName:"pre",className:"language-hocon"},'rules = [\n  OrganizeImports,\n  ExplicitResultTypes,\n  RemoveUnused\n]\n\nRemoveUnused.imports = false\n\nOrganizeImports.groupedImports = Explode\nOrganizeImports.expandRelative = true\nOrganizeImports.removeUnused = true\nOrganizeImports.groups = [\n  "re:javax?\\."\n  "scala."\n  "scala.meta."\n  "*"\n]\n\n')))}d.isMDXComponent=!0}}]);