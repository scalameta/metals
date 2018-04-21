const React = require('react');

class Footer extends React.Component {
  render() {
    const currentYear = new Date().getFullYear();
    const { copyright, colors: { secondaryColor } } = this.props.config;
    return (
      <footer className="nav-footer" id="footer" style={{ backgroundColor: secondaryColor }}>
        <section className="copyright">{copyright}</section>
      </footer>
    );
  }
}

module.exports = Footer;
