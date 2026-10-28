/**
 * Attribution required by the Last.fm and Discogs API terms (SPEC.md section 17).
 */
export function Footer() {
  return (
    <footer className="footer">
      <a className="audioscrobbler" href="https://www.last.fm" target="_blank" rel="noreferrer">
        Powered by AudioScrobbler
      </a>
      <span>
        Release data provided by{' '}
        <a href="https://www.discogs.com" target="_blank" rel="noreferrer">
          Discogs
        </a>
        . Track lengths partly from{' '}
        <a href="https://musicbrainz.org" target="_blank" rel="noreferrer">
          MusicBrainz
        </a>
        .
      </span>
      <span className="muted">ScrobbleCue is not affiliated with Last.fm or Discogs. Open source, MIT licensed.</span>
    </footer>
  )
}
