/*! For license information please see b79c2638.7806c78a.js.LICENSE.txt */
"use strict";(self.webpackChunk=self.webpackChunk||[]).push([[8283],{8799:(e,t,n)=>{n.r(t),n.d(t,{assets:()=>s,contentTitle:()=>i,default:()=>u,frontMatter:()=>a,metadata:()=>r,toc:()=>d});var l=n(4848),o=n(8453);const a={id:"gradle",title:"Gradle"},i=void 0,r={id:"build-tools/gradle",title:"Gradle",description:"Gradle is a build tool that can be used easily with a large number of",source:"@site/target/docs/build-tools/gradle.md",sourceDirName:"build-tools",slug:"/build-tools/gradle",permalink:"/metals/docs/build-tools/gradle",draft:!1,unlisted:!1,editUrl:"https://github.com/scalameta/metals/edit/main/docs/build-tools/gradle.md",tags:[],version:"current",frontMatter:{id:"gradle",title:"Gradle"},sidebar:"docs",previous:{title:"Bloop",permalink:"/metals/docs/build-tools/bloop"},next:{title:"Maven",permalink:"/metals/docs/build-tools/maven"}},s={},d=[{value:"Automatic installation",id:"automatic-installation",level:2},{value:"Manual installation",id:"manual-installation",level:2}];function c(e){const t={a:"a",code:"code",h2:"h2",p:"p",pre:"pre",...(0,o.R)(),...e.components};return(0,l.jsxs)(l.Fragment,{children:[(0,l.jsxs)(t.p,{children:["Gradle is a build tool that can be used easily with a large number of\nprogramming languages including Scala. With it you can easily define your builds\nfor Groovy or Kotlin, which enables for a high degree of customization. You can\nlook up all the possible features on the ",(0,l.jsx)(t.a,{href:"https://gradle.org/",children:"Gradle website"}),"."]}),"\n",(0,l.jsx)(t.h2,{id:"automatic-installation",children:"Automatic installation"}),"\n",(0,l.jsx)(t.p,{children:'The first time you open Metals in a new Gradle workspace you will be\nprompted to import the build. Select "Import Build" to start the\nautomatic installation. This will create all the needed Bloop config\nfiles. You should then be able to edit and compile your code utilizing\nall of the features.'}),"\n",(0,l.jsx)(t.h2,{id:"manual-installation",children:"Manual installation"}),"\n",(0,l.jsx)(t.p,{children:"In a highly customized workspaces it might not be possible to use automatic\nimport. In such cases it's quite simple to add the capability to generate the\nneeded Bloop config."}),"\n",(0,l.jsx)(t.p,{children:"First we need to add the Bloop plugin dependency to the project. It should be\nincluded in the buildscript section:"}),"\n",(0,l.jsx)(t.pre,{children:(0,l.jsx)(t.code,{className:"language-groovy",children:"buildscript {\n    repositories {\n        mavenCentral()\n    }\n    dependencies {\n        classpath 'ch.epfl.scala:gradle-bloop_2.12:2.0.0'\n    }\n}\n"})}),"\n",(0,l.jsxs)(t.p,{children:["Secondly, we need to enable the plugin for all the projects we want to include.\nIt's easiest to define it for ",(0,l.jsx)(t.code,{children:"allprojects"}),":"]}),"\n",(0,l.jsx)(t.pre,{children:(0,l.jsx)(t.code,{className:"language-groovy",children:"allprojects {\n   apply plugin: bloop.integrations.gradle.BloopPlugin\n}\n"})}),"\n",(0,l.jsxs)(t.p,{children:["Now we can run ",(0,l.jsx)(t.code,{children:"gradle bloopInstall"}),", which will create all of the Bloop\nconfiguration files."]}),"\n",(0,l.jsx)(t.p,{children:"This will enable us to work with Metals and all features should work."})]})}function u(e={}){const{wrapper:t}={...(0,o.R)(),...e.components};return t?(0,l.jsx)(t,{...e,children:(0,l.jsx)(c,{...e})}):c(e)}},1020:(e,t,n)=>{var l=n(6540),o=Symbol.for("react.element"),a=Symbol.for("react.fragment"),i=Object.prototype.hasOwnProperty,r=l.__SECRET_INTERNALS_DO_NOT_USE_OR_YOU_WILL_BE_FIRED.ReactCurrentOwner,s={key:!0,ref:!0,__self:!0,__source:!0};function d(e,t,n){var l,a={},d=null,c=null;for(l in void 0!==n&&(d=""+n),void 0!==t.key&&(d=""+t.key),void 0!==t.ref&&(c=t.ref),t)i.call(t,l)&&!s.hasOwnProperty(l)&&(a[l]=t[l]);if(e&&e.defaultProps)for(l in t=e.defaultProps)void 0===a[l]&&(a[l]=t[l]);return{$$typeof:o,type:e,key:d,ref:c,props:a,_owner:r.current}}t.Fragment=a,t.jsx=d,t.jsxs=d},4848:(e,t,n)=>{e.exports=n(1020)},8453:(e,t,n)=>{n.d(t,{R:()=>i,x:()=>r});var l=n(6540);const o={},a=l.createContext(o);function i(e){const t=l.useContext(a);return l.useMemo((function(){return"function"==typeof e?e(t):{...t,...e}}),[t,e])}function r(e){let t;return t=e.disableParentContext?"function"==typeof e.components?e.components(o):e.components||o:i(e.components),l.createElement(a.Provider,{value:t},e.children)}}}]);