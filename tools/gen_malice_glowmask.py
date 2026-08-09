"""Paint Malice's glowing landmarks into her texture, and emit the matching glow stencil.

Run from the project root:  python tools/gen_malice_glowmask.py

--- HOW GECKOLIB ACTUALLY USES THE MASK ---
This is the part that is easy to get wrong, and I did at first. From GeoGlowingTextureMeta:

    int color = originalImage.getPixel(pixel.x, pixel.y);   // RGB comes from the BASE texture
    if (pixel.alpha > 0)
        color = withAlpha(pixel.alpha, ...);                // only ALPHA comes from the mask
    newImage.setPixel(pixel.x, pixel.y, color);
    originalImage.setPixel(pixel.x, pixel.y, 0);            // and the pixel is ERASED from the base

So the mask is a **stencil, not a colour source**. Its own colours are discarded entirely. The
bright colours have to live in the base texture; the mask only says which pixels are emissive.

A mask full of bright purple over a near-black base therefore renders near-black — invisible —
which is exactly what happened. It also punches transparent holes in the body wherever the mask
is opaque, so a misplaced mask shows up as holes rather than as glow.

That is why this script writes BOTH files: the landmarks go into the texture, the stencil marks
them. Re-running is idempotent; it repaints the same pixels.

--- WHERE THE LANDMARKS GO ---
Every rectangle is computed from the real cube UVs in visage_of_malice.geo.json using the
standard Bedrock box unwrap, so nothing is guessed and a re-UV can be followed by a re-run.
Originals are backed up to tools/texture_backup/ the first time this runs.
"""
import json
import math
import os
import shutil

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "common", "src", "main", "resources", "assets", "divine_encounters")
GEO = os.path.join(ASSETS, "geo", "visage_of_malice.geo.json")
TEX_DIR = os.path.join(ASSETS, "textures", "entity")
BACKUP = os.path.join(ROOT, "tools", "texture_backup")


def load_cubes():
    data = json.load(open(GEO))
    geometry = data["minecraft:geometry"][0]
    cubes = {}
    for bone in geometry["bones"]:
        if bone.get("cubes"):
            cubes[bone["name"]] = bone["cubes"]
    return cubes


def faces(cube):
    """The six face rectangles of a box, standard Bedrock unwrap.

    `north` is the -Z face — the one a player sees head-on, since entity models face north.
    Sizes are ceiled, matching how Blockbench allocates UV space for fractional cubes; using
    round() here quietly mis-places anything with a .5 dimension, such as the outer horns.
    """
    u, v = (int(round(n)) for n in cube["uv"])
    sx, sy, sz = (int(math.ceil(n)) for n in cube["size"])
    return {
        "up": (u + sz, v, sx, sz),
        "down": (u + sz + sx, v, sx, sz),
        "west": (u, v + sz, sz, sy),
        "north": (u + sz, v + sz, sx, sy),
        "east": (u + sz + sx, v + sz, sz, sy),
        "south": (u + sz + sx + sz, v + sz, sx, sy),
    }


class Landmarks:
    """Collects the pixels that should glow, so base and stencil can never disagree."""

    def __init__(self, width, height):
        self.width, self.height = width, height
        self.pixels = {}

    def put(self, x, y, colour):
        if 0 <= x < self.width and 0 <= y < self.height:
            self.pixels[(x, y)] = colour

    def dot(self, rect, ox, oy, colour):
        x, y, w, h = rect
        if ox < w and oy < h:
            self.put(x + ox, y + oy, colour)

    def outline(self, rect, colour, inset=0):
        x, y, w, h = rect
        for dy in range(inset, max(inset + 1, h - inset)):
            for dx in range(inset, max(inset + 1, w - inset)):
                if dx in (inset, w - inset - 1) or dy in (inset, h - inset - 1):
                    self.put(x + dx, y + dy, colour)


def build(base_name, eye, seam, horn):
    base_path = os.path.join(TEX_DIR, base_name + ".png")
    os.makedirs(BACKUP, exist_ok=True)
    backup_path = os.path.join(BACKUP, base_name + ".png")
    if not os.path.exists(backup_path):
        shutil.copy2(base_path, backup_path)
        print("  backed up original ->", os.path.relpath(backup_path, ROOT))

    # Always start from the pristine copy, so repeated runs cannot accumulate edits.
    base = Image.open(backup_path).convert("RGBA")
    marks = Landmarks(base.width, base.height)
    cubes = load_cubes()

    # --- eyes ---------------------------------------------------------------------------------
    # On the muzzle's forward face. The head cube sits behind the muzzle and is almost entirely
    # occluded by it, so eyes painted there would never be seen.
    muzzle_front = faces(cubes["head"][1])["north"]
    for ox in (1, 3):
        marks.dot(muzzle_front, ox, 1, eye)

    # --- horns --------------------------------------------------------------------------------
    # Lit edges on the outer segments. The horns dominate the silhouette, so lighting them is what
    # lets a player read her facing across a dark arena.
    for bone in ("hornRight", "hornLeft"):
        for cube in cubes[bone]:
            marks.outline(faces(cube)["north"], horn)

    # --- torso seams --------------------------------------------------------------------------
    # The "faint glowing seams" of a construct whose body is not fully there.
    for cube in cubes["torso"]:
        marks.outline(faces(cube)["north"], seam, inset=1)
    for cube in cubes["ribcage"]:
        marks.outline(faces(cube)["north"], seam)

    # Paint the colours into the texture — this is where the glow's RGB actually comes from —
    # and mark exactly the same pixels in the stencil.
    mask = Image.new("RGBA", base.size, (0, 0, 0, 0))
    base_px, mask_px = base.load(), mask.load()
    for (x, y), colour in marks.pixels.items():
        base_px[x, y] = colour
        mask_px[x, y] = (255, 255, 255, 255)

    base.save(base_path)
    mask.save(os.path.join(TEX_DIR, base_name + "_glowmask.png"))
    print("  %-28s %d landmark pixels (%.1f%% of sheet)" % (
        base_name, len(marks.pixels), 100.0 * len(marks.pixels) / (base.width * base.height)))


# Bright on purpose: these colours are what the glow layer renders at full brightness, so they
# need real luminance. Phase 3 burns hotter — same landmarks, harder to miss.
build("visage_of_malice", eye=(255, 214, 170, 255), seam=(198, 120, 255, 255),
      horn=(228, 170, 255, 255))
build("visage_of_malice_deep", eye=(255, 240, 210, 255), seam=(232, 168, 255, 255),
      horn=(248, 214, 255, 255))
print("done — base textures carry the glow colours, masks are pure stencils")
