import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class Main {
    enum Type {
        DOUBLE(2), TRIPLE(3), QUAD(4); // We want to define the monument types for a player to search.

        final int k;

        Type(int k) {
            this.k = k;
        }

        static Type from(String s) {
            switch (s.toLowerCase(Locale.ROOT)) {
                case "double":
                    return DOUBLE;
                case "triple":
                    return TRIPLE;
                case "quad":
                    return QUAD;
                default:
                    throw new IllegalArgumentException("type must be one of: double, triple, quad");
            }
        }
    }

    private static void writeCsv(String path, Type type, List<AFKSpotFinder.AFKSpot> spots) throws IOException {
        try (BufferedWriter w = new BufferedWriter(new FileWriter(path))) {
            w.write("type,afkX,afkZ,netherX,netherZ,count,monuments\n");
            for (AFKSpotFinder.AFKSpot s : spots) {
                // Nether conversion: divide by 8, round to nearest int (you can change policy)
                int netherX = (int) Math.round(s.afkX / 8.0);
                int netherZ = (int) Math.round(s.afkZ / 8.0);

                String monumentsStr = s.monuments.stream()
                        .map(m -> "(" + m.centerX + "," + m.centerZ + ")")
                        .collect(Collectors.joining(";"));

                w.write(String.format(Locale.ROOT,
                        "%s,%d,%d,%d,%d,%d,%s\n",
                        type.name().toLowerCase(Locale.ROOT),
                        s.afkX, s.afkZ,
                        netherX, netherZ,
                        s.count,
                        csvEscape(monumentsStr)));
            }
        }
    }

    private static String csvEscape(String s) {
        // minimal safe CSV escaping
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static long parseLongStrict(String s, String name) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid " + name + ": " + s);
        }
    }

    private static int parseIntStrict(String s, String name) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid " + name + ": " + s);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.err.println("Usage:");
            System.err.println("  java Main <seed> <double|triple|quad> <rangeBlocks> <excludeRadius> <threads>");
            System.err.println("Examples:");
            System.err.println("  java Main 123456789 double 20000 10000 4");
            System.exit(1);
        }

        long seed = parseLongStrict(args[0], "seed");
        Type type = Type.from(args[1]);
        int rangeBlocks = parseIntStrict(args[2], "rangeBlocks");
        int excludeRadius = parseIntStrict(args[3], "excludeRadius");
        int threads = parseIntStrict(args[4], "threads");

        if (rangeBlocks <= 0) {
            throw new IllegalArgumentException("rangeBlocks must be > 0");
        }
        if (excludeRadius < 0) {
            throw new IllegalArgumentException("excludeRadius must be >= 0");
        }
        if (excludeRadius > rangeBlocks) {
            throw new IllegalArgumentException("excludeRadius must be <= rangeBlocks");
        }

        // 1) Find all ocean monuments in range
        OceanMonumentCoords locator = new OceanMonumentCoords(seed, threads);

        // NOTE: This returns monument *centers* in block coords (x,z).
        // The internal algorithm is intentionally pluggable.
        List<OceanMonumentCoords.MonumentPos> monuments = locator.findMonumentsInRange(rangeBlocks, excludeRadius,
                type.k);

        // 2) Find AFK spots for requested type
        AFKSpotFinder finder = new AFKSpotFinder(threads);

        List<AFKSpotFinder.AFKSpot> spots = finder.findAFKSpots(monuments, type.k);

        // Sort: highest count first (all same here), then closest to origin, then
        // coordinates
        spots.sort(Comparator
                .comparingInt((AFKSpotFinder.AFKSpot s) -> -s.count)
                .thenComparingDouble(s -> s.distanceToOrigin())
                .thenComparingInt(s -> s.afkX)
                .thenComparingInt(s -> s.afkZ));

        // 3) Write CSV
        writeCsv("results.csv", type, spots);

        System.out.println("[DONE] Wrote results.csv with " + spots.size() + " AFK spot(s).");
    }
}
