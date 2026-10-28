package com.scrobblecue.tracklist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.scrobblecue.tracklist.PreparedTracklist.DurationSource;
import com.scrobblecue.tracklist.PreparedTracklist.Group;
import com.scrobblecue.tracklist.PreparedTracklist.GroupKind;
import com.scrobblecue.tracklist.PreparedTracklist.Track;

import tools.jackson.databind.json.JsonMapper;

class TracklistPreparerTests {

	private final TracklistPreparer preparer = new TracklistPreparer();

	@Test
	void doubleLpIsGroupedIntoFourSides() {
		var prepared = prepare("release-1.json");

		assertThat(prepared.sideScrobblable()).isTrue();
		assertThat(prepared.albumArtist()).isEqualTo("The Persuader");
		assertThat(prepared.album()).isEqualTo("Stockholm");
		assertThat(prepared.groups()).extracting(Group::kind, Group::label, g -> g.tracks().size())
			.containsExactly(tuple(GroupKind.SIDE, "A", 1), tuple(GroupKind.SIDE, "B", 2),
					tuple(GroupKind.SIDE, "C", 2), tuple(GroupKind.SIDE, "D", 1));
		assertThat(prepared.groups().get(0).tracks().get(0)).isEqualTo(
				new Track("A", "The Persuader", "Östermalm", 285, DurationSource.DISCOGS, true));
		assertThat(prepared.unassigned()).isEmpty();
	}

	@Test
	void sevenInchSingleHasSidesAAndB() {
		var prepared = prepare("release-249504.json");

		assertThat(prepared.groups()).extracting(Group::label).containsExactly("A", "B");
		assertThat(prepared.groups().get(0).tracks()).extracting(Track::artist, Track::title, Track::durationSec)
			.containsExactly(tuple("Rick Astley", "Never Gonna Give You Up", 212));
	}

	@Test
	void headingsAreDroppedAndIndexTracksExpanded() {
		var sideA = side(prepare("edge-cases-vinyl.json"), "A");

		assertThat(sideA).extracting(Track::position).containsExactly("A1", "A2a", "A2b", "A3");
	}

	@Test
	void artistsAreJoinedAndDisambiguationSuffixesRemoved() {
		var prepared = prepare("edge-cases-vinyl.json");

		assertThat(prepared.albumArtist()).isEqualTo("Various Artists");
		assertThat(side(prepared, "A")).extracting(Track::artist)
			.containsExactly("Prince & The Revolution", "Ensemble", "Ensemble", "Somebody Feat. Guest");
		assertThat(side(prepared, "B")).extracting(Track::artist).startsWith("Artist One, Artist Two");
	}

	@Test
	void whitespaceInTitlesIsCollapsed() {
		var prepared = prepare("edge-cases-vinyl.json");

		assertThat(prepared.album()).isEqualTo("Edge Cases Compilation");
		assertThat(side(prepared, "B")).extracting(Track::title).contains("Very Long Jam");
	}

	@Test
	void shortTracksCountForTimingButAreNotScrobbled() {
		var interlude = track(prepare("edge-cases-vinyl.json"), "A3");

		assertThat(interlude.durationSec()).isEqualTo(20);
		assertThat(interlude.scrobblable()).isFalse();
	}

	@Test
	void durationsFallBackFromDiscogsToLookupToDefault() {
		var withoutLookup = prepare("edge-cases-vinyl.json");
		var withLookup = prepare("edge-cases-vinyl.json", Map.of("B1", 199, "A1", 999), List.of());

		assertThat(track(withoutLookup, "B1")).extracting(Track::durationSec, Track::durationSource)
			.containsExactly(210, DurationSource.DEFAULT);
		assertThat(track(withLookup, "B1")).extracting(Track::durationSec, Track::durationSource)
			.containsExactly(199, DurationSource.LOOKUP);
		// Discogs wins over a lookup
		assertThat(track(withLookup, "A1").durationSec()).isEqualTo(245);
		assertThat(track(withoutLookup, "B2").durationSec()).isEqualTo(3735);
	}

	@Test
	void correctionsWinAndCanSkipTracks() {
		var prepared = prepare("edge-cases-vinyl.json", Map.of(), List.of(
				new TrackCorrection("A1", "Purple Song (Album Version)", null, 250, false),
				new TrackCorrection("B2", null, null, null, true)));

		assertThat(track(prepared, "A1")).isEqualTo(new Track("A1", "Prince & The Revolution",
				"Purple Song (Album Version)", 250, DurationSource.CORRECTION, true));
		assertThat(track(prepared, "B2").scrobblable()).isFalse();
	}

	@Test
	void positionsWithoutSideOrNumberAreUnassigned() {
		var prepared = prepare("edge-cases-vinyl.json");

		assertThat(prepared.unassigned()).extracting(Track::position, Track::title)
			.containsExactly(tuple("Video", "Bonus Clip"));
	}

	@Test
	void cdIsGroupedByDiscAndNotSideScrobblable() {
		var prepared = prepare("cd-two-discs.json");

		assertThat(prepared.sideScrobblable()).isFalse();
		assertThat(prepared.groups()).extracting(Group::kind, Group::label, g -> g.tracks().size())
			.containsExactly(tuple(GroupKind.DISC, "1", 2), tuple(GroupKind.DISC, "2", 1));
	}

	@Test
	void doubleASideComesAfterSingleLetterSides() {
		var release = new DiscogsRelease(1, "Single", List.of(new DiscogsRelease.Artist("Band", "")),
				List.of(new DiscogsRelease.Format("Vinyl", "1", List.of("7\""))),
				List.of(track("AA", "Flip"), track("A", "Hit")));

		assertThat(preparer.prepare(release, null, null).groups()).extracting(Group::label).containsExactly("A", "AA");
	}

	@Test
	void cassetteWithLetteredSidesIsOneGroupInV1() {
		var release = new DiscogsRelease(1, "Tape", List.of(new DiscogsRelease.Artist("Band", "")),
				List.of(new DiscogsRelease.Format("Cassette", "1", List.of())),
				List.of(track("A1", "One"), track("B1", "Two")));

		var prepared = preparer.prepare(release, null, null);

		assertThat(prepared.sideScrobblable()).isFalse();
		assertThat(prepared.groups()).extracting(Group::kind, g -> g.tracks().size())
			.containsExactly(tuple(GroupKind.ALL, 2));
	}

	private static DiscogsRelease.Track track(String position, String title) {
		return new DiscogsRelease.Track(position, "track", title, "3:00", null, null);
	}

	private PreparedTracklist prepare(String fixture) {
		return prepare(fixture, Map.of(), List.of());
	}

	private PreparedTracklist prepare(String fixture, Map<String, Integer> lookups, List<TrackCorrection> corrections) {
		try (InputStream in = getClass().getResourceAsStream("/discogs/" + fixture)) {
			var release = JsonMapper.builder().build().readValue(in, DiscogsRelease.class);
			return preparer.prepare(release, lookups, corrections);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static List<Track> side(PreparedTracklist prepared, String label) {
		return prepared.groups().stream().filter(g -> g.label().equals(label)).findFirst().orElseThrow().tracks();
	}

	private static Track track(PreparedTracklist prepared, String position) {
		return prepared.groups()
			.stream()
			.flatMap(g -> g.tracks().stream())
			.filter(t -> t.position().equals(position))
			.findFirst()
			.orElseThrow();
	}

}
