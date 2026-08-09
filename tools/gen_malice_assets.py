"""Generate placeholder animations and textures for the Visage of Malice.

Run from the project root:  python tools/gen_malice_assets.py

Companion to gen_visage_animations.py, and it follows the same conventions — clip lengths
match the tick timings in MaliceAttacks, and horizontal swings are driven from the same
declared SwingPath angles so the animated limb and the rendered arc agree by construction.

ANIM_YAW_SIGN carries over from the War generator, where it was confirmed in game. See the
note there: geometry angles are positive toward the model's right, Bedrock bone Y rotations
run the other way once GeckoLib has loaded them.

The rig is a constructed satyr: digitigrade legs (thigh / shin / hoof), a hollow ribcage, long
forearms, and big swept horns.

Movement is deliberately unlike a humanoid walk, and as of the "hostile concept" pass it is not a
walk at all: Malice hovers. The locomotion clips below contain **no gait cycle**, because a hovering
body has nothing to push against. The legs hang, trailing very slightly, and the only thing that
tells you it is moving is that the world is going past it.

Three cues are removed on purpose, because each of them reads as *alive*:

- **breathing** — no periodic torso or ribcage rise anywhere;
- **weight shift** — no side-to-side hip translation, no leg loading;
- **the bob** — the up/down root motion a walk cycle needs is gone. What little vertical drift
  remains lives in `MaliceController.hoverRise`, is under 5 cm, and is slow enough not to read as a
  rhythm.

What replaces them is much slower and much weaker: a long asymmetric sway in the horns and arms,
too slow to be a pulse, with two periods that never line up. The intent is a body that is *being
held* in position by something rather than holding itself.
"""
import collections
import io
import json
import math
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "common", "src", "main", "resources", "assets", "divine_encounters")
ANIM_OUT = os.path.join(ASSETS, "animations", "visage_of_malice.animation.json")

ANIM_YAW_SIGN = 1.0
TPS = 20.0


def fmt(t):
    s = f"{t:.4f}".rstrip('0').rstrip('.')
    return s if s else "0.0"


def kf(rotation=None, position=None):
    out = {}
    if rotation:
        out["rotation"] = {fmt(t): [round(v, 2) for v in val] for t, val in rotation}
    if position:
        out["position"] = {fmt(t): [round(v, 3) for v in val] for t, val in position}
    return out


# Bones that MUST appear in every clip, even holding a constant value. See `clip`.
DRIVEN_BONES = ("head", "neck")


def clip(length, loop, bones):
    """Build one clip, guaranteeing that every code-driven bone is animated.

    `VisageOfMaliceModel.setCustomAnimations` adds the gaze correction *on top of* whatever the
    clip posed, which is only safe if the clip actually posed it.

    GeckoLib decides per frame whether a bone was animated (`GeoBone.hasRotationChanged`). If it
    was, the bone is authoritatively rewritten from the clip and adding an offset afterwards is
    correct. If it was NOT, GeckoLib instead eases the bone from *its current value* back toward
    the rest pose over `getBoneResetTime()` — and its current value still contains the offset the
    model added last frame. The offset is then added again on top of the partially-decayed result,
    every frame, and the bone spins.

    This bit once, hard: dropping the head keyframes from the hover clips (to stop the head
    "looking around", since the gaze correction owns it now) is exactly what left the head
    un-animated, and it span. Holding a constant value keeps the bone animated and pins it, which
    is what was actually wanted.

    A constant keyframe is enough — it still enqueues animation points, so the bone is reset from
    the clip each frame and nothing accumulates.
    """
    for name in DRIVEN_BONES:
        if name not in bones:
            bones[name] = kf(rotation=[(0.0, [0, 0, 0]), (round(length, 4), [0, 0, 0])])
    d = collections.OrderedDict()
    if loop:
        d["loop"] = True
    d["animation_length"] = round(length, 4)
    d["bones"] = bones
    return d


def pose(theta, roll_deg):
    """Decompose an in-plane swing angle into torso yaw, arm yaw and arm pitch."""
    roll = math.radians(roll_deg)
    lateral = theta * math.cos(roll)
    vertical = theta * math.sin(roll)
    torso_yaw = ANIM_YAW_SIGN * lateral * 0.45
    arm_yaw = ANIM_YAW_SIGN * lateral * 0.9
    arm_pitch = -60.0 - vertical * 0.9
    return torso_yaw, arm_yaw, arm_pitch


def swing_clip(windup, active, recovery, start_theta, end_theta, roll, crouch=0.0, lunge=0.0):
    """A one-shot attack clip whose leading claw travels start_theta -> end_theta."""
    total = (windup + active + recovery) / TPS
    t_wind = windup / TPS
    t_coil = max(0.05, t_wind * 0.7)
    t_strike = (windup + active) / TPS

    b0, a0, p0 = pose(start_theta, roll)
    b1, a1, p1 = pose(end_theta, roll)
    bc, ac, pc = pose(start_theta * 1.2, roll)

    return clip(total, False, {
        "root": kf(position=[
            (0.0, [0, 0, 0]),
            (t_wind, [0, -crouch, -lunge * 0.3]),
            (t_strike, [0, 0, lunge]),
            (total, [0, 0, 0])]),
        "hips": kf(rotation=[
            (0.0, [0, b0 * 0.4, 0]),
            (t_wind, [8 + crouch * 4, bc * 0.5, 0]),
            (t_strike, [-4, b1 * 0.5, 0]),
            (total, [0, 0, 0])]),
        "torso": kf(rotation=[
            (0.0, [4, b0, 0]),
            (t_coil, [14, bc, 0]),
            (t_wind, [12, b0, 0]),
            (t_strike, [-10, b1, 0]),
            (total, [4, 0, 0])]),
        "neck": kf(rotation=[
            (0.0, [-6, -b0 * 0.5, 0]),
            (t_wind, [-14, -bc * 0.5, 0]),
            (t_strike, [8, -b1 * 0.5, 0]),
            (total, [-6, 0, 0])]),
        "armRight": kf(rotation=[
            (0.0, [10, 0, 8]),
            (t_coil, [pc, ac, 22]),
            (t_wind, [p0, a0, 18]),
            (t_strike, [p1, a1, -14]),
            (total, [10, 0, 8])]),
        "forearmRight": kf(rotation=[
            (0.0, [-30, 0, 0]),
            (t_wind, [-72, 0, 0]),
            (t_strike, [-8, 0, 0]),
            (total, [-30, 0, 0])]),
        "armLeft": kf(rotation=[
            (0.0, [10, 0, -8]),
            (t_wind, [p0 * 0.4, -a0 * 0.4, -26]),
            (t_strike, [p1 * 0.3, -a1 * 0.4, -12]),
            (total, [10, 0, -8])]),
        "forearmLeft": kf(rotation=[
            (0.0, [-30, 0, 0]),
            (t_wind, [-54, 0, 0]),
            (total, [-30, 0, 0])]),
        # Digitigrade legs stay coiled through the whole action — it never stands upright.
        "thighRight": kf(rotation=[
            (0.0, [-32, 0, 0]), (t_wind, [-44 - crouch * 6, 0, 0]),
            (t_strike, [-24, 0, 0]), (total, [-32, 0, 0])]),
        "shinRight": kf(rotation=[
            (0.0, [58, 0, 0]), (t_wind, [72, 0, 0]), (t_strike, [46, 0, 0]), (total, [58, 0, 0])]),
        "thighLeft": kf(rotation=[
            (0.0, [-32, 0, 0]), (t_wind, [-40 - crouch * 6, 0, 0]),
            (t_strike, [-28, 0, 0]), (total, [-32, 0, 0])]),
        "shinLeft": kf(rotation=[
            (0.0, [58, 0, 0]), (t_wind, [68, 0, 0]), (t_strike, [50, 0, 0]), (total, [58, 0, 0])]),
    })


anims = collections.OrderedDict()

# --- locomotion -----------------------------------------------------------------------------
L = 1.1
# The hover. Nothing here is a locomotion cycle — this clip plays identically whether Malice is
# holding station or crossing the arena at full speed, which is the whole point: its motion through
# the world is unrelated to what its body is doing.
#
# The two sway periods (L and L*0.61) are deliberately incommensurate, so the loop never visibly
# repeats and the eye cannot find a rhythm to lock onto.
L = 5.2
anims["animation.visage_of_malice.stalk"] = clip(L, True, {
    # Held. The hover height is maintained by the controller, not animated here — so a deliberate
    # rise or drop always means something.
    "root": kf(position=[(0, [0, 0, 0]), (L, [0, 0, 0])]),
    # A slow lean that never returns through the same pose it left by.
    "hips": kf(rotation=[(0, [4, 0, 0.6]), (L * 0.5, [3, 0, -0.6]), (L, [4, 0, 0.6])]),
    "torso": kf(rotation=[(0, [6, 1.5, 0]), (L * 0.61, [5, -1.5, 0]), (L, [6, 1.5, 0])]),
    "neck": kf(rotation=[(0, [-14, 0, 0]), (L, [-14, 0, 0])]),
    # No idle head motion at all: the head is driven entirely by the gaze correction in
    # VisageOfMaliceModel, so it holds dead still on the player while the body drifts under it.
    # Any keyframe here would fight that and reintroduce the "looking around" read.
    #
    # Arms hang from the shoulder with a long, uneven pendulum — the motion of something suspended,
    # not something carrying its own limbs.
    "armRight": kf(rotation=[(0, [8, 0, 6]), (L * 0.5, [11, 0, 8]), (L, [8, 0, 6])]),
    "armLeft": kf(rotation=[(0, [11, 0, -8]), (L * 0.61, [8, 0, -6]), (L, [11, 0, -8])]),
    "forearmRight": kf(rotation=[(0, [-22, 0, 0]), (L * 0.5, [-18, 0, 0]), (L, [-22, 0, 0])]),
    "forearmLeft": kf(rotation=[(0, [-18, 0, 0]), (L * 0.61, [-22, 0, 0]), (L, [-18, 0, 0])]),
    # Legs trail. They are not supporting anything and they are not walking; they are the part of
    # the silhouette that most gives the hover away, so they stay almost perfectly still.
    "thighRight": kf(rotation=[(0, [-16, 0, 0]), (L * 0.5, [-14, 0, 0]), (L, [-16, 0, 0])]),
    "shinRight": kf(rotation=[(0, [34, 0, 0]), (L * 0.5, [32, 0, 0]), (L, [34, 0, 0])]),
    "hoofRight": kf(rotation=[(0, [-18, 0, 0]), (L, [-18, 0, 0])]),
    "thighLeft": kf(rotation=[(0, [-14, 0, 0]), (L * 0.61, [-16, 0, 0]), (L, [-14, 0, 0])]),
    "shinLeft": kf(rotation=[(0, [32, 0, 0]), (L * 0.61, [34, 0, 0]), (L, [32, 0, 0])]),
    "hoofLeft": kf(rotation=[(0, [-18, 0, 0]), (L, [-18, 0, 0])]),
    "tailStub": kf(rotation=[(0, [34, 3, 0]), (L * 0.61, [34, -3, 0]), (L, [34, 3, 0])]),
})

# The "gather" — what used to be a crouch. It is now the sharpest contrast in the whole rig, and it
# is the one place a mechanical read is *wanted*: the hover is loose and drifting, and this snaps
# into a rigid, symmetric, perfectly still shape. Predators coil. This one locks.
#
# Note the total absence of interpolation targets: every bone holds one value for the entire loop.
# Combined with the sway of the hover it should look like the animation stopped, which is far more
# unsettling than any amount of tension.
L = 0.9
anims["animation.visage_of_malice.crouch"] = clip(L, True, {
    # Draws in *toward its own centre* rather than dropping toward the ground. There is nothing
    # beneath it to load against, and a downward crouch would silently reintroduce weight.
    "root": kf(position=[(0, [0, -0.5, 0]), (L, [0, -0.5, 0])]),
    "hips": kf(rotation=[(0, [24, 0, 0]), (L, [24, 0, 0])]),
    "torso": kf(rotation=[(0, [20, 0, 0]), (L, [20, 0, 0])]),
    "neck": kf(rotation=[(0, [-30, 0, 0]), (L, [-30, 0, 0])]),
    # Exactly mirrored, to the degree. Living bodies are never this symmetric.
    "armRight": kf(rotation=[(0, [46, 0, 16]), (L, [46, 0, 16])]),
    "armLeft": kf(rotation=[(0, [46, 0, -16]), (L, [46, 0, -16])]),
    "forearmRight": kf(rotation=[(0, [-84, 0, 0]), (L, [-84, 0, 0])]),
    "forearmLeft": kf(rotation=[(0, [-84, 0, 0]), (L, [-84, 0, 0])]),
    "thighRight": kf(rotation=[(0, [-52, 0, 0]), (L, [-52, 0, 0])]),
    "shinRight": kf(rotation=[(0, [84, 0, 0]), (L, [84, 0, 0])]),
    "thighLeft": kf(rotation=[(0, [-52, 0, 0]), (L, [-52, 0, 0])]),
    "shinLeft": kf(rotation=[(0, [84, 0, 0]), (L, [84, 0, 0])]),
    "tailStub": kf(rotation=[(0, [30, 0, 0]), (L, [30, 0, 0])]),
})

L = 0.8
anims["animation.visage_of_malice.perch"] = clip(L, True, {
    # It is not balancing on anything — it is holding station above it. The legs below are folded
    # out of the way rather than braced.
    "root": kf(position=[(0, [0, -1.0, 0]), (L, [0, -1.0, 0])]),
    "hips": kf(rotation=[(0, [34, 0, 0]), (L, [34, 0, 0])]),
    "torso": kf(rotation=[(0, [28, 0, 0]), (L, [28, 0, 0])]),
    "neck": kf(rotation=[(0, [-46, 0, 0]), (L, [-46, 0, 0])]),
    # No idle head sweep: the gaze correction owns the head, and it is already locked on you.
    "armRight": kf(rotation=[(0, [64, 0, 16]), (L, [64, 0, 16])]),
    "armLeft": kf(rotation=[(0, [64, 0, -16]), (L, [64, 0, -16])]),
    "forearmRight": kf(rotation=[(0, [-96, 0, 0]), (L, [-96, 0, 0])]),
    "forearmLeft": kf(rotation=[(0, [-96, 0, 0]), (L, [-96, 0, 0])]),
    "thighRight": kf(rotation=[(0, [-74, 0, 0]), (L, [-74, 0, 0])]),
    "shinRight": kf(rotation=[(0, [104, 0, 0]), (L, [104, 0, 0])]),
    "thighLeft": kf(rotation=[(0, [-74, 0, 0]), (L, [-74, 0, 0])]),
    "shinLeft": kf(rotation=[(0, [104, 0, 0]), (L, [104, 0, 0])]),
})

L = 0.5
anims["animation.visage_of_malice.pounce"] = clip(L, True, {
    "root": kf(position=[(0, [0, 0, 0]), (L, [0, 0, 0])]),
    "hips": kf(rotation=[(0, [-16, 0, 0]), (L, [-12, 0, 0])]),
    "torso": kf(rotation=[(0, [-10, 0, 0]), (L, [-6, 0, 0])]),
    "neck": kf(rotation=[(0, [16, 0, 0]), (L, [12, 0, 0])]),
    "armRight": kf(rotation=[(0, [-104, 0, 22]), (L, [-112, 0, 26])]),
    "armLeft": kf(rotation=[(0, [-104, 0, -22]), (L, [-112, 0, -26])]),
    "forearmRight": kf(rotation=[(0, [-24, 0, 0]), (L, [-16, 0, 0])]),
    "forearmLeft": kf(rotation=[(0, [-24, 0, 0]), (L, [-16, 0, 0])]),
    "thighRight": kf(rotation=[(0, [40, 0, 0]), (L, [46, 0, 0])]),
    "shinRight": kf(rotation=[(0, [-30, 0, 0]), (L, [-36, 0, 0])]),
    "thighLeft": kf(rotation=[(0, [40, 0, 0]), (L, [46, 0, 0])]),
    "shinLeft": kf(rotation=[(0, [-30, 0, 0]), (L, [-36, 0, 0])]),
})

L = 0.8
anims["animation.visage_of_malice.recover"] = clip(L, True, {
    # Recovery, not catching its breath. The torso does not pump; the arms settle back along an
    # uneven path and stop. Anything periodic here would read as panting, which is precisely the
    # cue this pass exists to remove.
    "root": kf(position=[(0, [0, -0.3, 0]), (L, [0, -0.3, 0])]),
    "hips": kf(rotation=[(0, [20, 0, 0]), (L, [20, 0, 0])]),
    "torso": kf(rotation=[(0, [18, 0, 0]), (L, [18, 0, 0])]),
    "neck": kf(rotation=[(0, [-20, 0, 0]), (L, [-20, 0, 0])]),
    "armRight": kf(rotation=[(0, [22, -6, 12]), (L * 0.5, [18, -5, 11]), (L, [22, -6, 12])]),
    "armLeft": kf(rotation=[(0, [22, 6, -12]), (L * 0.66, [18, 5, -11]), (L, [22, 6, -12])]),
    "thighRight": kf(rotation=[(0, [-36, 0, 0]), (L, [-36, 0, 0])]),
    "shinRight": kf(rotation=[(0, [62, 0, 0]), (L, [62, 0, 0])]),
    "thighLeft": kf(rotation=[(0, [-36, 0, 0]), (L, [-36, 0, 0])]),
    "shinLeft": kf(rotation=[(0, [62, 0, 0]), (L, [62, 0, 0])]),
})

L = 2.4
anims["animation.visage_of_malice.no_witness"] = clip(L, True, {
    "root": kf(position=[(0, [0, 0, 0]), (1.2, [0, 1.4, 0]), (L, [0, 0, 0])]),
    "hips": kf(rotation=[(0, [-8, 0, 0]), (1.2, [-18, 0, 0]), (L, [-8, 0, 0])]),
    "torso": kf(rotation=[(0, [-14, 0, 0]), (1.2, [-26, 0, 0]), (L, [-14, 0, 0])]),
    "neck": kf(rotation=[(0, [-10, 0, 0]), (1.2, [-30, 0, 0]), (L, [-10, 0, 0])]),
    "armRight": kf(rotation=[(0, [-30, 0, 40]), (1.2, [-60, 0, 96]), (L, [-30, 0, 40])]),
    "armLeft": kf(rotation=[(0, [-30, 0, -40]), (1.2, [-60, 0, -96]), (L, [-30, 0, -40])]),
    "forearmRight": kf(rotation=[(0, [-20, 0, 0]), (1.2, [-6, 0, 0]), (L, [-20, 0, 0])]),
    "forearmLeft": kf(rotation=[(0, [-20, 0, 0]), (1.2, [-6, 0, 0]), (L, [-20, 0, 0])]),
    "thighRight": kf(rotation=[(0, [-30, 0, 0]), (L, [-30, 0, 0])]),
    "shinRight": kf(rotation=[(0, [56, 0, 0]), (L, [56, 0, 0])]),
    "thighLeft": kf(rotation=[(0, [-30, 0, 0]), (L, [-30, 0, 0])]),
    "shinLeft": kf(rotation=[(0, [56, 0, 0]), (L, [56, 0, 0])]),
})

# --- attacks, timings mirroring MaliceAttacks exactly ----------------------------------------
# Hooking Swipe: SwingPath.leftToRight(115) -> -57.5 to +57.5, horizontal.
anims["animation.visage_of_malice.hooking_swipe"] = swing_clip(8, 5, 9, -57.5, 57.5, 0.0, lunge=0.4)
# Raking Backhand: rightToLeft(150) with a 150 deg yaw offset — the torso twists, the legs do not.
anims["animation.visage_of_malice.backhand"] = swing_clip(12, 6, 13, 75.0, -75.0, 0.0, crouch=0.3)
# Black Sweep: rightToLeft(140).
anims["animation.visage_of_malice.black_sweep"] = swing_clip(22, 8, 18, 70.0, -70.0, 0.0, crouch=0.9)
# Crescent of Spite: leftToRight(172).
anims["animation.visage_of_malice.crescent_of_spite"] = swing_clip(26, 10, 20, -86.0, 86.0, 0.0, crouch=1.1)

# Lanes and impacts have no angular travel; their motion is a straight drive.
def lunge_clip(windup, active, recovery, crouch, reach, horn=False):
    total = (windup + active + recovery) / TPS
    t_wind, t_strike = windup / TPS, (windup + active) / TPS
    return clip(total, False, {
        "root": kf(position=[(0.0, [0, 0, 0]), (t_wind, [0, -crouch, -0.8]),
                             (t_strike, [0, 0, reach]), (total, [0, 0, 0])]),
        "hips": kf(rotation=[(0.0, [10, 0, 0]), (t_wind, [26, 0, 0]),
                             (t_strike, [-6, 0, 0]), (total, [10, 0, 0])]),
        "torso": kf(rotation=[(0.0, [12, 0, 0]), (t_wind, [30, 0, 0]),
                              (t_strike, [-14 if horn else -4, 0, 0]), (total, [12, 0, 0])]),
        "neck": kf(rotation=[(0.0, [-14, 0, 0]), (t_wind, [-34, 0, 0]),
                             (t_strike, [26 if horn else 6, 0, 0]), (total, [-14, 0, 0])]),
        "armRight": kf(rotation=[(0.0, [12, 0, 8]), (t_wind, [40, 20, 20]),
                                 (t_strike, [-96, -8, 4]), (total, [12, 0, 8])]),
        "forearmRight": kf(rotation=[(0.0, [-30, 0, 0]), (t_wind, [-80, 0, 0]),
                                     (t_strike, [-4, 0, 0]), (total, [-30, 0, 0])]),
        "armLeft": kf(rotation=[(0.0, [12, 0, -8]), (t_wind, [36, -20, -20]),
                                (t_strike, [-40, 10, -14]), (total, [12, 0, -8])]),
        "forearmLeft": kf(rotation=[(0.0, [-30, 0, 0]), (t_wind, [-70, 0, 0]), (total, [-30, 0, 0])]),
        "thighRight": kf(rotation=[(0.0, [-32, 0, 0]), (t_wind, [-56, 0, 0]),
                                   (t_strike, [-16, 0, 0]), (total, [-32, 0, 0])]),
        "shinRight": kf(rotation=[(0.0, [58, 0, 0]), (t_wind, [86, 0, 0]),
                                  (t_strike, [34, 0, 0]), (total, [58, 0, 0])]),
        "thighLeft": kf(rotation=[(0.0, [-32, 0, 0]), (t_wind, [-52, 0, 0]),
                                  (t_strike, [-20, 0, 0]), (total, [-32, 0, 0])]),
        "shinLeft": kf(rotation=[(0.0, [58, 0, 0]), (t_wind, [82, 0, 0]),
                                 (t_strike, [38, 0, 0]), (total, [58, 0, 0])]),
    })


anims["animation.visage_of_malice.needle_thrust"] = lunge_clip(11, 4, 10, 0.8, 2.0)
anims["animation.visage_of_malice.backbite"] = lunge_clip(14, 5, 16, 1.2, 2.4)
anims["animation.visage_of_malice.horn_rush"] = lunge_clip(16, 14, 15, 1.6, 3.0, horn=True)
anims["animation.visage_of_malice.low_pounce"] = lunge_clip(9, 3, 10, 1.8, 3.4)
anims["animation.visage_of_malice.grudge"] = lunge_clip(6, 3, 8, 0.6, 0.4)
anims["animation.visage_of_malice.manifest"] = lunge_clip(5, 3, 6, 1.4, 0.0)

doc = collections.OrderedDict()
doc["format_version"] = "1.8.0"
doc["animations"] = anims

os.makedirs(os.path.dirname(ANIM_OUT), exist_ok=True)
with io.open(ANIM_OUT, "w", encoding="utf-8") as f:
    json.dump(doc, f, indent="\t")
    f.write("\n")
print("wrote", len(anims), "clips")

# ---------------------------------------------------------------- textures
from PIL import Image, ImageDraw


def ensure(*parts):
    path = os.path.join(ASSETS, *parts)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    return path


def malice_skin(path, obsidian, seam, hollow, horn, glow):
    """Dark construct with glowing seams. Deliberately low-value so the seams carry the read."""
    img = Image.new("RGBA", (128, 128), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    def block(x, y, w, h, colour, edge=None):
        d.rectangle([x, y, x + w - 1, y + h - 1], fill=colour)
        if edge:
            d.rectangle([x, y, x + w - 1, y + h - 1], outline=edge)

    # hips / torso / ribcage / neck
    block(0, 0, 30, 8, obsidian, seam)
    block(0, 8, 42, 11, obsidian, seam)
    block(0, 19, 44, 12, obsidian, seam)
    block(30, 8, 14, 10, hollow, seam)
    block(30, 18, 20, 6, hollow, seam)
    block(42, 0, 14, 8, obsidian, seam)
    block(44, 18, 12, 8, obsidian, seam)
    # head, muzzle
    block(56, 0, 24, 12, obsidian, seam)
    block(56, 12, 20, 8, obsidian, seam)
    # horns — lighter, the most readable silhouette cue
    block(80, 0, 12, 12, horn, seam)
    block(92, 0, 12, 12, horn, seam)
    block(80, 12, 12, 10, horn, seam)
    block(92, 12, 12, 10, horn, seam)
    # arms / forearms / claws
    for y in (32, 46):
        block(16, y, 12, 14, obsidian, seam)
        block(28, y, 12, 14, obsidian, seam)
        block(40, y, 12, 8, horn, seam)
    # legs / shins / hooves
    for y in (32, 46):
        block(56, y, 12, 14, obsidian, seam)
        block(68, y, 10, 14, obsidian, seam)
        block(78, y, 10, 8, (20, 16, 24, 255), seam)

    # Glowing eyes — the one thing that must stay legible at the edge of vision.
    d.rectangle([59, 3, 62, 5], fill=glow)
    d.rectangle([70, 3, 73, 5], fill=glow)
    # Seam lines down the torso.
    for y in range(9, 30, 3):
        d.line([(4, y), (38, y)], fill=seam)
    img.save(path)


# The two body sheets are OPT-IN, behind --textures.
#
# They were the original placeholders and have since been replaced by hand-authored artwork. This
# script is re-run whenever an animation changes, and running it used to silently overwrite that
# artwork with the placeholder — which is exactly what happened once. Regenerating a sheet is now
# something you have to ask for.
#
# `gen_malice_glowmask.py` keeps copies in tools/texture_backup/ and is the way back if it happens
# again; re-run it after restoring, since the base sheets carry the glow colours.
if "--textures" in sys.argv:
    malice_skin(ensure("textures", "entity", "visage_of_malice.png"),
                obsidian=(26, 20, 34, 255), seam=(96, 44, 130, 255), hollow=(8, 6, 12, 255),
                horn=(58, 48, 66, 255), glow=(196, 120, 240, 255))
    malice_skin(ensure("textures", "entity", "visage_of_malice_deep.png"),
                obsidian=(18, 12, 26, 255), seam=(150, 60, 200, 255), hollow=(4, 3, 7, 255),
                horn=(44, 34, 54, 255), glow=(236, 168, 255, 255))
    print("body sheets REGENERATED from placeholder art (--textures)")
else:
    print("body sheets left alone (pass --textures to regenerate placeholders)")


def soft_dot(path, size, falloff, core):
    img = Image.new("RGBA", (size, size), (255, 255, 255, 0))
    px = img.load()
    c = (size - 1) / 2.0
    for x in range(size):
        for y in range(size):
            r = math.hypot(x - c, y - c) / c
            a = max(0.0, 1.0 - r ** falloff)
            px[x, y] = (255, 255, 255, int(255 * min(1.0, a * core)))
    img.save(path)


soft_dot(ensure("textures", "particle", "malice_mote.png"), 10, 2.0, 1.3)
print("textures written")
