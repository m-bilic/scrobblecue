package com.scrobblecue.app.ui.footer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp

/**
 * Credits required by the API terms (SPEC.md section 17): "Powered by AudioScrobbler" with a link to
 * Last.fm, and Discogs as the data source.
 */
@Composable
fun Attribution(modifier: Modifier = Modifier) {
    val linkStyle = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary))
    Column(modifier.padding(vertical = 8.dp)) {
        Text(
            buildAnnotatedString {
                withLink(LinkAnnotation.Url("https://www.last.fm", linkStyle)) { append("Powered by AudioScrobbler") }
                append(" · Release data from ")
                withLink(LinkAnnotation.Url("https://www.discogs.com", linkStyle)) { append("Discogs") }
                append(", track lengths from ")
                withLink(LinkAnnotation.Url("https://musicbrainz.org", linkStyle)) { append("MusicBrainz") }
                append(".")
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "ScrobbleCue is not affiliated with Last.fm, Discogs or MusicBrainz.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
