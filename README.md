
# DoubleTripleQuadMonumentFinder

DoubleTripleQuadMonumentFinder is a **high-performance Minecraft Java Edition Double/Triple/Quad Ocean Monument finder** designed for **very large search ranges**. This tool is optimized for **speed, parallelism, and correctness**.

**Small Quirk: I will be using the terms _Ocean Monument_ and _Monument_ interchangeably.**

---

## Why This Tool?

Typically, players build an endgame portal-based Guardian farm, which is super fast. They usually require having an AFK point. But sometimes there are other neighboring monuments within 128 block radius, so players can take advantage of building 2, 3, or 4 same farms to increase ~2x, ~3x, or ~4x the rates respectively (the reason why roughly is because an AFK point does not cover all of the full monuments). This tool answers a more practical question:

> Given my seed and specific search ranges, does my world have double, triple, or quad monuments? If so, what are the AFK points?

As a reference, **[cubiomes-viewer](https://github.com/Cubitect/cubiomes-viewer)** includes an option to search for AFK spots that contain **quad monuments**. This project focuses on the same idea, but also supports searching for **double** and **triple** monument AFK spots, which can be useful when a seed does not absolutely have quad monuments in the entire world.

**Unfortunately, this tool does not include calculating seeds that have double/triple/quad monuments with a specified range, similar to Cubiomes.**

---

## Empirical Analysis

- A region is a 512 x 512 block area. Each region contains at most one ocean monument.

**Given a 1M x 1M area:**

- There is **between ~303,532 and ~384,639 ocean monuments**.

  - This means the probability of having an ocean monument in a region is **between ~7.957% and ~10.083%**.

- However, **between ~294,068 and ~378,318** of them are isolated, which means the tool prunes **between ~96.882% and ~98.330%** of ocean monuments. **To further clarify, this statistic means that given an ocean monument $\ O_1=(x_1, z_1)$ (ignoring y value), if the distance between that and the closest one, $\ O_c=(x_c, z_c)$, is more than 256 blocks, then it cannot be used for identifying double, triple, and quad monuments. This is because monsters never spawn outside of 128 block radius (spherical), so $\ O_1$ is eliminated from the monuments list.**
  - Intuitively, an ocean monument region whose neighboring regions (North, Northwest, West, Southwest, South, Southeast, East, and Northeast) contain 0 monuments is eliminated.
  - This means the probability that $\ O_1$ pairs with $\ O_c$ within 256 blocks is **between ~1.671% and ~3.218%.**
- This leaves **between ~6,321 and ~9,464** monuments capable of creating double monuments.
- **LIMITATION:** the reduction between double to triple monuments and triple to quad monuments is not observed.

### Type Guarantees

In a 59,999,968 x 59,999,968 world (taking account with world borders in vanilla survival):
- You are guaranteed to get at least one AFK point with double monuments if you search a square radius of **12,500 blocks** (meaning searching a 25,000 x 25,000 square with endpoints (-12,500, -12,500), (-12,500, 12,500), (12,500, -12,500), and (12,500, 12,500)). 
- You are guaranteed to get at least one AFK point with triple monuments if you search a square radius of **12,500,000 blocks** (meaning searching a 25,000,000 x 25,000,000 square with endpoints (-12,500,000, -12,500,000), (-12,500,000, 12,500,000), (12,500,000, -12,500,000), and (12,500,000, 12,500,000)). 
	- Keep in mind that most of the times they will be more than 100,000 blocks away from the origin, even in the Nether. So I recommend building player cannons that can quickly travel at long distances.
- Getting a quad monument in a random seed throughout the entire world is almost always impossible.


---

## How DoubleTripleQuadMonumentFinder Works

1. 

---

## Minecraft Version Compatibility (1.18+)

This tool works on version 1.18+.

---

## Requirements

To reduce any potential errors such as `command not found`, download the required tools.

- [Java **17+**](https://www.oracle.com/java/technologies/downloads/) (required)

**If you download a Release ZIP (recommended):**

- No additional tools needed

**If you build from source (developers):**

- **[Git](https://git-scm.com/install/windows)** (required for cloning the repository and submodules)

- **[Clang](https://winlibs.com)** (required to compile `libcubiomeswrap.dylib`)
	- I recommend watching this [video](https://youtu.be/4Ob_w1yDd6M?si=6N6c6BzGkQ6dycOm) on installing Clang. You will need to configure the `PATH` variable to execute Clang.

---

## Build (Development Version)

Currently, you must compile the native cubiomes wrapper manually (macOS). This step is highly recommended because without it, you will get results that do not have ocean monuments (very inaccurate).

### Compile `libcubiomeswrap.dylib` (macOS)

This project uses a small JNI shim (`native/cubiomeswrap_jni.c`) compiled together with the **cubiomes** source files.

1) Ensure you have a Java 17+ JDK installed and set `JAVA_HOME`:

```bash
export JAVA_HOME=$(/usr/libexec/java_home)
```  

2) Clone cubiomes into `external/`:

```bash
mkdir -p external
cd external
git clone https://github.com/Cubitect/cubiomes.git
cd ..
```

3) Point `CUBIOMES_DIR` to the cubiomes source folder (the one containing `generator.c`, `biomes.c`, etc.):

```bash
export CUBIOMES_DIR="$PWD/external/cubiomes"
```

4) Compile the JNI wrapper + cubiomes sources into a macOS dynamic library:

```bash
clang -dynamiclib -O2 -fPIC \
-I"$JAVA_HOME/include" \
-I"$JAVA_HOME/include/darwin" \
-I"$CUBIOMES_DIR" \
-o libcubiomeswrap.dylib \
native/cubiomeswrap_jni.c \
"$CUBIOMES_DIR"/biomenoise.c \
"$CUBIOMES_DIR"/biomes.c \
"$CUBIOMES_DIR"/finders.c \
"$CUBIOMES_DIR"/generator.c \
"$CUBIOMES_DIR"/layers.c \
"$CUBIOMES_DIR"/noise.c \
"$CUBIOMES_DIR"/util.c \
-lm
```

5) Verify the JNI symbols are present (you should see `Java_OceanMonumentCoords_...`):

```bash
nm -gU libcubiomeswrap.dylib | grep -i Java_
```

If you see something like this:

```bash
0000000000000500 T _Java_OceanMonumentCoords_00024CubiomesSupport_00024CubiomesHandle_c_1create
00000000000007c0 T _Java_OceanMonumentCoords_00024CubiomesSupport_00024CubiomesHandle_c_1free
0000000000000584 T _Java_OceanMonumentCoords_00024CubiomesSupport_00024CubiomesHandle_c_1isViableMonument
00000000000005c4 T _Java_OceanMonumentCoords_00024CubiomesSupport_00024CubiomesHandle_c_1isViableMonumentBatch
```

This means the library did export the JNI entrypoints. Otherwise, Java will fail with `UnsatisfiedLinkError`.

Verify that `libcubiomeswrap.dylib` exists by checking the folder. If it is present, then you created Cubiomes oracle.

---

## Running DoubleTripleQuadMonumentFinder

---

## Output

### Calculating Portal Coordinates

**Warning:** the AFK points are _not_ the exact coordinates where the portals from guardian farms lead to. If the AFK point reports `(x,z)` in the overworld and nether, then they are _not_ portal coordinates that lead to the killing chamber. If you build the portal for the Nether without testing, then the guardians will not teleport to a designated location (you can still make separate killing chambers, but it is inefficient).

---

## Xaero’s Minimap Waypoint Export

---

## Sample Screenshots:

---

## FAQs and Troubleshooting:

1. **Does this work on Bedrock Edition:** Unfortunately, no. The code that identifies ocean monuments is completely different from Java.
2. **Can this locate sponge rooms:** No. However, you can look at [here](https://github.com/BrianBLee1201/SpongeAnalyzer), which I created a tool for you.