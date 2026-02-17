import java.util.*;
import java.util.concurrent.*;

/**
 * Finds AFK points (in block coordinates) that are within 128 blocks of k ocean monument centers,
 * then refines each unique monument-set to the best AFK (x,y,z) that maximizes guardian spawn coverage.
 *
 * Notes:
 * - Monument centers are expected to be in OVERWORLD block coords (center-of-chunk: chunk*16+8).
 * - For any valid solution with k>=2, at least one pair of monuments must be within 256 blocks.
 *   We use this to safely prune isolated monuments to reduce memory/heap pressure.
 */
public class AFKSpotFinder {

    public static final class AFKSpot {
        public final int afkX; // blocks
        public final int afkY; // blocks (best AFK Y after coverage optimization; -1 if not computed)
        public final int afkZ; // blocks

        // Convenience: tell the player where to place the AFK block (x, y-1, z).
        public final int placeBlockX;
        public final int placeBlockY;
        public final int placeBlockZ;

        // Coverage score (spawnable blocks counted by MaximumCoverageAFK); -1 if not computed.
        public final long totalCovered;

        public final int count; // 2/3/4
        public final List<OceanMonumentCoords.MonumentPos> monuments;

        /**
         * Provisional AFK spot (x,z only). Y/coverage will be filled by a later optimization step.
         */
        public AFKSpot(int afkX, int afkZ, List<OceanMonumentCoords.MonumentPos> monuments) {
            this(afkX, -1, afkZ, afkX, -1, afkZ, -1L, monuments);
        }

        /**
         * Final AFK spot (x,y,z) with coverage information.
         */
        public AFKSpot(int afkX, int afkY, int afkZ, int placeBlockX, int placeBlockY, int placeBlockZ,
                       long totalCovered, List<OceanMonumentCoords.MonumentPos> monuments) {
            this.afkX = afkX;
            this.afkY = afkY;
            this.afkZ = afkZ;
            this.placeBlockX = placeBlockX;
            this.placeBlockY = placeBlockY;
            this.placeBlockZ = placeBlockZ;
            this.totalCovered = totalCovered;
            this.monuments = Collections.unmodifiableList(new ArrayList<>(monuments));
            this.count = monuments.size();
        }

        /** Build a finalized AFKSpot from MaximumCoverageAFK's result. */
        static AFKSpot fromCoverage(MaximumCoverageAFK.BestAFK best, List<OceanMonumentCoords.MonumentPos> monuments) {
            return new AFKSpot(best.x, best.y, best.z,
                    best.placeBlockX, best.placeBlockY, best.placeBlockZ,
                    best.totalCovered,
                    monuments);
        }

        public double distanceToOrigin() {
            return Math.hypot(afkX, afkZ);
        }
    }

    private final int threads;

    // Game constants
    private static final int AFK_RADIUS_BLOCKS = 128;
    private static final int AFK_RADIUS2 = AFK_RADIUS_BLOCKS * AFK_RADIUS_BLOCKS;

    // If a point is within 128 of both monuments, their pairwise distance <= 256. (change it to 224)
    private static final int MAX_PAIRWISE_BLOCKS = 224;
    private static final int MAX_PAIRWISE2 = MAX_PAIRWISE_BLOCKS * MAX_PAIRWISE_BLOCKS;

    // Spatial hash cell size in blocks.
    private static final int CELL_SIZE = 256;

    // Debug: print at most N excluded isolated monuments (to avoid log spam).
    // Override with: -Dafk.debugExcludedLimit=0 (disable) or a larger number.
    private static final int DEBUG_EXCLUDED_LIMIT = Integer.getInteger("afk.debugExcludedLimit", 20);

    // Controls batching of anchor processing to avoid 1 Future per monument.
    private static final int DEFAULT_BATCH_SIZE = 25_000;

    public AFKSpotFinder(int threads) {
        this.threads = Math.max(1, threads);
    }

    public List<AFKSpot> findAFKSpots(List<OceanMonumentCoords.MonumentPos> monuments, int k) throws InterruptedException {
        if (k < 2 || k > 4) throw new IllegalArgumentException("k must be 2,3,4");
        if (monuments == null || monuments.isEmpty()) return Collections.emptyList();

        // Build spatial index once.
        SpatialIndex index = new SpatialIndex(monuments);

        // Safe prune for k>=2: remove monuments that cannot participate in ANY solution.
        List<OceanMonumentCoords.MonumentPos> pruned = (k >= 2) ? pruneIsolated(monuments, index) : monuments;
        if (pruned.isEmpty()) return Collections.emptyList();

        // Rebuild over pruned list (smaller buckets, faster queries).
        SpatialIndex prunedIndex = new SpatialIndex(pruned);

        System.out.println("[INFO] AFKSpotFinder: monuments=" + monuments.size() + ", after prune=" + pruned.size());

        // Process anchors in batches to reduce Future overhead.
        final int batchSize = Integer.getInteger("afk.batchSize", DEFAULT_BATCH_SIZE);
        final int n = pruned.size();
        final int numBatches = (n + batchSize - 1) / batchSize;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CompletionService<List<AFKSpot>> cs = new ExecutorCompletionService<>(pool);

        try {
            for (int b = 0; b < numBatches; b++) {
                final int start = b * batchSize;
                final int end = Math.min(n, start + batchSize);
                cs.submit(() -> {
                    ArrayList<AFKSpot> local = new ArrayList<>();
                    for (int i = start; i < end; i++) {
                        local.addAll(findFromAnchor(pruned, i, k, prunedIndex));
                    }
                    return local;
                });
            }

            // Merge + de-duplicate (spots can be found from multiple anchors)
            LongHashMap<AFKSpot> dedup = new LongHashMap<>(Math.max(16, n / 8));

            for (int done = 0; done < numBatches; done++) {
                Future<List<AFKSpot>> f = cs.take();
                List<AFKSpot> got;
                try {
                    got = f.get();
                } catch (ExecutionException e) {
                    throw new RuntimeException(e.getCause());
                }

                for (AFKSpot s : got) {
                    long key = keyOf(s);
                    dedup.putIfAbsent(key, s);
                }
            }

            // Convert unique monument-sets into finalized AFK spots by maximizing coverage (x,y,z).
            ArrayList<AFKSpot> provisional = dedup.values();
            ArrayList<AFKSpot> finalized = new ArrayList<>(provisional.size());

            // Note: This step is typically cheap because the number of unique monument-groups is small
            // compared to the raw candidate count.
            for (AFKSpot s : provisional) {
                MaximumCoverageAFK.BestAFK best = MaximumCoverageAFK.findBest(s.monuments);
                finalized.add(AFKSpot.fromCoverage(best, s.monuments));
            }

            // Optional: sort by best coverage descending, then distance to origin.
            finalized.sort(Comparator
                    .comparingLong((AFKSpot s) -> s.totalCovered).reversed()
                    .thenComparingDouble(AFKSpot::distanceToOrigin));

            return finalized;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Safe pruning:
     * If a monument has no other monument within 256 blocks, it cannot be part of any
     * valid double/triple/quad AFK spot.
     */
    private List<OceanMonumentCoords.MonumentPos> pruneIsolated(
            List<OceanMonumentCoords.MonumentPos> monuments,
            SpatialIndex index
    ) {
        ArrayList<OceanMonumentCoords.MonumentPos> kept = new ArrayList<>(Math.max(16, monuments.size() / 10));
        int excluded = 0;
        int printed = 0;

        for (OceanMonumentCoords.MonumentPos m : monuments) {
            // Query neighbors within 256; keep if we find ANY distinct neighbor.
            List<OceanMonumentCoords.MonumentPos> neigh = index.queryWithin(m.centerX, m.centerZ, MAX_PAIRWISE_BLOCKS);
            boolean hasOther = false;
            for (OceanMonumentCoords.MonumentPos n : neigh) {
                if (n.centerX != m.centerX || n.centerZ != m.centerZ) {
                    hasOther = true;
                    break;
                }
            }

            if (hasOther) {
                kept.add(m);
            } else {
                excluded++;
                if (DEBUG_EXCLUDED_LIMIT > 0 && printed < DEBUG_EXCLUDED_LIMIT) {
                    System.out.println("[DEBUG] Excluding isolated monument (" + m.centerX + "," + m.centerZ + ") - no other monument within "
                            + MAX_PAIRWISE_BLOCKS + " blocks (cannot form double/triple/quad)");
                    printed++;
                }
            }
        }

        if (excluded > 0) {
            System.out.println("[INFO] AFKSpotFinder: excluded isolated monuments=" + excluded);
        }
        return kept;
    }

    private List<AFKSpot> findFromAnchor(
            List<OceanMonumentCoords.MonumentPos> monuments,
            int anchorIdx,
            int k,
            SpatialIndex index
    ) {
        OceanMonumentCoords.MonumentPos a = monuments.get(anchorIdx);

        // Candidate neighbors near anchor (within 256)
        List<OceanMonumentCoords.MonumentPos> neigh = index.queryWithin(a.centerX, a.centerZ, MAX_PAIRWISE_BLOCKS);

        // Deterministic iteration
        neigh.sort(Comparator.comparingInt((OceanMonumentCoords.MonumentPos p) -> p.centerX)
                .thenComparingInt(p -> p.centerZ));

        ArrayList<AFKSpot> out = new ArrayList<>();

        int n = neigh.size();
        if (k == 2) {
            for (OceanMonumentCoords.MonumentPos b : neigh) {
                if (same(a, b)) continue;
                tryAdd(out, a, b);
            }
        } else if (k == 3) {
            for (int i = 0; i < n; i++) {
                OceanMonumentCoords.MonumentPos b = neigh.get(i);
                if (same(a, b)) continue;
                for (int j = i + 1; j < n; j++) {
                    OceanMonumentCoords.MonumentPos c = neigh.get(j);
                    if (same(a, c) || same(b, c)) continue;
                    if (!pairwiseOk(a, b, c)) continue;
                    tryAdd(out, a, b, c);
                }
            }
        } else { // k == 4
            for (int i = 0; i < n; i++) {
                OceanMonumentCoords.MonumentPos b = neigh.get(i);
                if (same(a, b)) continue;
                for (int j = i + 1; j < n; j++) {
                    OceanMonumentCoords.MonumentPos c = neigh.get(j);
                    if (same(a, c) || same(b, c)) continue;
                    for (int t = j + 1; t < n; t++) {
                        OceanMonumentCoords.MonumentPos d = neigh.get(t);
                        if (same(a, d) || same(b, d) || same(c, d)) continue;
                        if (!pairwiseOk(a, b, c, d)) continue;
                        tryAdd(out, a, b, c, d);
                    }
                }
            }
        }

        return out;
    }

    private void tryAdd(List<AFKSpot> out, OceanMonumentCoords.MonumentPos... pts) {
        int k = pts.length;

        // Candidate AFK location = average of centers
        double ax = 0, az = 0;
        for (OceanMonumentCoords.MonumentPos p : pts) {
            ax += p.centerX;
            az += p.centerZ;
        }
        ax /= k;
        az /= k;

        // Validate: AFK point within 128 blocks of each
        for (OceanMonumentCoords.MonumentPos p : pts) {
            double dx = ax - p.centerX;
            double dz = az - p.centerZ;
            double d2 = dx * dx + dz * dz;
            if (d2 > (double) AFK_RADIUS2) return;
        }

        int afkX = (int) Math.round(ax);
        int afkZ = (int) Math.round(az);

        out.add(new AFKSpot(afkX, afkZ, Arrays.asList(pts)));
    }

    private boolean pairwiseOk(OceanMonumentCoords.MonumentPos... pts) {
        for (int i = 0; i < pts.length; i++) {
            for (int j = i + 1; j < pts.length; j++) {
                int dx = pts[i].centerX - pts[j].centerX;
                int dz = pts[i].centerZ - pts[j].centerZ;
                int d2 = dx * dx + dz * dz;
                if (d2 > MAX_PAIRWISE2) return false;
            }
        }
        return true;
    }

    private boolean same(OceanMonumentCoords.MonumentPos a, OceanMonumentCoords.MonumentPos b) {
        return a.centerX == b.centerX && a.centerZ == b.centerZ;
    }

    /**
     * Hash-based dedup key: sorted monument coords only.
     *
     * We intentionally do NOT include AFK coords here, because we later compute the best (x,y,z)
     * via MaximumCoverageAFK and we want exactly one result per unique monument-set.
     */
    private long keyOf(AFKSpot s) {
        long h = 0x9e3779b97f4a7c15L;

        // Sort monument coords deterministically for stable key.
        // We avoid allocating a new list: copy to array.
        OceanMonumentCoords.MonumentPos[] arr = s.monuments.toArray(new OceanMonumentCoords.MonumentPos[0]);
        Arrays.sort(arr, Comparator.comparingInt((OceanMonumentCoords.MonumentPos p) -> p.centerX)
                .thenComparingInt(p -> p.centerZ));

        for (OceanMonumentCoords.MonumentPos p : arr) {
            long v = (((long) p.centerX) << 32) ^ (p.centerZ & 0xffffffffL);
            h = mix64(h ^ v);
        }
        return h;
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return z ^ (z >>> 33);
    }

    /**
     * Simple spatial hash index for "find all monuments within radius".
     */
    private static final class SpatialIndex {
        private final Map<Long, List<OceanMonumentCoords.MonumentPos>> cellMap = new HashMap<>();

        SpatialIndex(List<OceanMonumentCoords.MonumentPos> pts) {
            for (OceanMonumentCoords.MonumentPos p : pts) {
                long key = cellKey(cellX(p.centerX), cellZ(p.centerZ));
                cellMap.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
            }
        }

        List<OceanMonumentCoords.MonumentPos> queryWithin(int x, int z, int radius) {
            int cx = cellX(x);
            int cz = cellZ(z);
            int rCells = (int) Math.ceil(radius / (double) CELL_SIZE);

            int r2 = radius * radius;
            ArrayList<OceanMonumentCoords.MonumentPos> out = new ArrayList<>();

            for (int dx = -rCells; dx <= rCells; dx++) {
                for (int dz = -rCells; dz <= rCells; dz++) {
                    long key = cellKey(cx + dx, cz + dz);
                    List<OceanMonumentCoords.MonumentPos> bucket = cellMap.get(key);
                    if (bucket == null) continue;

                    for (OceanMonumentCoords.MonumentPos p : bucket) {
                        int ddx = p.centerX - x;
                        int ddz = p.centerZ - z;
                        int d2 = ddx * ddx + ddz * ddz;
                        if (d2 <= r2) out.add(p);
                    }
                }
            }
            return out;
        }

        private static int cellX(int x) {
            return Math.floorDiv(x, CELL_SIZE);
        }

        private static int cellZ(int z) {
            return Math.floorDiv(z, CELL_SIZE);
        }

        private static long cellKey(int cx, int cz) {
            return (((long) cx) << 32) ^ (cz & 0xffffffffL);
        }
    }

    /**
     * Very small primitive-key map for dedup without String keys.
     * Open addressing, linear probing. Not general-purpose.
     */
    private static final class LongHashMap<V> {
        private static final long EMPTY = 0L;

        private long[] keys;
        private V[] vals;
        private int size;
        private int mask;
        private int maxFill;

        LongHashMap(int expected) {
            int cap = 1;
            while (cap < expected * 2) cap <<= 1;
            keys = new long[cap];
            // We store values in a generic array; this cast is safe because we only ever insert V.
            @SuppressWarnings("unchecked")
            V[] tmp = (V[]) new Object[cap];
            vals = tmp;
            mask = cap - 1;
            maxFill = (int) (cap * 0.65);
        }

        void putIfAbsent(long key, V val) {
            // Avoid EMPTY sentinel collision by remapping 0 to a mixed value.
            if (key == EMPTY) key = 0x9e3779b97f4a7c15L;

            if (size >= maxFill) rehash(keys.length << 1);

            int pos = (int) mix32(key) & mask;
            while (true) {
                long k = keys[pos];
                if (k == EMPTY) {
                    keys[pos] = key;
                    vals[pos] = val;
                    size++;
                    return;
                }
                if (k == key) {
                    return; // already present
                }
                pos = (pos + 1) & mask;
            }
        }

        ArrayList<V> values() {
            ArrayList<V> out = new ArrayList<>(size);
            for (V v : vals) {
                if (v != null) out.add(v);
            }
            return out;
        }

        private void rehash(int newCap) {
            long[] oldK = keys;
            V[] oldV = vals;

            keys = new long[newCap];
            // We store values in a generic array; this cast is safe because we only ever insert V.
            @SuppressWarnings("unchecked")
            V[] tmp = (V[]) new Object[newCap];
            vals = tmp;
            mask = newCap - 1;
            maxFill = (int) (newCap * 0.65);
            size = 0;

            for (int i = 0; i < oldK.length; i++) {
                long k = oldK[i];
                V v = oldV[i];
                if (k != EMPTY && v != null) {
                    int pos = (int) mix32(k) & mask;
                    while (keys[pos] != EMPTY) {
                        pos = (pos + 1) & mask;
                    }
                    keys[pos] = k;
                    vals[pos] = v;
                    size++;
                }
            }
        }

        private static int mix32(long z) {
            z ^= (z >>> 33);
            z *= 0xff51afd7ed558ccdL;
            z ^= (z >>> 33);
            z *= 0xc4ceb9fe1a85ec53L;
            z ^= (z >>> 33);
            return (int) z;
        }
    }
}