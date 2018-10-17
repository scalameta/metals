const React = require("react");

const siteConfig = require(process.cwd() + "/siteConfig.js");

class Footer extends React.Component {
  render() {
    const currentYear = new Date().getFullYear();
    const {
      copyright,
      colors: { secondaryColor }
    } = this.props.config;
    return (
      <footer
        className="nav-footer"
        id="footer"
        style={{ backgroundColor: secondaryColor }}
      >
        <section className="sitemap">
          {this.props.config.footerIcon && (
            <a href={this.props.config.baseUrl} className="nav-home">
              <img
                src={`${this.props.config.baseUrl}${
                  this.props.config.footerIcon
                }`}
                alt={this.props.config.title}
                width="66"
                height="58"
              />
            </a>
          )}
          <div>
            <h5>Docs</h5>
            <a
              href={`
                ${this.props.config.baseUrl}docs/overview.html`}
            >
              Project Goals
            </a>
            <a
              href={`
                ${
                  this.props.config.baseUrl
                }docs/getting-started-contributors.html`}
            >
              Contributing
            </a>
          </div>
          <div>
            <h5>Community</h5>
            <a href="https://gitter.im/scalameta/metals" target="_blank">
              Chat on Gitter
            </a>
          </div>
          <div>
            <h5>More</h5>
            <a href={siteConfig.repoUrl} target="_blank">
              GitHub
            </a>
          </div>
        </section>
        <section className="copyright">{copyright}</section>
      </footer>
    );
  }
}

module.exports = Footer;
