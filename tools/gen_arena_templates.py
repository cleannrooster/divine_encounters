"""Generate the two arena structure templates as real Minecraft .nbt files.

Run from the project root:  python tools/gen_arena_templates.py

These are ordinary structure templates. Open one with a structure block in game, edit it, and
save over the file — nothing here is baked into Java. Only the *seed* geometry is scripted,
because the alternative was shipping no cage at all.

What the code does depend on is the data markers: structure blocks in DATA mode whose metadata
strings the encounter reads back out.

  stage_<n>_<name>   choreographs the assembly. Every block joins the stage of its nearest
                     marker, so moving one re-sequences that region with no code change.
  arena_centre       the middle of the fightable space; also where an unresolved boss parks.
  boss_spawn         where the boss enters.
  perch_<name>       elevated spots Malice can occupy and dive from.
  hazard_<name>      cage sections that can participate in arena hazards.
  echo_<name>        peripheral spots for false silhouettes.

Format reference: a structure .nbt is gzipped NBT containing size, palette, blocks and entities.
Writing it directly avoids any dependency on running the game to author a template.
"""
import gzip
import os
import struct

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "common", "src", "main", "resources",
                       "data", "divine_encounters", "structure")

# 1.21.1
DATA_VERSION = 3955

# --- minimal NBT writer -------------------------------------------------------------------
TAG_END, TAG_INT, TAG_STRING, TAG_LIST, TAG_COMPOUND = 0, 3, 8, 9, 10


def _name(s):
    raw = s.encode("utf-8")
    return struct.pack(">H", len(raw)) + raw


def _int(v):
    return struct.pack(">i", v)


def w_string(v):
    raw = v.encode("utf-8")
    return struct.pack(">H", len(raw)) + raw


def w_list(tag_id, items):
    return struct.pack(">bi", tag_id, len(items)) + b"".join(items)


def w_compound(pairs):
    """pairs: list of (tag_id, name, payload-bytes)."""
    out = b""
    for tag_id, name, payload in pairs:
        out += struct.pack(">b", tag_id) + _name(name) + payload
    return out + struct.pack(">b", TAG_END)


def int_list(values):
    return w_list(TAG_INT, [_int(v) for v in values])


def block_state(name, properties=None):
    pairs = [(TAG_STRING, "Name", w_string(name))]
    if properties:
        pairs.insert(0, (TAG_COMPOUND, "Properties", w_compound(
            [(TAG_STRING, k, w_string(v)) for k, v in properties.items()])))
    return w_compound(pairs)


def block_entry(pos, state_index, nbt=None):
    pairs = [(TAG_LIST, "pos", int_list(list(pos))),
             (TAG_INT, "state", _int(state_index))]
    if nbt is not None:
        pairs.insert(0, (TAG_COMPOUND, "nbt", nbt))
    return w_compound(pairs)


def data_marker_nbt(metadata):
    """A structure block in DATA mode. This is how authored metadata rides in a template."""
    return w_compound([
        (TAG_STRING, "id", w_string("minecraft:structure_block")),
        (TAG_STRING, "mode", w_string("DATA")),
        (TAG_STRING, "metadata", w_string(metadata)),
        (TAG_STRING, "name", w_string("")),
        (TAG_STRING, "author", w_string("divine_encounters")),
    ])


def write_template(path, size, palette, blocks):
    root = w_compound([
        (TAG_INT, "DataVersion", _int(DATA_VERSION)),
        (TAG_LIST, "size", int_list(list(size))),
        (TAG_LIST, "palette", w_list(TAG_COMPOUND, palette)),
        (TAG_LIST, "blocks", w_list(TAG_COMPOUND, blocks)),
        (TAG_LIST, "entities", w_list(TAG_COMPOUND, [])),
    ])
    # The file's root is an unnamed compound.
    payload = struct.pack(">b", TAG_COMPOUND) + _name("") + root
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with gzip.open(path, "wb") as f:
        f.write(payload)
    print("wrote", os.path.basename(path), "-", len(blocks), "entries")


class Builder:
    """Accumulates blocks against a de-duplicated palette."""

    def __init__(self, size):
        self.size = size
        self.palette = []
        self.index = {}
        self.blocks = []
        self.taken = set()
        self._entry_at = {}
        self.marker_positions = set()

    def state(self, name, properties=None):
        key = (name, tuple(sorted((properties or {}).items())))
        if key not in self.index:
            self.index[key] = len(self.palette)
            self.palette.append(block_state(name, properties))
        return self.index[key]

    def put(self, x, y, z, name, properties=None):
        if not (0 <= x < self.size[0] and 0 <= y < self.size[1] and 0 <= z < self.size[2]):
            return
        if (x, y, z) in self.taken:
            return
        self.taken.add((x, y, z))
        entry = block_entry((x, y, z), self.state(name, properties))
        self._entry_at[(x, y, z)] = entry
        self.blocks.append(entry)

    def marker(self, x, y, z, metadata):
        # Markers take priority over geometry. A marker silently dropped because a rib happened to
        # occupy its position would break the assembly staging or lose a perch, and the symptom
        # (an arena that builds in the wrong order) is a long way from the cause.
        if (x, y, z) in self.taken:
            self.blocks = [entry for entry in self.blocks
                           if entry is not self._entry_at.get((x, y, z))]
            self._entry_at.pop((x, y, z), None)
        self.taken.add((x, y, z))
        self.marker_positions.add((x, y, z))
        entry = block_entry((x, y, z),
                            self.state("minecraft:structure_block", {"mode": "data"}),
                            data_marker_nbt(metadata))
        self._entry_at[(x, y, z)] = entry
        self.blocks.append(entry)

    # --- rasterisation -------------------------------------------------------------------
    # Circles must be sampled by ARC LENGTH, not by a fixed angle count. A fixed count gives
    # duplicates at small radii and gaps at large ones: 32 samples around a radius-14.5 ring
    # leaves 2.85 blocks between neighbours, so two of every three ring blocks never exist.
    # Sampling at two per block of arc guarantees a continuous ring at any radius; the
    # duplicates that produces are harmless, since put() de-duplicates.
    SAMPLES_PER_BLOCK = 2.0

    def ring(self, cx, cz, y, radius, name, properties=None):
        """A continuous circle of blocks at a given radius."""
        import math
        samples = max(8, int(math.ceil(2 * math.pi * radius * self.SAMPLES_PER_BLOCK)))
        for i in range(samples):
            angle = (2 * math.pi * i) / samples
            self.put(cx + int(round(math.cos(angle) * radius)), y,
                     cz + int(round(math.sin(angle) * radius)), name, properties)

    def spoke(self, cx, cz, y, angle, r_from, r_to, name, properties=None):
        """A radial line. Steps by half a block so it cannot dot."""
        import math
        steps = max(1, int(math.ceil(abs(r_to - r_from) * 2)))
        for i in range(steps + 1):
            r = r_from + (r_to - r_from) * (i / steps)
            self.put(cx + int(round(math.cos(angle) * r)), y,
                     cz + int(round(math.sin(angle) * r)), name, properties)

    def solid_positions(self):
        """Every occupied position that is not a marker — used by the seal check."""
        return {pos for pos in self.taken if pos not in self.marker_positions}

    def save(self, path):
        write_template(path, self.size, self.palette, self.blocks)


# --- Malice's cage ------------------------------------------------------------------------
# A ring of black roots and antler ribs that curls inward overhead. Porous by intent — the player
# must see fragments of forest through it — but every *ring* is continuous. Gaps between ribs are
# design; gaps within a ring were the bug.
def build_cage():
    import math
    radius = 15
    height = 13
    size = (radius * 2 + 1, height, radius * 2 + 1)
    b = Builder(size)
    cx = cz = radius

    # Ground seal: two continuous rings, two blocks tall. This is what actually stops the player
    # walking out, so it is the one part with no gaps at all.
    for y in (0, 1):
        b.ring(cx, cz, y, radius - 0.5, "minecraft:mangrove_roots")
        b.ring(cx, cz, y, radius - 1.5, "minecraft:mangrove_roots")

    # Vertical ribs, leaning inward as they rise so the cage closes overhead.
    ribs = 16
    rib_top = height - 3
    for i in range(ribs):
        angle = (2 * math.pi * i) / ribs
        for y in range(2, rib_top):
            lean = ((y - 2) / max(1, rib_top - 2)) ** 2 * 4.0
            r = radius - 0.5 - lean
            x = cx + int(round(math.cos(angle) * r))
            z = cz + int(round(math.sin(angle) * r))
            if i % 2 == 0:
                b.put(x, y, z, "minecraft:polished_basalt", {"axis": "y"})
            else:
                b.put(x, y, z, "minecraft:blackstone")

    # Crown: two continuous concentric rings joined by radial antler spokes. Previously this was
    # eight dotted spokes with nothing tying them together, which read as a broken ceiling.
    inner_r = radius - 4.5 - 3.6
    outer_r = radius - 0.5 - 4.0
    b.ring(cx, cz, rib_top, outer_r, "minecraft:polished_blackstone")
    b.ring(cx, cz, height - 2, (outer_r + inner_r) * 0.5, "minecraft:polished_blackstone")
    b.ring(cx, cz, height - 1, inner_r, "minecraft:mangrove_roots")
    for i in range(ribs):
        angle = (2 * math.pi * i) / ribs
        b.spoke(cx, cz, rib_top, angle, outer_r, (outer_r + inner_r) * 0.5,
                "minecraft:polished_blackstone")
        if i % 2 == 0:
            b.spoke(cx, cz, height - 2, angle, (outer_r + inner_r) * 0.5, inner_r,
                    "minecraft:polished_blackstone")
            # Sparse inner lattice — closure without an opaque lid.
            b.spoke(cx, cz, height - 1, angle, inner_r, 1.0, "minecraft:mangrove_roots")

    # --- authored markers ---
    b.marker(cx, 1, cz, "arena_centre")
    b.marker(cx + 3, 1, cz + 3, "boss_spawn")
    # Staging: roots, ribs, upper walls, canopy. Nearest-marker assignment means these four
    # positions choreograph the whole assembly.
    b.marker(cx, 0, cz + radius - 3, "stage_0_roots")
    b.marker(cx + radius - 3, 4, cz, "stage_1_ribs")
    b.marker(cx - radius + 3, 8, cz, "stage_2_walls")
    b.marker(cx, height - 1, cz, "stage_3_canopy")

    for idx, angle_deg in enumerate((45, 135, 225, 315)):
        angle = math.radians(angle_deg)
        r = radius - 5
        b.marker(cx + int(round(math.cos(angle) * r)), height - 4,
                 cz + int(round(math.sin(angle) * r)), "perch_%d" % idx)
    for idx, angle_deg in enumerate((0, 90, 180, 270)):
        angle = math.radians(angle_deg)
        r = radius - 3
        b.marker(cx + int(round(math.cos(angle) * r)), 2,
                 cz + int(round(math.sin(angle) * r)), "hazard_%d" % idx)
        b.marker(cx + int(round(math.cos(angle) * r)), 1,
                 cz + int(round(math.sin(angle) * r)), "echo_%d" % idx)

    verify_ring_continuity(b, cx, cz, [0, 1], radius, "malice_cage ground seal")
    b.save(os.path.join(OUT_DIR, "malice_cage.nbt"))


# --- War's tower --------------------------------------------------------------------------
# An open colonnade rather than a room: a raised fighting floor ringed by pillars, so a flying
# boss has vertical space and the player can still see out.
def build_tower():
    import math
    radius = 21
    height = 18
    size = (radius * 2 + 1, height, radius * 2 + 1)
    b = Builder(size)
    cx = cz = radius

    # Raised platform edge, leaving the middle as natural ground.
    for y in (0, 1):
        b.ring(cx, cz, y, radius - 1, "minecraft:polished_diorite")
        b.ring(cx, cz, y, radius - 2, "minecraft:polished_diorite")

    pillars = 12
    pillar_r = radius - 2
    crown = height - 3
    for i in range(pillars):
        angle = (2 * math.pi * i) / pillars
        x = cx + int(round(math.cos(angle) * pillar_r))
        z = cz + int(round(math.sin(angle) * pillar_r))
        for y in range(2, crown):
            b.put(x, y, z, "minecraft:quartz_pillar", {"axis": "y"})
        b.put(x, crown, z, "minecraft:chiseled_quartz_block")

    # A continuous architrave joining the pillar tops. This was previously 24 detached stubs,
    # which is what read as a missing ceiling ring.
    b.ring(cx, cz, crown + 1, pillar_r, "minecraft:smooth_quartz")
    b.ring(cx, cz, crown + 2, pillar_r - 1, "minecraft:smooth_quartz")

    b.marker(cx, 2, cz, "arena_centre")
    b.marker(cx, 2, cz - 6, "boss_spawn")
    for idx, angle_deg in enumerate((0, 90, 180, 270)):
        angle = math.radians(angle_deg)
        b.marker(cx + int(round(math.cos(angle) * (radius - 4))), 2,
                 cz + int(round(math.sin(angle) * (radius - 4))), "echo_%d" % idx)

    verify_ring_continuity(b, cx, cz, [0, 1], radius, "war_tower platform edge")
    b.save(os.path.join(OUT_DIR, "war_tower.nbt"))


def verify_ring_continuity(builder, cx, cz, levels, radius, label):
    """Fail loudly if a sealing ring has holes.

    Walks outward from the centre along many rays and checks each one hits a solid block before
    leaving the footprint. This is the property the encounter actually depends on — a cage with a
    gap does not cage anything — and it is exactly what a fixed-angle-count rasterisation
    silently broke.
    """
    import math
    solid = builder.solid_positions()
    rays = 360
    holes = []
    for level in levels:
        for i in range(rays):
            angle = (2 * math.pi * i) / rays
            hit = False
            r = 1.0
            while r <= radius:
                x = cx + int(round(math.cos(angle) * r))
                z = cz + int(round(math.sin(angle) * r))
                if (x, level, z) in solid:
                    hit = True
                    break
                r += 0.5
            if not hit:
                holes.append((level, round(math.degrees(angle))))
    if holes:
        sample = ", ".join("y=%d@%ddeg" % h for h in holes[:6])
        raise SystemExit("SEAL CHECK FAILED for %s: %d gaps (%s ...)"
                         % (label, len(holes), sample))
    print("  seal check ok:", label, "- %d rays x %d levels, no gaps" % (rays, len(levels)))


build_cage()
build_tower()
print("templates written to", OUT_DIR)
