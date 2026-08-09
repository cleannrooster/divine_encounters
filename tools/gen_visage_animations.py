"""Regenerate the placeholder Bedrock animations for the Visage of War.

!!! THE LIVE CLIPS ARE HAND-AUTHORED. THIS SCRIPT WILL DESTROY THEM. !!!

As of the "impossible momentum" pass the shipping clips have been edited by hand and are no longer
this script's output. Running it plainly would overwrite that work, so it now refuses unless you
pass --overwrite-authored, and it keeps a copy in tools/authored_backup/ before it does.

    python tools/gen_visage_animations.py                      # safe: writes nothing
    python tools/gen_visage_animations.py --overwrite-authored # destructive, backs up first

This is not hypothetical caution. The Malice texture generator silently reverted hand-painted
sheets to placeholder art earlier in development, for exactly this reason: a generator that is
correct to re-run for one asset gets re-run for all of them.

The script is still worth keeping. It remains the reference for which swing angles a clip *should*
honour (see the sign convention below), and the arc/weapon agreement it guarantees by construction
is the thing hand-authored clips have to reproduce by discipline.

Run from the project root:  python tools/gen_visage_animations.py

Why this exists: the attack clips are generated from the *same* swing angles the attack
definitions declare (the SwingPath on each entry in VisageAttacks), so the animated blade and
the rendered slash arc travel the same way by construction rather than by hand-matching. Clip
lengths match the tick timings in VisageAttacks exactly. If you retune a swing's angles or
timings there, re-run this so the placeholder clip follows.

Hand-authored replacement clips do not need this script — but they do need to honour the same
start/end angles, or the arc and the weapon will disagree again.

--- SIGN CONVENTION ---
Geometry angles are positive toward her RIGHT (see SwingPath). Bedrock bone Y rotations do not
share that handedness once GeckoLib has loaded them, so ANIM_YAW_SIGN reconciles the two.

This was verified in game: at -1.0 the clips swung opposite to their arcs, so it is +1.0. It is
the only thing linking the two conventions — if a future GeckoLib or model change inverts the
horizontal swing again, flip this one constant and re-run rather than editing keyframes or
mirroring the model. Vertical travel (overhead vs rising) is unaffected by it.
"""
import json
import collections
import io
import math
import os
import shutil
import sys

OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   "common", "src", "main", "resources", "assets", "divine_encounters",
                   "animations", "visage_of_war.animation.json")

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


def clip(length, loop, bones):
    d = collections.OrderedDict()
    if loop:
        d["loop"] = True
    d["animation_length"] = round(length, 4)
    d["bones"] = bones
    return d


def pose(theta, roll_deg):
    """Decompose an in-plane swing angle into body yaw, arm yaw and arm pitch.

    A swing at in-plane angle `theta` through a plane rolled by `roll_deg` has a lateral
    component of theta*cos(roll) and a vertical component of theta*sin(roll). Driving the
    rig from that decomposition is what keeps the pose pointing where the damage is.
    """
    roll = math.radians(roll_deg)
    lateral = theta * math.cos(roll)
    vertical = theta * math.sin(roll)
    body_yaw = ANIM_YAW_SIGN * lateral * 0.40
    arm_yaw = ANIM_YAW_SIGN * lateral * 0.85
    # Arms hang at 0 and point forward near -90; more vertical component means a higher blade.
    arm_pitch = -72.0 - vertical * 0.95
    return body_yaw, arm_yaw, arm_pitch


def swing_clip(windup, active, recovery, start_theta, end_theta, roll,
               lift=0.0, advance=0.0, wing_spread=1.0):
    """Build a one-shot attack clip whose blade travels start_theta -> end_theta."""
    total = (windup + active + recovery) / TPS
    t_wind = windup / TPS
    t_anticipate = max(0.05, t_wind * 0.75)
    t_strike = (windup + active) / TPS
    t_settle = t_strike + (recovery / TPS) * 0.45

    b0, a0, p0 = pose(start_theta, roll)
    b1, a1, p1 = pose(end_theta, roll)
    # Exaggerate the wind-back a little past the true start angle: anticipation reads as intent.
    bw, aw, pw = pose(start_theta * 1.25, roll)

    bones = {
        "body": kf(position=[
            (0.0, [0, 0, 0]),
            (t_wind, [0, lift * 0.6, -advance * 0.4]),
            (t_strike, [0, -lift * 0.25, advance]),
            (total, [0, 0, 0])]),
        "torso": kf(rotation=[
            (0.0, [0, b0 * 0.5, 0]),
            (t_anticipate, [-6 - lift * 4, bw, 0]),
            (t_wind, [-4, b0, 0]),
            (t_strike, [10 + max(0.0, p0 - p1) * 0.08, b1, 0]),
            (total, [0, 0, 0])]),
        "head": kf(rotation=[
            (0.0, [0, -b0 * 0.6, 0]),
            (t_wind, [4, -bw * 0.6, 0]),
            (t_strike, [-6, -b1 * 0.6, 0]),
            (total, [0, 0, 0])]),
        "rightArm": kf(rotation=[
            (0.0, [-20, 0, 4]),
            (t_anticipate, [pw, aw, 14]),
            (t_wind, [p0, a0, 12]),
            (t_strike, [p1, a1, -10]),
            (t_settle, [p1 * 0.4 - 14, a1 * 0.3, 0]),
            (total, [-8, 0, 3])]),
        "leftArm": kf(rotation=[
            (0.0, [-12, 0, -8]),
            (t_wind, [p0 * 0.55, -a0 * 0.5, -22]),
            (t_strike, [p1 * 0.4, -a1 * 0.5, -14]),
            (total, [-4, 0, -6])]),
        "rightLeg": kf(rotation=[
            (0.0, [-14, 0, 2]),
            (t_wind, [-26 - lift * 6, 0, 2]),
            (t_strike, [6, 0, 2]),
            (total, [-14, 0, 2])]),
        "leftLeg": kf(rotation=[
            (0.0, [8, 0, -2]),
            (t_wind, [20 + lift * 5, 0, -2]),
            (t_strike, [-12, 0, -2]),
            (total, [8, 0, -2])]),
        "wingRight": kf(rotation=[
            (0.0, [0, 0, -14]),
            (t_wind, [0, 18 * wing_spread, -34 * wing_spread]),
            (t_strike, [0, -12 * wing_spread, 20 * wing_spread]),
            (total, [0, 0, -14])]),
        "wingRightOuter": kf(rotation=[
            (0.0, [0, 0, -6]),
            (t_wind, [0, 14 * wing_spread, -26 * wing_spread]),
            (t_strike, [0, -16 * wing_spread, 26 * wing_spread]),
            (total, [0, 0, -6])]),
        "wingLeft": kf(rotation=[
            (0.0, [0, 0, 14]),
            (t_wind, [0, -18 * wing_spread, 34 * wing_spread]),
            (t_strike, [0, 12 * wing_spread, -20 * wing_spread]),
            (total, [0, 0, 14])]),
        "wingLeftOuter": kf(rotation=[
            (0.0, [0, 0, 6]),
            (t_wind, [0, -14 * wing_spread, 26 * wing_spread]),
            (t_strike, [0, 16 * wing_spread, -26 * wing_spread]),
            (total, [0, 0, 6])]),
    }
    return clip(total, False, bones)


anims = collections.OrderedDict()

# --- locomotion loops -------------------------------------------------------------------
L = 2.4
anims["animation.visage_of_war.hover"] = clip(L, True, {
    "body": kf(position=[(0, [0, 0, 0]), (L / 2, [0, 0.8, 0]), (L, [0, 0, 0])]),
    "torso": kf(rotation=[(0, [2, 0, 0]), (L / 2, [-2, 0, 0]), (L, [2, 0, 0])]),
    "head": kf(rotation=[(0, [-2, 0, 0]), (L / 2, [1, 0, 0]), (L, [-2, 0, 0])]),
    "rightArm": kf(rotation=[(0, [-8, 0, 3]), (L / 2, [-14, 0, 5]), (L, [-8, 0, 3])]),
    "leftArm": kf(rotation=[(0, [-4, 0, -6]), (L / 2, [-9, 0, -9]), (L, [-4, 0, -6])]),
    "rightLeg": kf(rotation=[(0, [-14, 0, 2]), (L / 2, [-10, 0, 2]), (L, [-14, 0, 2])]),
    "leftLeg": kf(rotation=[(0, [8, 0, -2]), (L / 2, [12, 0, -2]), (L, [8, 0, -2])]),
    "wingRight": kf(rotation=[(0, [0, 0, -14]), (L / 2, [0, -8, 16]), (L, [0, 0, -14])]),
    "wingRightOuter": kf(rotation=[(0, [0, 0, -6]), (L / 2, [0, -14, 22]), (L, [0, 0, -6])]),
    "wingLeft": kf(rotation=[(0, [0, 0, 14]), (L / 2, [0, 8, -16]), (L, [0, 0, 14])]),
    "wingLeftOuter": kf(rotation=[(0, [0, 0, 6]), (L / 2, [0, 14, -22]), (L, [0, 0, 6])]),
})

L = 1.0
anims["animation.visage_of_war.pursue"] = clip(L, True, {
    "body": kf(position=[(0, [0, 0, 0]), (L / 2, [0, 0.5, 0]), (L, [0, 0, 0])]),
    "torso": kf(rotation=[(0, [12, 0, 0]), (L / 2, [8, 0, 0]), (L, [12, 0, 0])]),
    "head": kf(rotation=[(0, [-12, 0, 0]), (L, [-12, 0, 0])]),
    "rightArm": kf(rotation=[(0, [-26, 0, 6]), (L / 2, [-33, 0, 8]), (L, [-26, 0, 6])]),
    "leftArm": kf(rotation=[(0, [-14, 0, -12]), (L / 2, [-19, 0, -15]), (L, [-14, 0, -12])]),
    "rightLeg": kf(rotation=[(0, [-24, 0, 3]), (L / 2, [-20, 0, 3]), (L, [-24, 0, 3])]),
    "leftLeg": kf(rotation=[(0, [16, 0, -3]), (L / 2, [20, 0, -3]), (L, [16, 0, -3])]),
    "wingRight": kf(rotation=[(0, [0, 12, -30]), (L / 2, [0, -16, 30]), (L, [0, 12, -30])]),
    "wingRightOuter": kf(rotation=[(0, [0, 8, -18]), (L / 2, [0, -26, 38]), (L, [0, 8, -18])]),
    "wingLeft": kf(rotation=[(0, [0, -12, 30]), (L / 2, [0, 16, -30]), (L, [0, -12, 30])]),
    "wingLeftOuter": kf(rotation=[(0, [0, -8, 18]), (L / 2, [0, 26, -38]), (L, [0, -8, 18])]),
})

# Flat reposition arc. The roll itself is applied by the renderer from the controller's bank
# value, so this clip only supplies the swept-back flight posture that sells it.
L = 0.7
anims["animation.visage_of_war.bank"] = clip(L, True, {
    "torso": kf(rotation=[(0, [16, 0, 0]), (L / 2, [13, 0, 0]), (L, [16, 0, 0])]),
    "head": kf(rotation=[(0, [-18, 0, 0]), (L, [-18, 0, 0])]),
    "rightArm": kf(rotation=[(0, [-52, -10, 10]), (L / 2, [-58, -8, 12]), (L, [-52, -10, 10])]),
    "leftArm": kf(rotation=[(0, [-34, 14, -20]), (L, [-34, 14, -20])]),
    "rightLeg": kf(rotation=[(0, [-30, 0, 4]), (L, [-30, 0, 4])]),
    "leftLeg": kf(rotation=[(0, [-16, 0, -4]), (L, [-16, 0, -4])]),
    "wingRight": kf(rotation=[(0, [0, 40, -18]), (L / 2, [0, 32, -26]), (L, [0, 40, -18])]),
    "wingRightOuter": kf(rotation=[(0, [0, 30, -10]), (L / 2, [0, 24, -18]), (L, [0, 30, -10])]),
    "wingLeft": kf(rotation=[(0, [0, -40, 18]), (L / 2, [0, -32, 26]), (L, [0, -40, 18])]),
    "wingLeftOuter": kf(rotation=[(0, [0, -30, 10]), (L / 2, [0, -24, 18]), (L, [0, -30, 10])]),
})

# Climbing arc: wings driving hard, body opening up as she sweeps over the target.
L = 0.9
anims["animation.visage_of_war.aerial_sweep"] = clip(L, True, {
    "torso": kf(rotation=[(0, [-14, 0, 0]), (L / 2, [-20, 0, 0]), (L, [-14, 0, 0])]),
    "head": kf(rotation=[(0, [14, 0, 0]), (L, [14, 0, 0])]),
    "rightArm": kf(rotation=[(0, [-120, 8, 16]), (L / 2, [-134, 10, 20]), (L, [-120, 8, 16])]),
    "leftArm": kf(rotation=[(0, [-100, -10, -24]), (L / 2, [-114, -12, -28]), (L, [-100, -10, -24])]),
    "rightLeg": kf(rotation=[(0, [-34, 0, 3]), (L, [-34, 0, 3])]),
    "leftLeg": kf(rotation=[(0, [-20, 0, -3]), (L, [-20, 0, -3])]),
    "wingRight": kf(rotation=[(0, [0, 16, -52]), (L / 2, [0, -20, 36]), (L, [0, 16, -52])]),
    "wingRightOuter": kf(rotation=[(0, [0, 12, -40]), (L / 2, [0, -28, 46]), (L, [0, 12, -40])]),
    "wingLeft": kf(rotation=[(0, [0, -16, 52]), (L / 2, [0, 20, -36]), (L, [0, -16, 52])]),
    "wingLeftOuter": kf(rotation=[(0, [0, -12, 40]), (L / 2, [0, 28, -46]), (L, [0, -12, 40])]),
})

# --- attacks, generated from their declared swing paths ----------------------------------
# (windup, active, recovery, startAngle, endAngle, planeRoll) mirror VisageAttacks exactly.

# Wing Sweep: SwingPath.rightToLeft(105) -> +52.5 to -52.5, horizontal.
anims["animation.visage_of_war.sweep"] = swing_clip(9, 7, 10, 52.5, -52.5, 0.0, advance=0.35)

# Descending Cut: SwingPath.overhead(120, DIAGONAL_STEEP) -> +60 to -60, roll 68.
anims["animation.visage_of_war.descending_cut"] = swing_clip(
    17, 7, 14, 60.0, -60.0, 68.0, lift=0.9, advance=0.5)

# Sundering Sweep: SwingPath.leftToRight(162) -> -81 to +81, horizontal. Wide and slow.
anims["animation.visage_of_war.wide_cleave"] = swing_clip(
    25, 10, 19, -81.0, 81.0, 0.0, advance=0.6, wing_spread=1.35)

# Lance Thrust: a lane, so there is no angular travel — the motion is a straight drive forward.
W, A, R = 13, 5, 11
total = (W + A + R) / TPS
t_wind, t_strike = W / TPS, (W + A) / TPS
anims["animation.visage_of_war.thrust"] = clip(total, False, {
    "body": kf(position=[(0, [0, 0, 0]), (t_wind, [0, 0, -1.6]), (t_strike, [0, 0, 3.2]),
                         (total, [0, 0, 0])]),
    "torso": kf(rotation=[(0, [0, 20, 0]), (t_wind * 0.8, [0, 34, 0]), (t_wind, [0, 30, 0]),
                          (t_strike, [4, -14, 0]), (total, [0, 0, 0])]),
    "head": kf(rotation=[(0, [0, -16, 0]), (t_wind, [0, -24, 0]), (t_strike, [0, 10, 0]),
                         (total, [0, 0, 0])]),
    "rightArm": kf(rotation=[(0, [-30, 18, 0]), (t_wind * 0.8, [-16, 44, 4]), (t_wind, [-20, 40, 4]),
                             (t_strike, [-96, -6, 0]), (t_strike + 0.15, [-92, -4, 0]),
                             (total, [-8, 0, 3])]),
    "leftArm": kf(rotation=[(0, [-20, -18, -10]), (t_wind, [-26, -26, -14]),
                            (t_strike, [-32, 26, -20]), (total, [-4, 0, -6])]),
    "rightLeg": kf(rotation=[(0, [-18, 0, 2]), (t_wind, [-24, 0, 2]), (t_strike, [-34, 0, 2]),
                             (total, [-14, 0, 2])]),
    "leftLeg": kf(rotation=[(0, [10, 0, -2]), (t_wind, [14, 0, -2]), (t_strike, [24, 0, -2]),
                            (total, [8, 0, -2])]),
    "wingRight": kf(rotation=[(0, [0, 0, -18]), (t_wind, [0, 0, -36]), (t_strike, [0, 0, 10]),
                              (total, [0, 0, -14])]),
    "wingLeft": kf(rotation=[(0, [0, 0, 18]), (t_wind, [0, 0, 36]), (t_strike, [0, 0, -10]),
                             (total, [0, 0, 14])]),
})

# Heaven's Divide: also a lane (roll 78) — blade raised vertically, then driven straight ahead.
W, A, R = 27, 8, 24
total = (W + A + R) / TPS
t_wind, t_strike = W / TPS, (W + A) / TPS
t_settle = t_strike + (R / TPS) * 0.4
anims["animation.visage_of_war.heavy_cleave"] = clip(total, False, {
    "body": kf(position=[(0, [0, 0, 0]), (t_wind, [0, 3.2, -1.2]), (t_strike, [0, -1.6, 3.4]),
                         (total, [0, 0, 0])]),
    "torso": kf(rotation=[(0, [0, 0, 0]), (t_wind * 0.55, [-12, 18, 0]), (t_wind, [-24, 24, 0]),
                          (t_strike, [36, -16, 0]), (total, [0, 0, 0])]),
    "head": kf(rotation=[(0, [0, 0, 0]), (t_wind, [18, -20, 0]), (t_strike, [-26, 10, 0]),
                         (total, [0, 0, 0])]),
    "rightArm": kf(rotation=[(0, [-20, 0, 4]), (t_wind * 0.55, [-124, 14, 12]),
                             (t_wind, [-180, 18, 22]), (t_strike, [-8, -10, -16]),
                             (t_settle, [-24, -4, -6]), (total, [-8, 0, 3])]),
    "leftArm": kf(rotation=[(0, [-10, 0, -8]), (t_wind, [-164, -14, -30]), (t_strike, [-14, 10, -18]),
                            (total, [-4, 0, -6])]),
    "rightLeg": kf(rotation=[(0, [-14, 0, 2]), (t_wind, [-44, 0, 2]), (t_strike, [18, 0, 2]),
                             (total, [-14, 0, 2])]),
    "leftLeg": kf(rotation=[(0, [8, 0, -2]), (t_wind, [36, 0, -2]), (t_strike, [-22, 0, -2]),
                            (total, [8, 0, -2])]),
    "wingRight": kf(rotation=[(0, [0, 0, -14]), (t_wind, [0, 26, -62]), (t_strike, [0, -10, 32]),
                              (total, [0, 0, -14])]),
    "wingRightOuter": kf(rotation=[(0, [0, 0, -6]), (t_wind, [0, 20, -46]), (t_strike, [0, -14, 38]),
                                   (total, [0, 0, -6])]),
    "wingLeft": kf(rotation=[(0, [0, 0, 14]), (t_wind, [0, -26, 62]), (t_strike, [0, 10, -32]),
                             (total, [0, 0, 14])]),
    "wingLeftOuter": kf(rotation=[(0, [0, 0, 6]), (t_wind, [0, -20, 46]), (t_strike, [0, 14, -38]),
                                  (total, [0, 0, 6])]),
})

# Pressing Advance: a shove, no blade travel.
W, A, R = 6, 3, 7
total = (W + A + R) / TPS
t_wind, t_strike = W / TPS, (W + A) / TPS
anims["animation.visage_of_war.shove"] = clip(total, False, {
    "body": kf(position=[(0, [0, 0, 0]), (t_wind, [0, 0, -1.2]), (t_strike, [0, 0, 2.2]),
                         (total, [0, 0, 0])]),
    "torso": kf(rotation=[(0, [0, 0, 0]), (t_wind, [-10, 0, 0]), (t_strike, [18, 0, 0]),
                          (total, [0, 0, 0])]),
    "rightArm": kf(rotation=[(0, [-8, 0, 3]), (t_wind, [-32, 10, 14]), (t_strike, [-86, -6, 8]),
                             (total, [-8, 0, 3])]),
    "leftArm": kf(rotation=[(0, [-4, 0, -6]), (t_wind, [-28, -10, -16]), (t_strike, [-82, 6, -8]),
                            (total, [-4, 0, -6])]),
    "wingRight": kf(rotation=[(0, [0, 0, -14]), (t_strike, [0, 0, 20]), (total, [0, 0, -14])]),
    "wingLeft": kf(rotation=[(0, [0, 0, 14]), (t_strike, [0, 0, -20]), (total, [0, 0, 14])]),
})

# --- remaining loops ---------------------------------------------------------------------
L = 0.8
anims["animation.visage_of_war.charge"] = clip(L, True, {
    "body": kf(rotation=[(0, [26, 0, 0]), (L / 2, [30, 0, 0]), (L, [26, 0, 0])]),
    "head": kf(rotation=[(0, [-30, 0, 0]), (L, [-30, 0, 0])]),
    "rightArm": kf(rotation=[(0, [-104, -6, 0]), (L / 2, [-108, -4, 0]), (L, [-104, -6, 0])]),
    "leftArm": kf(rotation=[(0, [-30, 20, -24]), (L, [-30, 20, -24])]),
    "rightLeg": kf(rotation=[(0, [-34, 0, 2]), (L, [-34, 0, 2])]),
    "leftLeg": kf(rotation=[(0, [-22, 0, -2]), (L, [-22, 0, -2])]),
    "wingRight": kf(rotation=[(0, [0, 46, -8]), (L / 2, [0, 40, -14]), (L, [0, 46, -8])]),
    "wingRightOuter": kf(rotation=[(0, [0, 34, -4]), (L, [0, 34, -4])]),
    "wingLeft": kf(rotation=[(0, [0, -46, 8]), (L / 2, [0, -40, 14]), (L, [0, -46, 8])]),
    "wingLeftOuter": kf(rotation=[(0, [0, -34, 4]), (L, [0, -34, 4])]),
})

L = 1.0
anims["animation.visage_of_war.aerial_rise"] = clip(L, True, {
    "torso": kf(rotation=[(0, [-18, 0, 0]), (L / 2, [-24, 0, 0]), (L, [-18, 0, 0])]),
    "head": kf(rotation=[(0, [16, 0, 0]), (L, [16, 0, 0])]),
    "rightArm": kf(rotation=[(0, [-150, 10, 14]), (L / 2, [-162, 12, 18]), (L, [-150, 10, 14])]),
    "leftArm": kf(rotation=[(0, [-140, -10, -18]), (L / 2, [-152, -12, -22]), (L, [-140, -10, -18])]),
    "rightLeg": kf(rotation=[(0, [-30, 0, 2]), (L, [-30, 0, 2])]),
    "leftLeg": kf(rotation=[(0, [-22, 0, -2]), (L, [-22, 0, -2])]),
    "wingRight": kf(rotation=[(0, [0, 14, -50]), (L / 2, [0, -18, 34]), (L, [0, 14, -50])]),
    "wingRightOuter": kf(rotation=[(0, [0, 10, -36]), (L / 2, [0, -26, 44]), (L, [0, 10, -36])]),
    "wingLeft": kf(rotation=[(0, [0, -14, 50]), (L / 2, [0, 18, -34]), (L, [0, -14, 50])]),
    "wingLeftOuter": kf(rotation=[(0, [0, -10, 36]), (L / 2, [0, 26, -44]), (L, [0, -10, 36])]),
})

L = 0.6
anims["animation.visage_of_war.aerial_descend"] = clip(L, True, {
    "torso": kf(rotation=[(0, [42, 0, 0]), (L, [46, 0, 0])]),
    "head": kf(rotation=[(0, [-40, 0, 0]), (L, [-44, 0, 0])]),
    "rightArm": kf(rotation=[(0, [-186, 0, 10]), (L, [-190, 0, 12])]),
    "leftArm": kf(rotation=[(0, [-176, 0, -10]), (L, [-180, 0, -12])]),
    "rightLeg": kf(rotation=[(0, [-16, 0, 2]), (L, [-12, 0, 2])]),
    "leftLeg": kf(rotation=[(0, [-16, 0, -2]), (L, [-12, 0, -2])]),
    "wingRight": kf(rotation=[(0, [0, 56, 10]), (L, [0, 60, 14])]),
    "wingLeft": kf(rotation=[(0, [0, -56, -10]), (L, [0, -60, -14])]),
})

L = 3.0
anims["animation.visage_of_war.divine_pursuit"] = clip(L, True, {
    "body": kf(position=[(0, [0, 0, 0]), (1.5, [0, 2.0, 0]), (3.0, [0, 0, 0])]),
    "torso": kf(rotation=[(0, [-10, 0, 0]), (1.2, [-24, 0, 0]), (3.0, [-10, 0, 0])]),
    "head": kf(rotation=[(0, [-6, 0, 0]), (1.2, [-30, 0, 0]), (3.0, [-6, 0, 0])]),
    "rightArm": kf(rotation=[(0, [-20, 0, 30]), (1.2, [-40, 0, 96]), (3.0, [-20, 0, 30])]),
    "leftArm": kf(rotation=[(0, [-20, 0, -30]), (1.2, [-40, 0, -96]), (3.0, [-20, 0, -30])]),
    "rightLeg": kf(rotation=[(0, [-14, 0, 6]), (1.2, [-6, 0, 12]), (3.0, [-14, 0, 6])]),
    "leftLeg": kf(rotation=[(0, [-14, 0, -6]), (1.2, [-6, 0, -12]), (3.0, [-14, 0, -6])]),
    "wingRight": kf(rotation=[(0, [0, 0, -14]), (1.2, [0, 34, -46]), (3.0, [0, 0, -14])]),
    "wingRightOuter": kf(rotation=[(0, [0, 0, -6]), (1.2, [0, 26, -30]), (3.0, [0, 0, -6])]),
    "wingLeft": kf(rotation=[(0, [0, 0, 14]), (1.2, [0, -34, 46]), (3.0, [0, 0, 14])]),
    "wingLeftOuter": kf(rotation=[(0, [0, 0, 6]), (1.2, [0, -26, 30]), (3.0, [0, 0, 6])]),
})

L = 0.8
anims["animation.visage_of_war.recover"] = clip(L, True, {
    "torso": kf(rotation=[(0, [14, 0, 0]), (L / 2, [8, 0, 0]), (L, [14, 0, 0])]),
    "head": kf(rotation=[(0, [-10, 0, 0]), (L, [-10, 0, 0])]),
    "rightArm": kf(rotation=[(0, [-16, -8, 8]), (L / 2, [-22, -6, 10]), (L, [-16, -8, 8])]),
    "leftArm": kf(rotation=[(0, [-12, 8, -12]), (L / 2, [-18, 6, -14]), (L, [-12, 8, -12])]),
    "rightLeg": kf(rotation=[(0, [-12, 0, 2]), (L, [-12, 0, 2])]),
    "leftLeg": kf(rotation=[(0, [10, 0, -2]), (L, [10, 0, -2])]),
    "wingRight": kf(rotation=[(0, [0, 0, -8]), (L / 2, [0, 0, 6]), (L, [0, 0, -8])]),
    "wingLeft": kf(rotation=[(0, [0, 0, 8]), (L / 2, [0, 0, -6]), (L, [0, 0, 8])]),
})

doc = collections.OrderedDict()
doc["format_version"] = "1.8.0"
doc["animations"] = anims

if "--overwrite-authored" not in sys.argv:
    print("REFUSED: %s is hand-authored." % os.path.basename(OUT))
    print("         Nothing written. Pass --overwrite-authored to replace it with placeholders.")
    print("         (%d placeholder clips were built and discarded.)" % len(anims))
    sys.exit(0)

# Destructive from here. Keep the authored version recoverable.
if os.path.exists(OUT):
    backup_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "authored_backup")
    if not os.path.isdir(backup_dir):
        os.makedirs(backup_dir)
    shutil.copy2(OUT, os.path.join(backup_dir, os.path.basename(OUT)))
    print("backed up authored clips to tools/authored_backup/")

with io.open(OUT, "w", encoding="utf-8") as f:
    json.dump(doc, f, indent="\t")
    f.write("\n")
print("wrote", len(anims), "clips")
for name, data in anims.items():
    print("  ", name.split('.')[-1], data["animation_length"], "loop" if data.get("loop") else "once")
