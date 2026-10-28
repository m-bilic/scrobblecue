package com.scrobblecue.tracklist;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.scrobblecue.tracklist.PreparedTracklist.DurationSource;
import com.scrobblecue.tracklist.PreparedTracklist.Group;
import com.scrobblecue.tracklist.PreparedTracklist.GroupKind;
import com.scrobblecue.tracklist.PreparedTracklist.Track;

/**
 * Turns a raw Discogs release into tracks grouped by side, following SPEC.md section 9. Stateless:
 * everything it needs arrives with the call, and nothing is kept.
 */
@Component
public class TracklistPreparer {

	/** Last.fm does not count tracks of 30 seconds or less. */
	static final int MIN_SCROBBLE_SECONDS = 31;

	private static final String HEADING = "heading";

	private static final String INDEX = "index";

	/** Side labels in play order: A, B, …, Z, then AA, BB, … (7" double A-sides). */
	private static final Comparator<String> SIDE_ORDER = Comparator.comparingInt(String::length)
		.thenComparing(Comparator.naturalOrder());

	/**
	 * @param release the release JSON as Discogs returned it
	 * @param lookupDurations lengths in seconds the client found elsewhere (MusicBrainz, Last.fm), by position
	 * @param corrections the user's corrections for this release
	 */
	public PreparedTracklist prepare(DiscogsRelease release, Map<String, Integer> lookupDurations,
			List<TrackCorrection> corrections) {
		String albumArtist = NameCleaner.joinArtists(release.artists());
		Map<String, TrackCorrection> correctionsByPosition = corrections == null ? Map.of()
				: corrections.stream()
					.collect(Collectors.toMap(c -> c.position().trim(), Function.identity(), (a, b) -> b));
		Map<String, Integer> lookups = lookupDurations == null ? Map.of() : lookupDurations;

		List<Parsed> tracks = new ArrayList<>();
		for (DiscogsRelease.Track raw : flatten(release.tracklist())) {
			String position = raw.position() == null ? "" : raw.position().trim();
			tracks.add(new Parsed(Position.parse(position),
					toTrack(raw, position, albumArtist, lookups, correctionsByPosition.get(position))));
		}

		boolean vinyl = release.formats() != null
				&& release.formats().stream().anyMatch(f -> "Vinyl".equalsIgnoreCase(f.name()));
		boolean hasSides = tracks.stream().anyMatch(t -> t.position().kind() == Position.Kind.SIDE);
		boolean sideScrobblable = vinyl && hasSides;

		List<Track> unassigned = new ArrayList<>();
		List<Group> groups = sideScrobblable ? groupBySide(tracks, unassigned) : groupOther(tracks, unassigned);
		return new PreparedTracklist(release.id(), NameCleaner.clean(release.title()), albumArtist, sideScrobblable,
				groups, unassigned);
	}

	/** Drops headings and replaces index tracks by their sub-tracks. */
	private static List<DiscogsRelease.Track> flatten(List<DiscogsRelease.Track> tracklist) {
		List<DiscogsRelease.Track> flat = new ArrayList<>();
		if (tracklist == null) {
			return flat;
		}
		for (DiscogsRelease.Track track : tracklist) {
			if (HEADING.equals(track.type())) {
				continue;
			}
			if (INDEX.equals(track.type())) {
				flat.addAll(flatten(track.subTracks()));
				continue;
			}
			flat.add(track);
		}
		return flat;
	}

	private static Track toTrack(DiscogsRelease.Track raw, String position, String albumArtist,
			Map<String, Integer> lookups, TrackCorrection correction) {
		String artist = raw.artists() == null || raw.artists().isEmpty() ? albumArtist
				: NameCleaner.joinArtists(raw.artists());
		String title = NameCleaner.clean(raw.title());

		Integer duration;
		DurationSource source;
		Integer discogsDuration = Durations.parse(raw.duration());
		Integer lookupDuration = lookups.get(position);
		if (correction != null && correction.durationSec() != null) {
			duration = correction.durationSec();
			source = DurationSource.CORRECTION;
		}
		else if (discogsDuration != null) {
			duration = discogsDuration;
			source = DurationSource.DISCOGS;
		}
		else if (lookupDuration != null && lookupDuration > 0) {
			duration = lookupDuration;
			source = DurationSource.LOOKUP;
		}
		else {
			duration = Durations.DEFAULT_SECONDS;
			source = DurationSource.DEFAULT;
		}

		boolean skip = false;
		if (correction != null) {
			if (correction.title() != null && !correction.title().isBlank()) {
				title = correction.title().trim();
			}
			if (correction.artist() != null && !correction.artist().isBlank()) {
				artist = correction.artist().trim();
			}
			skip = correction.skip();
		}
		return new Track(position, artist, title, duration, source, !skip && duration >= MIN_SCROBBLE_SECONDS);
	}

	private static List<Group> groupBySide(List<Parsed> tracks, List<Track> unassigned) {
		Map<String, List<Track>> sides = new TreeMap<>(SIDE_ORDER);
		for (Parsed t : tracks) {
			if (t.position().kind() == Position.Kind.SIDE) {
				sides.computeIfAbsent(t.position().side(), s -> new ArrayList<>()).add(t.track());
			}
			else {
				unassigned.add(t.track());
			}
		}
		return sides.entrySet().stream().map(e -> new Group(GroupKind.SIDE, e.getKey(), e.getValue())).toList();
	}

	private static List<Group> groupOther(List<Parsed> tracks, List<Track> unassigned) {
		Map<Integer, List<Track>> discs = new TreeMap<>();
		List<Track> rest = new ArrayList<>();
		for (Parsed t : tracks) {
			switch (t.position().kind()) {
				case DISC_TRACK -> discs.computeIfAbsent(t.position().disc(), d -> new ArrayList<>()).add(t.track());
				case UNASSIGNED -> unassigned.add(t.track());
				default -> rest.add(t.track());
			}
		}
		Map<String, Group> groups = new LinkedHashMap<>();
		discs.forEach((disc, list) -> groups.put("disc" + disc, new Group(GroupKind.DISC, disc.toString(), list)));
		if (!rest.isEmpty()) {
			groups.put("all", new Group(GroupKind.ALL, "ALL", rest));
		}
		return List.copyOf(groups.values());
	}

	private record Parsed(Position position, Track track) {
	}

}
