/**
 * RouteCheck -- Abnahmepruefung fuer eine erzeugte .rd5-Region.
 *
 * Routet eine Strecke ueber die frisch gebauten Kacheln und gibt aus:
 *   - Laenge, Kosten, Anzahl Abschnitte
 *   - wie viele Meter der Route einen `opencurv:curve`-Tag tragen (aus der
 *     .rd5 DEKODIERT, nicht aus der OSM-Eingabe)
 *   - den laengengewichteten Mittelwert des Scores
 *   - die Spanne der Hoehenwerte (Nachweis, dass PosUnifier Hoehen aufgepraegt
 *     hat)
 *
 * Damit ist eine Kachel erst dann "gut", wenn eine echte Route darin faehrt
 * UND der Score dabei sichtbar ist. Genau das ist die Stufenpruefung, die die
 * Pipeline zwischen WayLinker und Veroeffentlichung braucht.
 *
 * Bewusst nur gegen btools.router / btools.mapaccess geschrieben, damit die
 * Klasse sowohl gegen den Upstream-Fat-Jar als auch gegen das einvendorte
 * brouter/-Modul laeuft.
 *
 * Aufruf:
 *   java -cp <jar>:<classes> RouteCheck <segmentdir> <profiledir> <profil> \
 *        <lon1> <lat1> <lon2> <lat2>
 *
 * Exit-Code 0 = Route gefunden, 2 = Routing-Fehler.
 */

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import btools.router.OsmNodeNamed;
import btools.router.OsmPathElement;
import btools.router.OsmTrack;
import btools.router.RoutingContext;
import btools.router.RoutingEngine;

public class RouteCheck {

  private static OsmNodeNamed wp(String name, double lon, double lat) {
    OsmNodeNamed n = new OsmNodeNamed();
    n.name = name;
    n.ilon = (int) ((lon + 180.) * 1000000. + 0.5);
    n.ilat = (int) ((lat + 90.) * 1000000. + 0.5);
    return n;
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 7) {
      System.out.println("usage: RouteCheck <segmentdir> <profiledir> <profil> <lon1> <lat1> <lon2> <lat2>");
      System.exit(1);
    }
    File segmentDir = new File(args[0]);
    String profileDir = args[1];
    String profile = args[2];

    System.setProperty("segmentBaseDir", segmentDir.getAbsolutePath());
    System.setProperty("profileBaseDir", profileDir);

    List<OsmNodeNamed> wplist = new ArrayList<>();
    wplist.add(wp("from", Double.parseDouble(args[3]), Double.parseDouble(args[4])));
    wplist.add(wp("to", Double.parseDouble(args[5]), Double.parseDouble(args[6])));

    RoutingContext rc = new RoutingContext();
    rc.localFunction = profile;

    RoutingEngine re = new RoutingEngine(null, null, segmentDir, wplist, rc, 0);
    re.quite = true;
    re.doRun(0);

    if (re.getErrorMessage() != null) {
      System.out.println("PROFILE=" + profile + " ERROR=" + re.getErrorMessage());
      System.exit(2);
    }

    OsmTrack t = re.getFoundTrack();

    int eleMin = Integer.MAX_VALUE, eleMax = Integer.MIN_VALUE;
    for (OsmPathElement e : t.nodes) {
      short se = e.getSElev();
      if (se == Short.MIN_VALUE) continue;
      int ele = se / 4;
      if (ele < eleMin) eleMin = ele;
      if (ele > eleMax) eleMax = ele;
    }

    long distTotal = 0, distScored = 0;
    double scoreWeighted = 0;
    int sections = 0;
    double scoreMin = Double.MAX_VALUE, scoreMax = -Double.MAX_VALUE;

    for (String m : t.aggregateMessages()) {
      String[] c = m.split("\t");
      if (c.length <= 9) continue;
      sections++;
      long d;
      try {
        d = Long.parseLong(c[3].trim());
      } catch (NumberFormatException nfe) {
        continue;
      }
      distTotal += d;
      String tags = c[9];
      int idx = tags.indexOf("opencurv:curve=");
      if (idx >= 0) {
        String v = tags.substring(idx + "opencurv:curve=".length());
        int sp = v.indexOf(' ');
        if (sp > 0) v = v.substring(0, sp);
        try {
          double s = Double.parseDouble(v);
          distScored += d;
          scoreWeighted += s * d;
          if (s < scoreMin) scoreMin = s;
          if (s > scoreMax) scoreMax = s;
        } catch (NumberFormatException ignored) {
        }
      }
    }

    double meanScore = distScored > 0 ? scoreWeighted / distScored : Double.NaN;
    double coverage = distTotal > 0 ? 100.0 * distScored / distTotal : 0.0;

    System.out.printf(
      "PROFILE=%s DIST=%dm COST=%d SECTIONS=%d SCORED=%.1f%% MEANSCORE=%.2f SCORERANGE=[%.1f..%.1f] ELE=[%s..%s]%n",
      profile, t.distance, t.cost, sections, coverage, meanScore,
      scoreMin == Double.MAX_VALUE ? 0 : scoreMin,
      scoreMax == -Double.MAX_VALUE ? 0 : scoreMax,
      eleMin == Integer.MAX_VALUE ? "-" : String.valueOf(eleMin),
      eleMax == Integer.MIN_VALUE ? "-" : String.valueOf(eleMax));
  }
}
