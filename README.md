# SP1 Merge

Android app that merges a 4-stem separation (vocals / other / bass / drums —
the standard [Demucs](https://github.com/facebookresearch/demucs) split) into
a single WAV file formatted for the Teenage Engineering SP-1 (Stem Player).

Built as an on-device alternative to
[sp1.clefairy.org](https://sp1.clefairy.org) and its companion CLI,
[softmodded/sp1-merge](https://github.com/softmodded/sp1-merge), for cases
where the browser tool's ~5 minute length cap gets in the way. Output format
was reverse-engineered from that project for compatibility — this is an
independent Java implementation, not a port of its source.

## What it does

1. Pick 4 stem files (any format the device's decoders support — mp3, wav,
   flac, m4a, ...)
2. Decodes each with `MediaExtractor`/`MediaCodec`
3. Resamples anything that isn't already 48kHz using a windowed-sinc filter
4. Trims all four to the shortest one's length
5. Writes an 8-channel, 24-bit, 48kHz `WAVE_FORMAT_EXTENSIBLE` WAV — channel
   order per frame is vocals L/R, other L/R, bass L/R, drums L/R

The merge runs in a foreground service so it keeps going if the app is
backgrounded, with a Cancel option that cleans up the partial output file.

## Building

Written for [AIDE Pro](https://www.android-ide.com/) — no Gradle project
files here, just the Java sources and manifest under `app/src/main/`. Create
a new AIDE Pro project with package `com.joshua.sp1merge`, drop these files
into the matching folders, and build.

Needs `android:largeHeap="true"` and a declared `<service>` for
`MergeService` in the manifest (both already present in the one here) — a
several-minute song at 4 stems needs real headroom.

## Notes

- Samples are stored as 16-bit internally (matching the real precision of
  mp3-decoded source audio) rather than float, to keep memory usage down —
  a 5 minute song across 4 stems is still a few hundred MB in memory at
  peak.
- There's a `DebugLog`/"Show Log" facility left in for now — writes a
  timestamped trace of each merge to the app's external files folder,
  viewable and copyable from the app itself. Harmless to leave in; strip it
  out once things have proven solid across more songs.
- Not affiliated with Teenage Engineering or the softmodded/sp1-merge
  project.

## License

All rights reserved. No license is granted for reuse, modification, or
redistribution — shared for reference only.

