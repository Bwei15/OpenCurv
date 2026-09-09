/*
 * This file is NOT part of upstream BRouter. It is an OpenCurv addition.
 *
 * BRouter keeps VoiceHint / MessageData package-private, so a bridge inside
 * package btools.router is the only way to read turn instructions out of a
 * computed OsmTrack without patching the vendored sources.
 */
package btools.router;

import java.util.ArrayList;
import java.util.List;

public final class OpenCurvTrackAccess {

  private OpenCurvTrackAccess() {
  }

  /** Flat, framework-free view of one BRouter voice hint. */
  public static final class Hint {
    public int ilon;
    public int ilat;
    public int command;          // VoiceHint.C, TL, TR, ...
    public String commandName;   // "TL", "TSHR", "RNDB3", ...
    public int roundaboutExit;   // 0 when not a roundabout
    public double distanceToNext;
    public int indexInTrack;
    public float angle;          // degrees, negative = left
    public float timeSeconds;
  }

  public static List<Hint> hints(OsmTrack track) {
    List<Hint> out = new ArrayList<>();
    if (track == null || track.voiceHints == null) {
      return out;
    }
    for (VoiceHint vh : track.voiceHints.list) {
      Hint h = new Hint();
      h.ilon = vh.ilon;
      h.ilat = vh.ilat;
      h.command = vh.cmd;
      h.commandName = safeCommandName(vh);
      h.roundaboutExit = vh.getExitNumber();
      h.distanceToNext = vh.distanceToNext;
      h.indexInTrack = vh.indexInTrack;
      h.angle = vh.angle == Float.MAX_VALUE ? 0f : vh.angle;
      h.timeSeconds = vh.getTime();
      out.add(h);
    }
    return out;
  }

  private static String safeCommandName(VoiceHint vh) {
    try {
      return vh.getCommandString();
    } catch (RuntimeException e) {
      return "C";
    }
  }

  /**
   * Flat [ilon0, ilat0, ilon1, ilat1, ...] view of the track geometry.
   * Returning primitives keeps the Kotlin side free of per-node object access
   * across the Java boundary on a list that can hold tens of thousands of
   * points.
   */
  public static int[] coordinates(OsmTrack track) {
    if (track == null) {
      return new int[0];
    }
    int n = track.nodes.size();
    int[] out = new int[n * 2];
    for (int i = 0; i < n; i++) {
      OsmPathElement e = track.nodes.get(i);
      out[i * 2] = e.getILon();
      out[i * 2 + 1] = e.getILat();
    }
    return out;
  }

  /** Elevation in metres for every track node; NaN where unknown. */
  public static double[] elevations(OsmTrack track) {
    if (track == null) {
      return new double[0];
    }
    int n = track.nodes.size();
    double[] out = new double[n];
    for (int i = 0; i < n; i++) {
      OsmPathElement e = track.nodes.get(i);
      out[i] = e.getSElev() == Short.MIN_VALUE ? Double.NaN : e.getElev();
    }
    return out;
  }

  /**
   * Per-track-node description of the way that node sits on, e.g.
   * "highway=tertiary maxspeed=30". Only tags the routing profile actually
   * references appear here; entries are null where nothing changed.
   */
  public static String[] wayDescriptions(OsmTrack track) {
    if (track == null) {
      return new String[0];
    }
    String[] out = new String[track.nodes.size()];
    for (int i = 0; i < track.nodes.size(); i++) {
      MessageData m = track.nodes.get(i).message;
      out[i] = m == null ? null : m.wayKeyValues;
    }
    return out;
  }

  /** Total ride time BRouter estimated for the track, in seconds. */
  public static int totalSeconds(OsmTrack track) {
    return track == null ? 0 : track.getTotalSeconds();
  }
}
