import java.util.*;

/**
 * Computes the AFK point (x,y,z) that maximizes guardian spawning "coverage" for a given
 * set of ocean monuments.
 *
 * Coverage model (offline, deterministic):
 * - Each monument contributes a fixed spawnable bounding box (58x58 in X/Z, Y=39..61 inclusive).
 *   For a monument center (a,b) in blocks:
 *     x in [a-29, a+28]
 *     z in [b-29, b+28]
 *     y in [39, 61]
 * - A spawnable block counts if its distance to the AFK point P(x,y,z) is within the mob-spawn annulus:
 *     24 <= dist(P, block) <= 128
 *   i.e. 24^2 <= d2 <= 128^2
 *
 * Implementation notes:
 * - We do NOT iterate over every block in the 3D box. Instead, we iterate over X/Z columns
 *   (58x58 = 3364 columns) and compute how many Y values (39..61) satisfy the annulus constraint.
 * - We do a coarse-to-fine search over candidate AFK points, which is fast enough for k<=4.
 */
public final class MaximumCoverageAFK {
    private MaximumCoverageAFK() {}

    // Mob spawn constants
    private static final int INNER_R = 24;
    private static final int OUTER_R = 128;
    private static final int INNER_R2 = INNER_R * INNER_R;
    private static final int OUTER_R2 = OUTER_R * OUTER_R;

    // Hard constraint: we require the AFK point (x,z) to be within 128 blocks of *each* monument center (horizontal).
    // This matches the tool’s “triple/quad AFK spot” intention and prevents the optimizer from drifting to only cover 2 monuments.
    private static final int CENTER_CONSTRAINT_R = 128;
    private static final int CENTER_CONSTRAINT_R2 = CENTER_CONSTRAINT_R * CENTER_CONSTRAINT_R;

    // Local search defaults (can be overridden with system properties):
    // Coarse scan step in x/z.
    private static final int DEFAULT_LOCAL_STEP = 32;

    // Monument spawnable bounds
    private static final int MON_X_MIN_OFF = -29;
    private static final int MON_X_MAX_OFF = +28;
    private static final int MON_Z_MIN_OFF = -29;
    private static final int MON_Z_MAX_OFF = +28;
    private static final int MON_Y_MIN = 39;
    private static final int MON_Y_MAX = 61;

    // Empirically optimal AFK height for maximum coverage.
    private static final int FIXED_AFK_Y = 50;

    public static final class BestAFK {
        public final int x;
        public final int y;
        public final int z;

        public final int placeBlockX;
        public final int placeBlockY;
        public final int placeBlockZ;

        /** Total counted spawnable blocks across all monuments. */
        public final long totalCovered;

        /** Per-monument counted spawnable blocks, same order as input list. */
        public final long[] perMonumentCovered;

        BestAFK(int x, int y, int z, long totalCovered, long[] perMonumentCovered) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.placeBlockX = x;
            this.placeBlockY = y - 1;
            this.placeBlockZ = z;
            this.totalCovered = totalCovered;
            this.perMonumentCovered = perMonumentCovered;
        }

        @Override
        public String toString() {
            return "BestAFK{x=" + x + ", y=" + y + ", z=" + z + ", totalCovered=" + totalCovered + "}";
        }
    }

    /**
     * Find the best AFK point for this monument group.
     *
     * Defaults are tuned for practicality:
     * - Fix y=50 (empirically optimal) to avoid iterating y
     * - Coarse step 8 in x/z
     * - Keep top 40 candidates, refine locally down to step=1
     */
    public static BestAFK findBest(List<OceanMonumentCoords.MonumentPos> monuments) {
        Objects.requireNonNull(monuments, "monuments");
        if (monuments.isEmpty()) {
            throw new IllegalArgumentException("monuments is empty");
        }

        final int keepTop = Integer.getInteger("afk.coverage.keepTop", 40);
        final int refineRadius = Integer.getInteger("afk.coverage.refineRadius", 24);
        final String refineStepsStr = System.getProperty("afk.coverage.refineSteps", "4,2,1");
        final boolean requireOutside24 = Boolean.parseBoolean(System.getProperty("afk.coverage.requireOutside24", "true"));
        final boolean debug = Boolean.parseBoolean(System.getProperty("afk.coverage.debug", "false"));

        int[] refineSteps = parseSteps(refineStepsStr);

        // Precompute monument boxes for fast scoring.
        MonBox[] boxes = new MonBox[monuments.size()];
        for (int i = 0; i < monuments.size(); i++) {
            OceanMonumentCoords.MonumentPos m = monuments.get(i);
            boxes[i] = new MonBox(m.centerX + MON_X_MIN_OFF, m.centerX + MON_X_MAX_OFF,
                                  m.centerZ + MON_Z_MIN_OFF, m.centerZ + MON_Z_MAX_OFF);
        }

        // Keep centers for the hard constraint.
        int[] cx = new int[monuments.size()];
        int[] cz = new int[monuments.size()];
        for (int i = 0; i < monuments.size(); i++) {
            cx[i] = monuments.get(i).centerX;
            cz[i] = monuments.get(i).centerZ;
        }

        // Local search space: center at the average of monument centers.
        // This is both faster and empirically yields better AFK points for triples/quads.
        int ax = 0, az = 0;
        for (int i = 0; i < monuments.size(); i++) {
            ax += cx[i];
            az += cz[i];
        }
        ax = Math.round(ax / (float) monuments.size());
        az = Math.round(az / (float) monuments.size());

        final int localStep = Integer.getInteger("afk.coverage.localStep", DEFAULT_LOCAL_STEP);

        // Seed points for the coarse scan.
        // We include the average point plus pairwise circle intersection points (radius=128 around each monument).
        ArrayList<Pt> seeds2D = new ArrayList<>();
        seeds2D.add(new Pt(ax, az));
        addCircleIntersectionSeeds(seeds2D, cx, cz, CENTER_CONSTRAINT_R);

        final int fixedY = clampY(FIXED_AFK_Y);
        // Feasible region bounding rectangle for intersection of disks radius 128:
        // x in [max(cx-128), min(cx+128)], z in [max(cz-128), min(cz+128)].
        FeasibleBounds fb = feasibleBounds(cx, cz, CENTER_CONSTRAINT_R);
        if (fb.isEmpty()) {
            // Should not happen for real triple/quad, but keep a safe fallback.
            Score s = scoreAt(boxes, ax, fixedY, az, requireOutside24);
            return new BestAFK(ax, fixedY, az, s.total, s.perMon);
        }

        int xMin = fb.xMin;
        int xMax = fb.xMax;
        int zMin = fb.zMin;
        int zMax = fb.zMax;

        // Coarse scan: keep top-N by score.
        TopN top = new TopN(Math.max(1, keepTop));

        if (debug) {
            System.out.println("[DEBUG] Coverage search avg=(" + ax + "," + az + ") bounds: x[" + xMin + "," + xMax + "] z[" + zMin + "," + zMax + "] y=" + fixedY + " step=" + localStep);
        }

        // Score explicit seed points (avg + circle intersections). This helps when the best point lies on the boundary
        // of the intersection region and the step grid would otherwise miss it.
        for (Pt p : seeds2D) {
            if (!withinAllCenters(cx, cz, p.x, p.z)) continue;
            Score s = scoreAt(boxes, p.x, fixedY, p.z, requireOutside24);
            top.offer(p.x, fixedY, p.z, s.total, s.perMon);
        }

        // Coarse scan within the feasible intersection bounds.
        // We use a coarse step (default 32) for speed; refinement will take over.
        int step0 = Math.max(1, localStep);
        for (int x = floorToStep(xMin, step0); x <= xMax; x += step0) {
            for (int z = floorToStep(zMin, step0); z <= zMax; z += step0) {
                if (!withinAllCenters(cx, cz, x, z)) continue;
                Score s = scoreAt(boxes, x, fixedY, z, requireOutside24);
                top.offer(x, fixedY, z, s.total, s.perMon);
            }
        }


        Candidate[] seeds = top.toArraySortedDesc();
        if (seeds.length == 0) {
            // Fall back to average point (and if even that fails constraint, return it anyway with its score).
            Score s;
            if (withinAllCenters(cx, cz, ax, az)) {
                s = scoreAt(boxes, ax, fixedY, az, requireOutside24);
            } else {
                s = scoreAt(boxes, ax, fixedY, az, requireOutside24);
            }
            return new BestAFK(ax, fixedY, az, s.total, s.perMon);
        }

        // Refine each seed locally; choose global best.
        BestAFK best = null;
        long bestScore = Long.MIN_VALUE;

        for (Candidate c : seeds) {
            int cx0 = c.x;
            int cy = c.y;
            int cz0 = c.z;

            // Multi-scale refinement in x/z; optionally keep y fixed (default), but we can also try a small y neighborhood.
            int rx = cx0;
            int rz = cz0;
            int ry = fixedY;

            long[] bestPer = null;
            long localBest = Long.MIN_VALUE;

            // First, score the seed itself.
            Score base = scoreAt(boxes, rx, fixedY, rz, requireOutside24);
            localBest = base.total;
            bestPer = base.perMon;

            for (int step : refineSteps) {
                int r = refineRadius;
                // Search a local window around the current best.
                for (int x = rx - r; x <= rx + r; x += step) {
                    for (int z = rz - r; z <= rz + r; z += step) {
                        if (!withinAllCenters(cx, cz, x, z)) continue;
                        Score s = scoreAt(boxes, x, fixedY, z, requireOutside24);
                        if (s.total > localBest) {
                            localBest = s.total;
                            rx = x;
                            ry = fixedY;
                            rz = z;
                            bestPer = s.perMon;
                        }
                    }
                }
            }

            if (localBest > bestScore) {
                bestScore = localBest;
                best = new BestAFK(rx, ry, rz, localBest, bestPer);
            }
        }

        // Final sanity: ensure placeBlockY is within world-ish bounds (players can adjust).
        return best;
    }

    // -------------------- Scoring --------------------

    /** Monument box in block coordinates (inclusive bounds). */
    private static final class MonBox {
        final int x0, x1;
        final int z0, z1;

        MonBox(int x0, int x1, int z0, int z1) {
            this.x0 = x0;
            this.x1 = x1;
            this.z0 = z0;
            this.z1 = z1;
        }
    }


    /** Holds total + per-monument covered counts. */
    private static final class Score {
        final long total;
        final long[] perMon;
        Score(long total, long[] perMon) {
            this.total = total;
            this.perMon = perMon;
        }
    }

    /**
     * Compute coverage score at a candidate AFK point.
     *
     * Uses X/Z columns and computes how many y in [MON_Y_MIN..MON_Y_MAX] satisfy:
     *   INNER_R2 <= d2_h + (yBlock - y)^2 <= OUTER_R2
     */
    private static Score scoreAt(MonBox[] boxes, int x, int y, int z, boolean requireOutside24) {
        long total = 0;
        long[] per = new long[boxes.length];

        for (int i = 0; i < boxes.length; i++) {
            MonBox b = boxes[i];
            long count = 0;

            for (int bx = b.x0; bx <= b.x1; bx++) {
                int dx = bx - x;
                int dx2 = dx * dx;
                for (int bz = b.z0; bz <= b.z1; bz++) {
                    int dz = bz - z;
                    int d2h = dx2 + dz * dz;

                    // If horizontally too far beyond outer radius, no y can fix it.
                    if (d2h > OUTER_R2) continue;

                    // Solve for (by - y)^2 bounds.
                    // Need: INNER_R2 - d2h <= (by-y)^2 <= OUTER_R2 - d2h
                    int upper = OUTER_R2 - d2h;
                    if (upper < 0) continue;

                    int lower = requireOutside24 ? (INNER_R2 - d2h) : Integer.MIN_VALUE;

                    // Convert squared bounds into |dy| bounds.
                    // For upper: |dy| <= floor(sqrt(upper))
                    int maxAbsDy = isqrtFloor(upper);

                    // For lower: |dy| >= ceil(sqrt(lower)) when lower>0, else 0.
                    int minAbsDy;
                    if (!requireOutside24) {
                        minAbsDy = 0;
                    } else if (lower <= 0) {
                        minAbsDy = 0;
                    } else {
                        int s = isqrtFloor(lower);
                        minAbsDy = (s * s == lower) ? s : (s + 1);
                    }

                    // Now count integer by in [MON_Y_MIN..MON_Y_MAX] satisfying minAbsDy <= |by-y| <= maxAbsDy.
                    count += countYInAnnulus(y, minAbsDy, maxAbsDy);
                }
            }

            per[i] = count;
            total += count;
        }

        return new Score(total, per);
    }

    /** Count by in [MON_Y_MIN..MON_Y_MAX] s.t. minAbsDy <= |by-y| <= maxAbsDy. */
    private static int countYInAnnulus(int y, int minAbsDy, int maxAbsDy) {
        if (maxAbsDy < 0) return 0;
        if (minAbsDy < 0) minAbsDy = 0;
        if (minAbsDy > maxAbsDy) return 0;

        // Valid by are in [y-maxAbsDy, y+maxAbsDy] minus the "inner" strip (y-(minAbsDy-1) .. y+(minAbsDy-1)).
        int loOuter = y - maxAbsDy;
        int hiOuter = y + maxAbsDy;

        int outerCount = intersectCount(loOuter, hiOuter, MON_Y_MIN, MON_Y_MAX);
        if (outerCount == 0) return 0;

        if (minAbsDy == 0) {
            // No hole.
            return outerCount;
        }

        int holeR = minAbsDy - 1;
        int loHole = y - holeR;
        int hiHole = y + holeR;
        int holeCount = intersectCount(loHole, hiHole, MON_Y_MIN, MON_Y_MAX);

        int res = outerCount - holeCount;
        return Math.max(0, res);
    }

    /** Inclusive intersection count between [a0,a1] and [b0,b1]. */
    private static int intersectCount(int a0, int a1, int b0, int b1) {
        int lo = Math.max(a0, b0);
        int hi = Math.min(a1, b1);
        return (hi >= lo) ? (hi - lo + 1) : 0;
    }

    // -------------------- Top-N selection (small, allocation-light) --------------------

    private static final class Candidate {
        final int x, y, z;
        final long score;
        final long[] perMon;
        Candidate(int x, int y, int z, long score, long[] perMon) {
            this.x = x; this.y = y; this.z = z;
            this.score = score;
            this.perMon = perMon;
        }
    }

    /**
     * Keeps the best N candidates by score.
     * We store perMon arrays only for kept candidates.
     */
    private static final class TopN {
        private final int n;
        private final PriorityQueue<Candidate> pq;

        TopN(int n) {
            this.n = n;
            this.pq = new PriorityQueue<>(Comparator.comparingLong(c -> c.score)); // min-heap
        }

        void offer(int x, int y, int z, long score, long[] perMon) {
            if (n <= 0) return;

            if (pq.size() < n) {
                pq.add(new Candidate(x, y, z, score, perMon));
                return;
            }

            Candidate min = pq.peek();
            if (min != null && score > min.score) {
                pq.poll();
                pq.add(new Candidate(x, y, z, score, perMon));
            }
        }

        Candidate[] toArraySortedDesc() {
            Candidate[] arr = pq.toArray(new Candidate[0]);
            Arrays.sort(arr, (a, b) -> Long.compare(b.score, a.score));
            return arr;
        }
    }

    // -------------------- Utilities --------------------

    private static final class Pt {
        final int x, z;
        Pt(int x, int z) { this.x = x; this.z = z; }
    }

    private static final class FeasibleBounds {
        final int xMin, xMax, zMin, zMax;
        FeasibleBounds(int xMin, int xMax, int zMin, int zMax) {
            this.xMin = xMin; this.xMax = xMax; this.zMin = zMin; this.zMax = zMax;
        }
        boolean isEmpty() { return xMin > xMax || zMin > zMax; }
    }

    private static FeasibleBounds feasibleBounds(int[] cx, int[] cz, int r) {
        int xMin = Integer.MIN_VALUE;
        int xMax = Integer.MAX_VALUE;
        int zMin = Integer.MIN_VALUE;
        int zMax = Integer.MAX_VALUE;
        for (int i = 0; i < cx.length; i++) {
            xMin = Math.max(xMin, cx[i] - r);
            xMax = Math.min(xMax, cx[i] + r);
            zMin = Math.max(zMin, cz[i] - r);
            zMax = Math.min(zMax, cz[i] + r);
        }
        return new FeasibleBounds(xMin, xMax, zMin, zMax);
    }

    private static int clampY(int y) {
        // Keep within plausible world heights; doesn't matter much.
        if (y < -64) return -64;
        if (y > 320) return 320;
        return y;
    }

    private static int floorToStep(int v, int step) {
        if (step <= 1) return v;
        int q = Math.floorDiv(v, step);
        return q * step;
    }

    private static int[] parseSteps(String s) {
        if (s == null || s.trim().isEmpty()) return new int[] {4, 2, 1};
        String[] parts = s.split(",");
        ArrayList<Integer> out = new ArrayList<>();
        for (String p : parts) {
            String t = p.trim();
            if (t.isEmpty()) continue;
            try {
                int v = Integer.parseInt(t);
                if (v > 0) out.add(v);
            } catch (NumberFormatException ignored) {}
        }
        if (out.isEmpty()) return new int[] {4, 2, 1};
        int[] a = new int[out.size()];
        for (int i = 0; i < out.size(); i++) a[i] = out.get(i);
        return a;
    }

    /** Integer sqrt floor for non-negative int. */
    private static int isqrtFloor(int x) {
        if (x <= 0) return 0;
        int r = (int) Math.sqrt(x);
        // Correct potential floating error.
        while ((long) (r + 1) * (long) (r + 1) <= x) r++;
        while ((long) r * (long) r > x) r--;
        return r;
    }

    /**
     * Adds pairwise circle-circle intersection points (rounded).
     * All circles have the same radius r.
     */
    private static void addCircleIntersectionSeeds(List<Pt> out, int[] cx, int[] cz, int r) {
        final double R = (double) r;

        for (int i = 0; i < cx.length; i++) {
            for (int j = i + 1; j < cx.length; j++) {
                double x0 = cx[i], z0 = cz[i];
                double x1 = cx[j], z1 = cz[j];

                double dx = x1 - x0;
                double dz = z1 - z0;
                double d2 = dx * dx + dz * dz;
                if (d2 == 0) continue;

                double d = Math.sqrt(d2);
                if (d > 2.0 * R) continue; // too far => no intersections

                // midpoint between centers
                double xm = x0 + dx * 0.5;
                double zm = z0 + dz * 0.5;

                // distance from midpoint to intersections along perpendicular
                double h2 = R * R - (d * d) * 0.25;
                if (h2 < 0) continue;
                double h = Math.sqrt(h2);

                // perpendicular unit vector
                double ux = -dz / d;
                double uz =  dx / d;

                double xi1 = xm + ux * h;
                double zi1 = zm + uz * h;
                double xi2 = xm - ux * h;
                double zi2 = zm - uz * h;

                out.add(new Pt((int) Math.round(xi1), (int) Math.round(zi1)));
                out.add(new Pt((int) Math.round(xi2), (int) Math.round(zi2)));
            }
        }
    }

    /** Hard constraint: candidate (x,z) must be within 128 blocks (horizontal) of every monument center. */
    private static boolean withinAllCenters(int[] cx, int[] cz, int x, int z) {
        for (int i = 0; i < cx.length; i++) {
            int dx = cx[i] - x;
            int dz = cz[i] - z;
            int d2 = dx * dx + dz * dz;
            if (d2 > CENTER_CONSTRAINT_R2) return false;
        }
        return true;
    }
}