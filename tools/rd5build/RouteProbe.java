/**
 * RouteProbe -- routet einmal ueber den Pruefstand und gibt aus,
 *   (a) welche der beiden parallelen Verbindungen genommen wurde,
 *   (b) welche Key/Value-Paare BRouter fuer jeden Abschnitt AUS DER .rd5
 *       dekodiert hat (das ist der Beweis, dass der Tag in der Kachel steht),
 *   (c) den Kostenfaktor, den das Profil daraus errechnet hat.
 *
 * Bewusst nur gegen btools.router / btools.expressions / btools.mapaccess
 * geschrieben -- damit laeuft dieselbe Klasse gegen den Upstream-Fat-Jar UND
 * gegen das im Repo einvendorte brouter/-Modul (das ist der Code, der spaeter
 * auf dem Handy laeuft).
 *
 * Aufruf:
 *   java -cp <jar>:<classes> RouteProbe <segmentdir> <profiledir> <profilname> \
 *        <lon1> <lat1> <lon2> <lat2>
 */

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import btools.router.OsmNodeNamed;
import btools.router.OsmTrack;
import btools.router.OsmPathElement;
import btools.router.RoutingContext;
import btools.router.RoutingEngine;

public class RouteProbe {

  private static OsmNodeNamed wp(String name, double lon, double lat) {
    OsmNodeNamed n = new OsmNodeNamed();
    n.name = name;
    n.ilon = (int) ((lon + 180.) * 1000000. + 0.5);
    n.ilat = (int) ((lat + 90.) * 1000000. + 0.5);
    return n;
  }

  public static void main(String[] args) throws Exception {
    File segmentDir = new File(args[0]);
    String profileDir = args[1];
    String profile = args[2];

    System.setProperty("segmentBaseDir", segmentDir.getAbsolutePath());
    System.setProperty("profileBaseDir", profileDir);

    List<OsmNodeNamed> wplist = new ArrayList<>();
    wplist.add(wp("from", Double.parseDouble(args[3]), Double.parseDouble(args[4])));
    wplist.add(wp("to", Double.parseDouble(args[5]), Double.parseDouble(args[6])));

    RoutingContext rc = new RoutingContext();
    rc.localFunction = profile; // wird ueber profileBaseDir + ".brf" aufgeloest

    RoutingEngine re = new RoutingEngine(null, null, segmentDir, wplist, rc, 0);
    re.quite = true;
    re.doRun(0);

    if (re.getErrorMessage() != null) {
      System.out.println("PROFILE=" + profile + " ERROR=" + re.getErrorMessage());
      System.exit(2);
    }

    OsmTrack t = re.getFoundTrack();
    double minLat = 999, maxLat = -999;
    for (OsmPathElement e : t.nodes) {
      double lat = (e.getILat() - 90000000) / 1000000.;
      if (lat < minLat) minLat = lat;
      if (lat > maxLat) maxLat = lat;
    }
    String branch = maxLat > 52.001 ? "NORD" : (minLat < 51.999 ? "SUED" : "?");

    System.out.println("PROFILE=" + profile
      + " BRANCH=" + branch
      + " DIST=" + t.distance + "m"
      + " COST=" + t.cost
      + " LATRANGE=[" + minLat + " .. " + maxLat + "]");

    // Spalten von MessageData.toMessage():
    // lon lat ele linkdist costfactor*1000 elevcost turncost nodecost initcost wayTags nodeTags time energy
    for (String m : t.aggregateMessages()) {
      String[] c = m.split("\t");
      if (c.length > 9) {
        System.out.println("   dist=" + c[3] + "m costfactor=" + (Integer.parseInt(c[4]) / 1000.)
          + "  tags: " + c[9]);
      } else {
        System.out.println("   " + m);
      }
    }
  }
}
