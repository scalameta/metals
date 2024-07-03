import React from "react";
import Link from "@docusaurus/Link";
import useDocusaurusContext from "@docusaurus/useDocusaurusContext";
import Layout from "@theme/Layout";

const Dropdown = () => {
  const gitpod = (template, organization) =>
    `https://gitpod.io/#template=${template},organization=${organization}/https://github.com/scalameta/gitpod-g8`;

  const links = [
    { organization: "scala", template: "hello-world", label: "Hello World!" },
    { organization: "scala", template: "scala3", label: "Scala 3" },
    {
      organization: "scala",
      template: "scalatest-example",
      label: "Scalatest",
    },
    {
      organization: "akka",
      template: "akka-scala-seed",
      label: "Akka",
    },
    {
      organization: "zio",
      template: "zio-project-seed",
      label: "ZIO",
    },
    {
      organization: "playframework",
      template: "play-scala-seed",
      label: "Play Framework",
    },
    {
      organization: "scala-native",
      template: "scala-native",
      label: "Scala Native",
    },
  ].map(({ organization, template, label }) => (
    <li key={template}>
      <a
        className="dropdown__link"
        target="_blank"
        rel="noopener noreferrer"
        href={gitpod(template, organization)}
      >
        {label}
      </a>
    </li>
  ));
  return (
    <div className="dropdown dropdown--hoverable">
      <button className="button button--lg button--outline button--primary margin--sm">
        Try Online with Gitpod
      </button>
      <ul className="dropdown__menu">{links}</ul>
    </div>
  );
};

const Supported = (props) => {
  const companies = [
    {
      name: "Scala Center",
      image: "img/scala-center-swirl.png",
      link: "https://scala.epfl.ch",
    },
    {
      name: "VirtusLab",
      image: "img/VirtusLab_logo_narrow.png",
      link: "https://virtuslab.com",
    },
  ];
  return (
    <div className="text--center ">
      <div className="padding--md">
        <h2 className="hero__subtitle padding-vert--md">Supported by: </h2>

        {companies.map((company) => (
          <a href={company.link}>
            <img src={company.image} title={company.name}></img>
          </a>
        ))}
      </div>
    </div>
  );
};

const Features = (props) => {
  const features = [
    {
      title: "Simple installation",
      content: "Open a directory, import your build and start coding.",
      image: "https://i.imgur.com/L5CurFG.png",
      imageAlign: "left",
    },
    {
      title: "Accurate diagnostics",
      content:
        "Compile on file save and see errors from the build tool directly inside the editor. No more switching focus to the console.",
      image: "https://i.imgur.com/JYLQGrc.gif",
      imageAlign: "right",
    },
    {
      title: "Rich build tool support",
      content:
        `The build tools sbt, Gradle, Maven and Mill are supported thanks to Bloop` +
        `Hot incremental compilation in the Bloop build server ensures compile errors appear as quickly as possible.`,
      image:
        "https://user-images.githubusercontent.com/1408093/68486864-dd9f2b00-01f6-11ea-9291-d3a7ce6ef225.png",
      imageAlign: "left",
    },
    {
      title: "Goto definition",
      content:
        "Jump to symbol definitions in your project sources and Scala/Java library dependencies.",
      image: "https://i.imgur.com/bCIhFof.gif",
      imageAlign: "right",
    },
    {
      title: "Completions",
      content:
        "Explore new library APIs, implement interfaces, generate exhaustive matches and more.",
      image:
        "https://user-images.githubusercontent.com/1408093/56036958-725bac00-5d2e-11e9-9cf7-46249125494a.gif",
      imageAlign: "left",
    },
    {
      title: "Hover (aka. type at point)",
      content: "See the expression type and symbol signature under the cursor.",
      image: "https://i.imgur.com/2MfQvsM.gif",
      imageAlign: "right",
    },
    {
      title: "Signature help (aka. parameter hints)",
      content:
        "View a method signature and method overloads as you fill in the arguments.",
      image: "https://i.imgur.com/DAWIrHu.gif",
      imageAlign: "left",
    },
    {
      title: "Find symbol references",
      content: "Find all usages of a symbol in the workspace.",
      image:
        "https://user-images.githubusercontent.com/1408093/51089190-75fc8880-1769-11e9-819c-95262205e95c.png",
      imageAlign: "right",
    },
    {
      title: "Fuzzy symbol search",
      content: "Search for symbols in the workspace or library dependencies.",
      image: "https://i.imgur.com/w5yrK1w.gif",
      imageAlign: "left",
    },
  ];
  return (
    <div>
      {features.map((feature) => (
        <div className="hero text--center" key={feature.title}>
          <div
            className={`container ${
              feature.imageAlign === "right" ? "flex-row" : "flex-row-reverse"
            }`}
          >
            <div className="padding--md">
              <h2 className="hero__subtitle">{feature.title}</h2>
              <p>{feature.content}</p>
            </div>
            <div className="padding-vert--md">
              <img src={feature.image} />
            </div>
          </div>
        </div>
      ))}
    </div>
  );
};

const Index = (props) => {
  const { siteConfig } = useDocusaurusContext();
  return (
    <Layout title={siteConfig.title} description={siteConfig.tagline}>
      <div className="hero text--center">
        <div className="container ">
          <div className="padding-vert--md">
            <h1 className="hero__title">{siteConfig.title}</h1>
            <p className="hero__subtitle">{siteConfig.tagline}</p>
          </div>
          <div>
            <Link
              to="/metals/docs"
              className="button button--lg button--outline button--primary margin--sm"
            >
              Get started
            </Link>
            <Dropdown />
            <Features />
            <Supported />
          </div>
        </div>
      </div>
    </Layout>
  );
};

export default Index;
