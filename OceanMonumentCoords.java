import java.util.*;
import java.util.concurrent.*;
import java.time.LocalTime;

public class OceanMonumentCoords {

    public static final class MonumentPos {
        // Monument center in blocks (X/Z). The offset convention is controlled by the
        // system property: -Dmonuments.centerOffset (default 0; set to 8 for old behavior).
        public final int centerX;
        public final int centerZ;

        public MonumentPos(int centerX, int centerZ) {
            this.centerX = centerX;
            this.centerZ = centerZ;
        }
    }

    private final long seed;
    private final int threads;
    private final MonumentAlgorithm algorithm;

    public OceanMonumentCoords(long seed, int threads) {
        this(seed, threads, new MonumentAlgorithm118Plus(CubiomesSupport.tryCreateValidatorOrNull(seed)));
    }

    public OceanMonumentCoords(long seed, int threads, MonumentAlgorithm algorithm) {
        this.seed = seed;
        this.threads = Math.max(1, threads);
        this.algorithm = Objects.requireNonNull(algorithm, "algorithm");
    }

    /**
     * Finds all ocean monument centers within [-rangeBlocks, +rangeBlocks] in X/Z.
     */
    public List<MonumentPos> findMonumentsInRange(int rangeBlocks, int excludeBlocks, int k)
            throws InterruptedException {
        if (rangeBlocks < 0) {
            throw new IllegalArgumentException("rangeBlocks must be >= 0");
        }
        if (excludeBlocks < 0) {
            throw new IllegalArgumentException("excludeBlocks must be >= 0");
        }
        if (excludeBlocks > rangeBlocks) {
            throw new IllegalArgumentException(
                    "excludeBlocks (" + excludeBlocks + ") must be <= rangeBlocks (" + rangeBlocks + ")");
        }

        int minChunk = floorDiv(-rangeBlocks, 16);
        int maxChunk = floorDiv(+rangeBlocks, 16);

        // Inner exclusion square radius in chunks (Chebyshev distance in chunk coords)
        int excludeChunks = (excludeBlocks <= 0) ? 0 : floorDiv(excludeBlocks, 16);

        // The algorithm usually works in "structure regions" (spacing grid).
        // We expose the chunk range and let the strategy decide how to scan.
        return algorithm.find(seed, minChunk, maxChunk, excludeChunks, k, threads);
    }

    /**
     * Strategy interface: swap in your real monument-placement logic here.
     */
    public interface MonumentAlgorithm {
        /**
         * @param excludeChunks Inner square radius (Chebyshev in chunk coords) to skip;
         *                      0 disables.
         */
        List<MonumentPos> find(long seed, int minChunk, int maxChunk, int excludeChunks, int k, int threads)
                throws InterruptedException;
    }

    /**
     * Optional validator (e.g., biome checks) to eliminate false positives.
     * If null, all candidate monument starts are accepted.
     */
    public interface MonumentValidator {
        boolean isValidMonumentChunk(int chunkX, int chunkZ);
    }

    /**
     * Optional batch validator to drastically reduce JNI overhead.
     * Implementations should fill outFlags[i] = 1 if (chunkXs[i], chunkZs[i]) is
     * valid, else 0.
     */
    public interface BatchMonumentValidator extends MonumentValidator {
        void isValidMonumentChunks(int[] chunkXs, int[] chunkZs, byte[] outFlags, int n);
    }

    /**
     * Optional cubiomes-backed biome/viability validation.
     *
     * Why this exists:
     * - Your Java region-placement logic finds *candidate* monument start chunks.
     * - Minecraft then applies biome/viability checks.
     * - Running those checks accurately offline is hard in pure Java.
     *
     * Cubiomes (https://github.com/Cubitect/cubiomes) can do those checks offline.
     * We keep Java clean by delegating the viability check to a tiny native shim.
     *
     * If the native library is not present (or fails to load), we gracefully fall
     * back
     * to returning null => no validation (more false positives, but still correct
     * candidates).
     */
    public static final class CubiomesSupport {
        private CubiomesSupport() {
        }

        /**
         * Default target: MC 1.18+ (you can extend the native shim to support other
         * versions).
         *
         * NOTE: Biome generation has changed across 1.18/1.19/1.20/1.21.
         * For best accuracy, compile the native shim against cubiomes and have it map
         * this enum
         * to the correct MC_* constant.
         */
        public enum McVersion {
            MC_1_18,
            MC_1_19,
            MC_1_20,
            MC_1_21
        }

        /**
         * Try to create a cubiomes-backed validator.
         * Returns null if cubiomes is unavailable, so callers can fall back safely.
         */
        public static MonumentValidator tryCreateValidatorOrNull(long worldSeed) {
            return tryCreateValidatorOrNull(worldSeed, McVersion.MC_1_18);
        }

        public static MonumentValidator tryCreateValidatorOrNull(long worldSeed, McVersion mcVersion) {
            try {
                CubiomesHandle h = CubiomesHandle.create(worldSeed, mcVersion);
                return new CubiomesMonumentValidator(h);
            } catch (Throwable t) {
                // Intentionally silent by default to keep CLI output clean.
                // If you want debugging, set -Dmonuments.cubiomes.debug=true
                System.err.println("[cubiomes] disabled: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                // if (Boolean.getBoolean("monuments.cubiomes.debug")) {
                // System.err.println("[cubiomes] disabled: " + t.getClass().getSimpleName() +
                // ": " + t.getMessage());
                // }
                return null;
            }
        }

        /**
         * A validator that delegates viability checks to cubiomes.
         */
        private static final class CubiomesMonumentValidator implements BatchMonumentValidator {
            private final CubiomesHandle handle;

            CubiomesMonumentValidator(CubiomesHandle handle) {
                this.handle = Objects.requireNonNull(handle, "handle");
            }

            @Override
            public boolean isValidMonumentChunk(int chunkX, int chunkZ) {
                return handle.isViableMonumentChunk(chunkX, chunkZ);
            }

            @Override
            public void isValidMonumentChunks(int[] chunkXs, int[] chunkZs, byte[] outFlags, int n) {
                handle.isViableMonumentChunks(chunkXs, chunkZs, outFlags, n);
            }
        }

        /**
         * Thin JNI bridge.
         *
         * You provide a native library (e.g. libcubiomeswrap.{dll,so,dylib}) that
         * implements:
         * - long c_create(long seed, int mcVersionOrdinal)
         * - int c_isViableMonument(long handle, int chunkX, int chunkZ)
         * - void c_isViableMonumentBatch(long handle, int[] chunkXs, int[] chunkZs,
         * byte[] outFlags)
         * - void c_free(long handle)
         *
         * The native side should:
         * - setupGenerator(&g, MC_1_18, 0) (or version switch)
         * - applySeed(&g, DIM_OVERWORLD, seed)
         * - call isViableStructurePos(Monument, &g, chunkX, chunkZ, 0)
         */
        private static final class CubiomesHandle implements AutoCloseable {
            private static volatile boolean LOADED = false;

            private final long ptr;

            private CubiomesHandle(long ptr) {
                if (ptr == 0)
                    throw new IllegalStateException("cubiomes handle is null");
                this.ptr = ptr;
            }

            static CubiomesHandle create(long seed, McVersion mcVersion) {
                loadNativeOnce();
                long p = c_create(seed, mcVersion.ordinal());
                return new CubiomesHandle(p);
            }

            boolean isViableMonumentChunk(int chunkX, int chunkZ) {
                // The cubiomes API expects structure positions in chunk coordinates for most
                // structures.
                return c_isViableMonument(ptr, chunkX, chunkZ) != 0;
            }

            void isViableMonumentChunks(int[] chunkXs, int[] chunkZs, byte[] outFlags, int n) {
                if (n <= 0)
                    return;
                c_isViableMonumentBatch(ptr, chunkXs, chunkZs, outFlags);
            }

            @Override
            public void close() {
                c_free(ptr);
            }

            private static void loadNativeOnce() {
                if (LOADED)
                    return;
                synchronized (CubiomesHandle.class) {
                    if (LOADED)
                        return;

                    // IMPORTANT: System.loadLibrary() expects the *base* name.
                    // On macOS, loadLibrary("cubiomeswrap") resolves to "libcubiomeswrap.dylib".
                    // Do NOT include the "lib" prefix in the base name.
                    List<String> baseNames = Arrays.asList(
                            "cubiomeswrap",
                            "cubiomes_wrap");

                    UnsatisfiedLinkError last = null;

                    // 1) Try normal library resolution (java.library.path, DYLD paths, etc.)
                    for (String n : baseNames) {
                        try {
                            System.loadLibrary(n);
                            LOADED = true;
                            return;
                        } catch (UnsatisfiedLinkError e) {
                            last = e;
                        }
                    }

                    // 2) Fallback: if the dylib sits next to the jar / in the current working
                    // directory.
                    // This is your most common dev setup: ./libcubiomeswrap.dylib
                    try {
                        String cwd = System.getProperty("user.dir");
                        String p = new java.io.File(cwd, "libcubiomeswrap.dylib").getAbsolutePath();
                        System.load(p);
                        LOADED = true;
                        return;
                    } catch (UnsatisfiedLinkError e) {
                        last = e;
                    }

                    // If nothing worked, throw the last link error.
                    if (last != null)
                        throw last;
                }
            }

            // JNI entrypoints (implemented by your native shim)
            private static native long c_create(long seed, int mcVersionOrdinal);

            private static native int c_isViableMonument(long handle, int chunkX, int chunkZ);

            private static native void c_isViableMonumentBatch(long handle, int[] chunkXs, int[] chunkZs,
                    byte[] outFlags);

            private static native void c_free(long handle);
        }
    }

    /**
     * 1.18+ Ocean Monument placement (offline):
     *
     * This implements the same region-based coordinate selection as Amidst's
     * RegionalStructureProducer with:
     * SALT = 10387313L
     * SPACING = 32 chunks
     * SEPARATION = 5 chunks
     * TRIANGULAR = true
     *
     * IMPORTANT:
     * - This generates candidate monument START chunks.
     * - To avoid false positives/negatives, plug in a MonumentValidator that
     * performs biome checks (deep ocean + surrounding ocean checks).
     */
    public static final class MonumentAlgorithm118Plus implements MonumentAlgorithm {
        private static final long SALT = 10387313L;
        private static final int SPACING = 32; // chunks
        private static final int SEPARATION = 5; // chunks
        private static final boolean IS_TRIANGULAR = true;

        // These match Amidst's hard-coded constants.
        private static final long MAGIC_NUMBER_1 = 341873128712L;
        private static final long MAGIC_NUMBER_2 = 132897987541L;

        // 1.18+ uses the fixed (non-buggy) negative coordinate math.
        private static final boolean BUGGY_STRUCTURE_COORD_MATH = false;

        private final MonumentValidator validator;

        // Pack two chunk coordinates into one long to avoid per-result object
        // allocation.
        // High 32 bits: chunkX, low 32 bits: chunkZ.
        private static long packChunk(int chunkX, int chunkZ) {
            return (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
        }

        private static int unpackChunkX(long packed) {
            return (int) (packed >> 32);
        }

        private static int unpackChunkZ(long packed) {
            return (int) packed;
        }

        // Pruning logic:
        // Any AFK spot must be within 128 blocks of each monument, so any two monuments
        // that can share
        // an AFK spot must be within 256 blocks of each other.
        // Region size is 32 chunks = 512 blocks, so any such pair must lie in the same
        // region or an adjacent region.
        private static final int MAX_PAIRWISE_BLOCKS = Integer.getInteger("monuments.pairwiseBlocks", 256);
        private static final long MAX_PAIRWISE2 = (long) MAX_PAIRWISE_BLOCKS * (long) MAX_PAIRWISE_BLOCKS;

        // Debug: print at most N isolated monuments that were pruned.
        // Override with: -Dmonuments.debugExcludedLimit=0 (disable) or a larger number.
        private static final int DEBUG_EXCLUDED_LIMIT = Integer.getInteger("monuments.debugExcludedLimit", 20);

        private static final class Column {
            final int regionX;
            final int minRegionZ;
            final int len;
            final int[] chunkX; // indexed by (rz - minRegionZ)
            final int[] chunkZ; // indexed by (rz - minRegionZ)
            final byte[] present; // 1 if viable monument exists at that regionZ

            Column(int regionX, int minRegionZ, int len) {
                this.regionX = regionX;
                this.minRegionZ = minRegionZ;
                this.len = len;
                this.chunkX = new int[len];
                this.chunkZ = new int[len];
                this.present = new byte[len];
            }

            boolean hasAt(int regionZ) {
                int idx = regionZ - minRegionZ;
                return idx >= 0 && idx < len && present[idx] != 0;
            }

            int getChunkXAt(int regionZ) {
                return chunkX[regionZ - minRegionZ];
            }

            int getChunkZAt(int regionZ) {
                return chunkZ[regionZ - minRegionZ];
            }

            void setAt(int regionZ, int cx, int cz) {
                int idx = regionZ - minRegionZ;
                present[idx] = 1;
                chunkX[idx] = cx;
                chunkZ[idx] = cz;
            }
        }

        private static final class LongBuilder {
            private long[] a;
            private int size;

            LongBuilder(int initialCapacity) {
                this.a = new long[Math.max(8, initialCapacity)];
            }

            void add(long v) {
                int s = size;
                if (s == a.length) {
                    a = Arrays.copyOf(a, a.length + (a.length >> 1) + 16);
                }
                a[s] = v;
                size = s + 1;
            }

            long[] toArrayTrimmed() {
                return Arrays.copyOf(a, size);
            }

            int size() {
                return size;
            }
        }

        public MonumentAlgorithm118Plus(MonumentValidator validator) {
            this.validator = validator;
        }

        @Override
        public List<MonumentPos> find(long seed, int minChunk, int maxChunk, int excludeChunks, int k, int threads)
                throws InterruptedException {
            // Convert chunk-range to region-range.
            final int minRegionX = floorDiv(getModifiedCoord(minChunk), SPACING);
            final int maxRegionX = floorDiv(getModifiedCoord(maxChunk), SPACING);
            final int minRegionZ = floorDiv(getModifiedCoord(minChunk), SPACING);
            final int maxRegionZ = floorDiv(getModifiedCoord(maxChunk), SPACING);
            final int regionZLen = (maxRegionZ - minRegionZ + 1);

            final boolean keepAll = Boolean.getBoolean("monuments.keepAll");
            if (keepAll) {
                System.out.println(
                        "[WARN] monuments.keepAll=true: disables pruning and can be very slow / memory-heavy.");
            }

            // Including filtering logic here for simplicity since it's easier to prune
            // early.

            if (k < 1 || k > 4) {
                throw new IllegalArgumentException("k must be 1..4 (got " + k + ")");
            }

            // For double/triple/quad, each monument must have at least (k-1) neighbors
            // nearby.
            // (This is a necessary condition; not sufficient, but great for pruning.)
            final int requiredNeighbors = (k <= 1) ? 0 : (k - 1);

            // === STEP A: placement-only columns + pairability prefilter (NO cubiomes here)
            // ===
            ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
            CompletionService<Column> cs = new ExecutorCompletionService<>(pool);
            try {
                final int inflightLimit = Math.max(8, threads * 4);
                final int[] nextToSubmit = new int[] { minRegionX };
                final int[] inflight = new int[] { 0 };

                HashMap<Integer, Column> ready = new HashMap<>();

                Runnable submitMore = () -> {
                    while (inflight[0] < inflightLimit && nextToSubmit[0] <= maxRegionX) {
                        final int rx = nextToSubmit[0];
                        cs.submit(new Callable<Column>() {
                            @Override
                            public Column call() {
                                return computeColumnPlacementOnly(seed, rx, minRegionZ, maxRegionZ, regionZLen,
                                        minChunk, maxChunk);
                            }
                        });
                        nextToSubmit[0]++;
                        inflight[0]++;
                    }
                };

                java.util.function.IntFunction<Column> awaitColumn = (int rx) -> {
                    Column c = ready.remove(rx);
                    while (c == null) {
                        try {
                            Future<Column> f = cs.take();
                            Column got = f.get();
                            inflight[0]--;
                            if (got.regionX == rx) {
                                c = got;
                            } else {
                                ready.put(got.regionX, got);
                            }
                            submitMore.run();
                        } catch (ExecutionException e) {
                            throw new RuntimeException(e.getCause());
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(ie);
                        }
                    }
                    return c;
                };

                submitMore.run();
                System.out.println("[" + LocalTime.now()
                        + "] [INFO] Step A: submitted placement-only column tasks (bounded inflight=" + inflightLimit
                        + ").");

                long totalCandidates = 0;
                long totalExcludedA = 0;
                int printedExcludedA = 0;

                LongBuilder pairable = new LongBuilder(1 << 16);

                Column prev = null;
                Column curr = awaitColumn.apply(minRegionX);
                Column next = (minRegionX < maxRegionX) ? awaitColumn.apply(minRegionX + 1) : null;

                totalCandidates += countPresent(curr);
                if (next != null)
                    totalCandidates += countPresent(next);

                final int logEvery = Integer.getInteger("monuments.progressEvery", 250);
                int processed = 0;
                int totalColumns = (maxRegionX - minRegionX + 1);

                for (int rx = minRegionX; rx <= maxRegionX; rx++) {
                    if (rx + 1 <= maxRegionX) {
                        if (next == null || next.regionX != rx + 1) {
                            next = awaitColumn.apply(rx + 1);
                            totalCandidates += countPresent(next);
                        }
                    } else {
                        next = null;
                    }

                    for (int rz = minRegionZ; rz <= maxRegionZ; rz++) {
                        if (!curr.hasAt(rz))
                            continue;

                        int axChunk = curr.getChunkXAt(rz);
                        int azChunk = curr.getChunkZAt(rz);

                        // Ring search: exclude inner square around origin (Chebyshev distance in chunk
                        // coords).
                        if (excludeChunks > 0) {
                            int cheb = Math.max(Math.abs(axChunk), Math.abs(azChunk));
                            if (cheb <= excludeChunks) {
                                // Skip anything fully inside the excluded inner area.
                                continue;
                            }
                        }

                        int neighborCount = 0;

                        for (int dx = -1; dx <= 1 && neighborCount < requiredNeighbors; dx++) {
                            Column col = (dx == -1) ? prev : (dx == 0 ? curr : next);
                            if (col == null)
                                continue;

                            for (int dz = -1; dz <= 1 && neighborCount < requiredNeighbors; dz++) {
                                if (dx == 0 && dz == 0)
                                    continue;
                                int nz = rz + dz;
                                if (nz < minRegionZ || nz > maxRegionZ)
                                    continue;
                                if (!col.hasAt(nz))
                                    continue;

                                int bxChunk = col.getChunkXAt(nz);
                                int bzChunk = col.getChunkZAt(nz);

                                int dxBlocks = (bxChunk - axChunk) << 4;
                                int dzBlocks = (bzChunk - azChunk) << 4;
                                long d2 = (long) dxBlocks * (long) dxBlocks + (long) dzBlocks * (long) dzBlocks;

                                if (d2 <= MAX_PAIRWISE2) {
                                    neighborCount++;
                                }
                            }
                        }

                        if (keepAll || requiredNeighbors == 0 || neighborCount >= requiredNeighbors) {
                            pairable.add(packChunk(axChunk, azChunk));
                        } else {
                            totalExcludedA++;
                            if (DEBUG_EXCLUDED_LIMIT > 0 && printedExcludedA < DEBUG_EXCLUDED_LIMIT) {
                                int bx = chunkCenterToBlock(axChunk);
                                int bz = chunkCenterToBlock(azChunk);
                                System.out.println("[DEBUG] Step A exclude (placement-only isolated) monument (" + bx
                                        + "," + bz + ") - no neighbor within " + MAX_PAIRWISE_BLOCKS + " blocks");
                                printedExcludedA++;
                            }
                        }
                    }

                    processed++;
                    if (logEvery > 0 && processed % logEvery == 0) {
                        System.out.println("[" + LocalTime.now() + "] [INFO] Step A: columns done " + processed + "/"
                                + totalColumns
                                + ", candidatesSoFar=" + totalCandidates + ", pairableSoFar=" + pairable.size()
                                + ", excludedSoFar=" + totalExcludedA);
                    }

                    prev = curr;
                    curr = next;
                }

                long[] pairablePacked = pairable.toArrayTrimmed();
                System.out.println("[" + LocalTime.now() + "] [INFO] Step A complete: candidates=" + totalCandidates
                        + ", pairable=" + pairablePacked.length + ", excluded=" + totalExcludedA + ".");

                // Step B header/progress
                if (validator != null) {
                    final int batchSizeForLog = Integer.getInteger("monuments.validateBatchSize", 10000);
                    System.out.println("[" + LocalTime.now() + "] [INFO] Step B: validating " + pairablePacked.length
                            + " candidate(s) in batches of " + batchSizeForLog + " (this can take a while)...");
                }

                // === STEP B: viability only on pairable survivors ===
                long[] viablePacked = (validator == null) ? pairablePacked
                        : validatePackedInBatches(pairablePacked, validator);
                System.out.println(
                        "[" + LocalTime.now() + "] [INFO] Step B complete: viable=" + viablePacked.length + ".");

                // === STEP C: re-prune after viability ===
                long[] finalPacked = keepAll ? viablePacked
                        : pruneIsolatedAfterViability(viablePacked, requiredNeighbors);
                System.out.println(
                        "[" + LocalTime.now() + "] [INFO] Step C complete: after re-prune=" + finalPacked.length + ".");

                ArrayList<MonumentPos> results = new ArrayList<>(finalPacked.length);
                for (long p : finalPacked) {
                    int cx = unpackChunkX(p);
                    int cz = unpackChunkZ(p);
                    results.add(new MonumentPos(chunkCenterToBlock(cx), chunkCenterToBlock(cz)));
                }
                return results;

            } finally {
                pool.shutdownNow();
                try {
                    pool.awaitTermination(5, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private static Column computeColumnPlacementOnly(
                long seed,
                int regionX,
                int minRegionZ,
                int maxRegionZ,
                int regionZLen,
                int minChunk,
                int maxChunk) {
            Column col = new Column(regionX, minRegionZ, regionZLen);

            for (int rz = minRegionZ; rz <= maxRegionZ; rz++) {
                int[] loc = getPossibleLocation(seed, regionX, rz);
                int chunkX = loc[0];
                int chunkZ = loc[1];

                // Filter to requested bounds
                if (chunkX < minChunk || chunkX > maxChunk || chunkZ < minChunk || chunkZ > maxChunk) {
                    continue;
                }

                col.setAt(rz, chunkX, chunkZ);
            }
            return col;
        }

        private static long countPresent(Column c) {
            long n = 0;
            for (int i = 0; i < c.len; i++) {
                if (c.present[i] != 0)
                    n++;
            }
            return n;
        }

        private static long[] validatePackedInBatches(long[] packed, MonumentValidator validator) {
            if (packed.length == 0)
                return packed;

            final int batchSize = Integer.getInteger("monuments.validateBatchSize", 10000);
            // Progress logging controls (set to 0 to disable)
            // Preferred: log every N items processed (independent of batch size)
            final int progressEveryItems = Integer.getInteger("monuments.validateProgressEveryItems", 100_000);
            // Back-compat: log every N batches (used only if progressEveryItems == 0)
            final int progressEveryBatches = Integer.getInteger("monuments.validateProgressEveryBatches", 1000);
            final long t0 = System.nanoTime();

            LongBuilder out = new LongBuilder(Math.min(packed.length, 1 << 16));

            int[] xs = new int[Math.min(batchSize, packed.length)];
            int[] zs = new int[Math.min(batchSize, packed.length)];
            byte[] flags = new byte[Math.min(batchSize, packed.length)];

            int pos = 0;
            int batchesDone = 0;
            final int totalBatches = (packed.length + batchSize - 1) / batchSize;
            int nextLogAt = (progressEveryItems > 0) ? Math.min(progressEveryItems, packed.length) : Integer.MAX_VALUE;

            while (pos < packed.length) {
                int n = Math.min(batchSize, packed.length - pos);

                if (xs.length < n) {
                    xs = new int[n];
                    zs = new int[n];
                    flags = new byte[n];
                }

                for (int i = 0; i < n; i++) {
                    long p = packed[pos + i];
                    xs[i] = unpackChunkX(p);
                    zs[i] = unpackChunkZ(p);
                }

                Arrays.fill(flags, 0, n, (byte) 0);

                if (validator instanceof BatchMonumentValidator) {
                    ((BatchMonumentValidator) validator).isValidMonumentChunks(xs, zs, flags, n);
                    for (int i = 0; i < n; i++) {
                        if (flags[i] != 0)
                            out.add(packChunk(xs[i], zs[i]));
                    }
                } else {
                    for (int i = 0; i < n; i++) {
                        if (validator.isValidMonumentChunk(xs[i], zs[i])) {
                            out.add(packChunk(xs[i], zs[i]));
                        }
                    }
                }
                pos += n;
                batchesDone++;

                final boolean shouldLog;
                if (progressEveryItems > 0) {
                    // Item-based logging: independent of batch size.
                    shouldLog = (pos >= nextLogAt) || (batchesDone == totalBatches);
                } else {
                    // Fallback to batch-based logging.
                    shouldLog = (progressEveryBatches > 0) && ((batchesDone % progressEveryBatches == 0) || (batchesDone == totalBatches));
                }

                if (shouldLog) {
                    long now = System.nanoTime();
                    double elapsedSec = (now - t0) / 1e9;
                    double done = Math.min(1.0, (double) pos / (double) packed.length);

                    // Rate in items/sec (avoid div-by-zero)
                    double rate = (elapsedSec <= 1e-9) ? 0.0 : (pos / elapsedSec);
                    double remaining = packed.length - pos;
                    double etaSec = (rate <= 1e-9) ? Double.POSITIVE_INFINITY : (remaining / rate);

                    String eta;
                    if (Double.isInfinite(etaSec) || etaSec > 365 * 24 * 3600) {
                        eta = "?";
                    } else {
                        long s = Math.max(0L, (long) Math.round(etaSec));
                        long h = s / 3600;
                        long m = (s % 3600) / 60;
                        long sec = s % 60;
                        if (h > 0) eta = h + "h " + m + "m " + sec + "s";
                        else if (m > 0) eta = m + "m " + sec + "s";
                        else eta = sec + "s";
                    }

                    // Print compact progress line
                    System.out.println("[" + LocalTime.now() + "] [INFO] Step B: batches " + batchesDone + "/" + totalBatches
                            + ", processed=" + pos + "/" + packed.length
                            + String.format(", %.1f%%", done * 100.0)
                            + (rate <= 0.0 ? "" : String.format(", rate=%.0f/s", rate))
                            + ", ETA=" + eta);

                    // Advance item-based log threshold (skip ahead in case we jumped by >1 interval)
                    if (progressEveryItems > 0) {
                        while (nextLogAt <= pos && nextLogAt < packed.length) {
                            nextLogAt = Math.min(packed.length, nextLogAt + progressEveryItems);
                        }
                    }
                }
            }
            return out.toArrayTrimmed();
        }

        private static long[] pruneIsolatedAfterViability(long[] packed, int requiredNeighbors) {
            if (packed.length == 0)
                return packed;

            LongLongMap map = new LongLongMap(packed.length);

            for (long p : packed) {
                int cx = unpackChunkX(p);
                int cz = unpackChunkZ(p);
                int rx = floorDiv(getModifiedCoord(cx), SPACING);
                int rz = floorDiv(getModifiedCoord(cz), SPACING);
                long key = (((long) rx) << 32) ^ (rz & 0xffffffffL);
                map.put(key, p);
            }

            LongBuilder kept = new LongBuilder(Math.max(16, packed.length / 4));
            long excluded = 0;
            int printed = 0;

            for (int i = 0; i < map.keys.length; i++) {
                long key = map.keys[i];
                if (key == LongLongMap.EMPTY)
                    continue;

                long aPacked = map.vals[i];
                int ax = unpackChunkX(aPacked);
                int az = unpackChunkZ(aPacked);

                int rx = (int) (key >> 32);
                int rz = (int) key;

                int neighborCount = 0;

                for (int dx = -1; dx <= 1 && neighborCount < requiredNeighbors; dx++) {
                    for (int dz = -1; dz <= 1 && neighborCount < requiredNeighbors; dz++) {
                        if (dx == 0 && dz == 0)
                            continue;

                        long nkey = (((long) (rx + dx)) << 32) ^ ((rz + dz) & 0xffffffffL);
                        long bPacked = map.get(nkey);
                        if (bPacked == LongLongMap.EMPTY)
                            continue;

                        int bx = unpackChunkX(bPacked);
                        int bz = unpackChunkZ(bPacked);

                        int dxBlocks = (bx - ax) << 4;
                        int dzBlocks = (bz - az) << 4;
                        long d2 = (long) dxBlocks * (long) dxBlocks + (long) dzBlocks * (long) dzBlocks;

                        if (d2 <= MAX_PAIRWISE2) {
                            neighborCount++;
                        }
                    }
                }

                if (requiredNeighbors == 0 || neighborCount >= requiredNeighbors) {
                    kept.add(aPacked);
                } else {
                    excluded++;
                    if (DEBUG_EXCLUDED_LIMIT > 0 && printed < DEBUG_EXCLUDED_LIMIT) {
                        int bx = chunkCenterToBlock(ax);
                        int bz = chunkCenterToBlock(az);
                        System.out.println("[DEBUG] Step C exclude (viable isolated) monument (" + bx + "," + bz
                                + ") - neighbors failed viability");
                        printed++;
                    }
                }
            }

            if (excluded > 0) {
                System.out.println("[INFO] Step C: excluded viable isolated monuments=" + excluded);
            }
            return kept.toArrayTrimmed();
        }

        private static final class LongLongMap {
            static final long EMPTY = 0L;

            final long[] keys;
            final long[] vals;
            final int mask;

            LongLongMap(int expected) {
                int cap = 1;
                while (cap < expected * 2)
                    cap <<= 1;
                keys = new long[cap];
                vals = new long[cap];
                mask = cap - 1;
            }

            void put(long key, long val) {
                if (key == EMPTY)
                    key = 0x9e3779b97f4a7c15L;
                int pos = (int) mix32(key) & mask;
                while (true) {
                    long k = keys[pos];
                    if (k == EMPTY) {
                        keys[pos] = key;
                        vals[pos] = val;
                        return;
                    }
                    if (k == key) {
                        vals[pos] = val;
                        return;
                    }
                    pos = (pos + 1) & mask;
                }
            }

            long get(long key) {
                if (key == EMPTY)
                    key = 0x9e3779b97f4a7c15L;
                int pos = (int) mix32(key) & mask;
                while (true) {
                    long k = keys[pos];
                    if (k == EMPTY)
                        return EMPTY;
                    if (k == key)
                        return vals[pos];
                    pos = (pos + 1) & mask;
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

        /**
         * Returns the possible structure start chunk for a region.
         * Equivalent to Amidst RegionalStructureProducer.getPossibleLocation,
         * but expressed directly in region coordinates.
         */
        private static int[] getPossibleLocation(long worldSeed, int regionX, int regionZ) {
            long regionSeed = getRegionSeed(worldSeed, regionX, regionZ);
            FastRand rand = new FastRand(regionSeed);

            int chunkX = getStructCoordInRegion(rand, regionX);
            int chunkZ = getStructCoordInRegion(rand, regionZ);
            return new int[] { chunkX, chunkZ };
        }

        private static long getRegionSeed(long worldSeed, int regionX, int regionZ) {
            return regionX * MAGIC_NUMBER_1
                    + regionZ * MAGIC_NUMBER_2
                    + worldSeed
                    + SALT;
        }

        private static int getStructCoordInRegion(FastRand random, int regionCoord) {
            int base = regionCoord * SPACING;
            int bound = SPACING - SEPARATION;

            if (IS_TRIANGULAR) {
                return base + (random.nextInt(bound) + random.nextInt(bound)) / 2;
            } else {
                return base + random.nextInt(bound);
            }
        }

        private static int getModifiedCoord(int coordinate) {
            if (coordinate < 0) {
                if (BUGGY_STRUCTURE_COORD_MATH) {
                    // Bug MC-131462
                    return coordinate - SPACING - 1;
                } else {
                    return coordinate - SPACING + 1;
                }
            }
            return coordinate;
        }

        private static int floorDiv(int a, int b) {
            return Math.floorDiv(a, b);
        }

        /**
         * Lightweight deterministic PRNG compatible with java.util.Random.
         * We implement it explicitly so results remain stable across JVMs.
         */
        private static final class FastRand {
            private static final long MULT = 0x5DEECE66DL;
            private static final long ADD = 0xBL;
            private static final long MASK = (1L << 48) - 1;

            private long seed;

            FastRand(long seed) {
                setSeed(seed);
            }

            void setSeed(long s) {
                this.seed = (s ^ MULT) & MASK;
            }

            private int next(int bits) {
                seed = (seed * MULT + ADD) & MASK;
                return (int) (seed >>> (48 - bits));
            }

            int nextInt(int bound) {
                if (bound <= 0) {
                    throw new IllegalArgumentException("bound must be positive");
                }

                // Match java.util.Random.nextInt(int bound)
                if ((bound & -bound) == bound) {
                    return (int) ((bound * (long) next(31)) >> 31);
                }

                int bits, val;
                do {
                    bits = next(31);
                    val = bits % bound;
                } while (bits - val + (bound - 1) < 0);
                return val;
            }
        }
    }

    // Helpers
    private static int floorDiv(int a, int b) {
        // Java's Math.floorDiv is fine too; keeping explicit here.
        return Math.floorDiv(a, b);
    }

    // Ocean monument “center” block coordinate convention.
    //
    // Empirically (and in many external tools), the structure start chunk maps to a block coordinate
    // that is aligned on 16 (i.e., chunk*16). Older code used chunk*16+8 (center-of-chunk).
    //
    // To keep the tool robust and allow either convention, we default to 0 and allow override:
    //   -Dmonuments.centerOffset=8
    private static final int MONUMENT_CENTER_OFFSET = Integer.getInteger("monuments.centerOffset", 0);

    public static int chunkCenterToBlock(int chunk) {
        return chunk * 16 + MONUMENT_CENTER_OFFSET;
    }
}