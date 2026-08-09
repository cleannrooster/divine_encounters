package com.cleannrooster.divineencounters.client.combat;

import com.cleannrooster.divineencounters.combat.AttackFrame;
import com.cleannrooster.divineencounters.combat.AttackGeometry;
import com.cleannrooster.divineencounters.combat.AttackShape;
import com.cleannrooster.divineencounters.combat.DirectionalAttack;
import com.cleannrooster.divineencounters.combat.SlashProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/// Draws an attack's swept surface as a textured ribbon.
///
/// The mesh is generated entirely from {@link AttackGeometry} — the same function the server queries when
/// deciding whether you were hit. A horizontal sweep therefore renders as a horizontal crescent of
/// exactly its real reach and arc, a vertical slash as a vertical arc, a thrust as an elongated lane the
/// length of its actual lance. Nothing here is tuned per boss; a new attack gets a correct visual by
/// existing.
///
/// The ribbon is also animated through the swing rather than shown whole: the leading edge advances with
/// the server's swing progress and the tail follows a fixed distance behind, so the arc is drawn being
/// cut.
public final class SlashRenderer {
    /// Emissive slashes are drawn fullbright so they read at night and in caves.
    private static final int FULL_BRIGHT = LightTexture.pack(15, 15);

    private SlashRenderer() {
    }

    static void render(SlashEffect effect, PoseStack poseStack, Camera camera,
                       MultiBufferSource.BufferSource buffers, float partialTick) {
        var definition = effect.definition();
        var profile = definition.slash();
        if (!profile.isVisible() || definition.shape().family() == AttackShape.Family.RADIAL) {
            return;
        }
        var alpha = profile.alpha() * effect.fade(partialTick);
        if (alpha <= 0.01f) {
            return;
        }

        var grid = buildGrid(effect, partialTick);
        if (grid == null) {
            return;
        }

        var renderType = profile.emissive()
                ? RenderType.entityTranslucentEmissive(profile.texture())
                : RenderType.entityTranslucent(profile.texture());
        var consumer = buffers.getBuffer(renderType);

        var cameraPos = camera.getPosition();
        var normal = AttackGeometry.planeNormal(definition, effect.frame());
        // Thickness is clamped to the attack's real half-extent, so however solid the blade is made
        // to look it can never occupy space the damage volume does not.
        var thickness = Math.min(profile.thickness(), (float) AttackGeometry.halfThickness(definition, effect.frame()));

        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        // The bloom goes down first, underneath the real arc.
        //
        // This is the only geometry in the mod drawn outside the damage volume, and it is drawn in a
        // deliberately different register so it cannot be mistaken for the volume: a fraction of the
        // alpha, fading to nothing at its outer limit, and with no thickness of its own. The bright,
        // solid ribbon laid over it is still exactly what hits you.
        //
        // Drawing it first also means it can never occlude the honest shape — the arc's own edge stays
        // the brightest thing on screen, which is what the player actually reads reach from.
        if (profile.hasOverreach()) {
            var bloom = buildOverreach(effect, partialTick);
            if (bloom != null) {
                emit(consumer, poseStack.last(), bloom, profile,
                        alpha * SlashProfile.OVERREACH_ALPHA, normal);
            }
        }

        if (thickness > 0.001f) {
            // Two parallel sheets read as a slab with real presence. One sheet displaced back and
            // forth (the previous approach) just looked like crumpled paper.
            emit(consumer, poseStack.last(), offsetGrid(grid, normal, thickness), profile, alpha, normal);
            emit(consumer, poseStack.last(), offsetGrid(grid, normal, -thickness), profile, alpha,
                    normal.scale(-1.0));
        } else {
            emit(consumer, poseStack.last(), grid, profile, alpha, normal);
        }
        poseStack.popPose();
    }

    /// The ribbon as a grid of world-space vertices plus a per-row brightness ramp.
    ///
    /// @param points    `[row][column]`; rows run along the long axis of the attack, columns across it
    /// @param rowAlpha  per-row opacity — the trailing end of a swing fades out behind the leading edge
    /// @param colAlpha  per-column opacity, or null for fully opaque across the blade. Only the outer
    ///                  bloom uses it, to dissolve as it projects past the real reach.
    private record Ribbon(Vec3[][] points, float[] rowAlpha, float @Nullable [] colAlpha) {
        Ribbon(Vec3[][] points, float[] rowAlpha) {
            this(points, rowAlpha, null);
        }

        float alphaAt(int row, int column) {
            var across = this.colAlpha == null ? 1.0f : this.colAlpha[column];
            return this.rowAlpha[row] * across;
        }
    }

    private static Ribbon buildGrid(SlashEffect effect, float partialTick) {
        var profile = effect.definition().slash();
        return switch (effect.definition().shape().family()) {
            case ARC -> buildArc(effect, profile, partialTick);
            case LANE, PATH -> buildLane(effect, profile, partialTick);
            case RADIAL -> null;
        };
    }

    /// A crescent: rows step through the swept angles, columns step from the inner radius out to the tip.
    /// Leaving the innermost fraction empty is what turns a pie wedge into a blade.
    private static Ribbon buildArc(SlashEffect effect, SlashProfile profile, float partialTick) {
        var definition = effect.definition();
        var frame = effect.frame();
        var lead = effect.leadingEdge(partialTick);
        var tail = effect.trailingEdge(partialTick);
        if (lead - tail < 1.0e-4f) {
            return null;
        }
        // Cones show their whole span from the first frame; swept arcs reveal it progressively.
        if (!definition.shape().isSwept()) {
            tail = 0.0f;
            lead = 1.0f;
        }

        var rows = Math.max(2, profile.sweepSegments());
        var cols = Math.max(1, profile.bladeSegments());
        var points = new Vec3[rows + 1][cols + 1];
        var rowAlpha = new float[rows + 1];

        for (var r = 0; r <= rows; r++) {
            var rowFraction = (float) r / rows;
            var s = Mth.lerp(rowFraction, tail, lead);
            // Brightest at the leading edge, dissolving toward the tail of the trail.
            rowAlpha[r] = rowFraction * rowFraction * (3.0f - 2.0f * rowFraction);
            for (var c = 0; c <= cols; c++) {
                var t = Mth.lerp((float) c / cols, profile.innerFraction(), 1.0f);
                points[r][c] = AttackGeometry.surfacePoint(definition, frame, s, t);
            }
        }
        return new Ribbon(points, rowAlpha);
    }

    /// A thrust or charge trail: rows step along the lane, columns across its width. The lane only
    /// extends as far as the swing has progressed, so a thrust visibly reaches out.
    private static Ribbon buildLane(SlashEffect effect, SlashProfile profile, float partialTick) {
        var definition = effect.definition();
        var frame = effect.frame();
        var extension = effect.leadingEdge(partialTick);
        if (extension <= 1.0e-4f) {
            return null;
        }

        var rows = Math.max(2, profile.bladeSegments());
        var across = AttackGeometry.planeAxis(definition, frame).scale(AttackGeometry.widthOf(definition, frame) * 0.5);
        var points = new Vec3[rows + 1][2];
        var rowAlpha = new float[rows + 1];

        for (var r = 0; r <= rows; r++) {
            var t = (float) r / rows;
            var centre = AttackGeometry.surfacePoint(definition, frame, extension, t);
            // Taper toward the root so the lane reads as a spearhead rather than a plank.
            var taper = 0.35f + 0.65f * t;
            points[r][0] = centre.subtract(across.scale(taper));
            points[r][1] = centre.add(across.scale(taper));
            rowAlpha[r] = 0.3f + 0.7f * t;
        }
        return new Ribbon(points, rowAlpha);
    }

    /// The outer bloom: a band projecting past the attack's real reach, fading out as it goes.
    ///
    /// It shares the arc's swept angles exactly, so it is the same shape pointing the same way — only
    /// longer. That is what keeps it reading as force being *projected along the strike* rather than
    /// as a decal pasted over it. It starts at the tip (`t = 1`, the real limit) and runs outward, so
    /// it never overlaps or brightens the damaging region.
    ///
    /// Returns null for shapes with no swept arc; a lane's reach is already its whole visual.
    private static @Nullable Ribbon buildOverreach(SlashEffect effect, float partialTick) {
        var definition = effect.definition();
        if (definition.shape().family() != AttackShape.Family.ARC) {
            return null;
        }
        var profile = definition.slash();
        var frame = effect.frame();
        var lead = effect.leadingEdge(partialTick);
        var tail = effect.trailingEdge(partialTick);
        if (lead - tail < 1.0e-4f) {
            return null;
        }
        if (!definition.shape().isSwept()) {
            tail = 0.0f;
            lead = 1.0f;
        }

        var rows = Math.max(2, profile.sweepSegments() / 2);
        var cols = OVERREACH_SEGMENTS;
        var points = new Vec3[rows + 1][cols + 1];
        var rowAlpha = new float[rows + 1];
        var colAlpha = new float[cols + 1];

        // Full strength where it meets the blade tip, nothing at its outer limit. Squared, so it
        // falls away quickly rather than lingering as a halo.
        for (var c = 0; c <= cols; c++) {
            var outward = (float) c / cols;
            colAlpha[c] = (1.0f - outward) * (1.0f - outward);
        }

        for (var r = 0; r <= rows; r++) {
            var rowFraction = (float) r / rows;
            var s = Mth.lerp(rowFraction, tail, lead);
            rowAlpha[r] = rowFraction * rowFraction * (3.0f - 2.0f * rowFraction);
            for (var c = 0; c <= cols; c++) {
                // t runs from 1 (the real tip) outward to 1 + overreach.
                var t = 1.0f + profile.overreach() * ((float) c / cols);
                points[r][c] = overreachPoint(definition, frame, s, t);
            }
        }
        return new Ribbon(points, rowAlpha, colAlpha);
    }

    /// The arc's surface extended past `t = 1`.
    ///
    /// {@link AttackGeometry#surfacePoint} deliberately clamps `t` to the real reach — that clamp is
    /// what guarantees particles can never spawn outside the damage volume, and it is not something to
    /// relax. So the bloom computes its own radius here instead, using the same angle and the same
    /// plane, and reaches past the limit without ever asking the geometry to lie about where the limit
    /// is.
    private static Vec3 overreachPoint(DirectionalAttack definition, AttackFrame frame, float s, float t) {
        var angle = Math.toRadians(AttackGeometry.sweepAngleDegrees(definition, frame, s));
        var plane = AttackGeometry.planeAxis(definition, frame);
        var direction = frame.forward().scale(Math.cos(angle)).add(plane.scale(Math.sin(angle)));
        var radius = (definition.innerRadius()
                + (definition.range() - definition.innerRadius()) * t) * frame.scale();
        return frame.origin().add(direction.scale(radius));
    }

    /// Radial subdivisions of the bloom. Few: it is a gradient, not a shape.
    private static final int OVERREACH_SEGMENTS = 3;

    /// A copy of the ribbon displaced bodily along the swing plane's normal — one face of the slab.
    private static Ribbon offsetGrid(Ribbon ribbon, Vec3 normal, float distance) {
        var source = ribbon.points();
        var shifted = new Vec3[source.length][];
        var offset = normal.scale(distance);
        for (var r = 0; r < source.length; r++) {
            shifted[r] = new Vec3[source[r].length];
            for (var c = 0; c < source[r].length; c++) {
                shifted[r][c] = source[r][c].add(offset);
            }
        }
        return new Ribbon(shifted, ribbon.rowAlpha());
    }

    private static void emit(VertexConsumer consumer, PoseStack.Pose pose, Ribbon ribbon,
                             SlashProfile profile, float alpha, Vec3 normal) {
        var points = ribbon.points();
        var rows = points.length - 1;
        var cols = points[0].length - 1;

        for (var r = 0; r < rows; r++) {
            var u0 = (float) r / rows;
            var u1 = (float) (r + 1) / rows;
            for (var c = 0; c < cols; c++) {
                var v0 = (float) c / cols;
                var v1 = (float) (c + 1) / cols;
                var p00 = points[r][c];
                var p10 = points[r + 1][c];
                var p11 = points[r + 1][c + 1];
                var p01 = points[r][c + 1];

                // Per-corner rather than per-row, so a ribbon that fades across the blade as well as
                // along the swing (the outer bloom) gets a smooth gradient in both directions.
                var a00 = alpha * ribbon.alphaAt(r, c);
                var a01 = alpha * ribbon.alphaAt(r, c + 1);
                var a11 = alpha * ribbon.alphaAt(r + 1, c + 1);
                var a10 = alpha * ribbon.alphaAt(r + 1, c);

                quad(consumer, pose, profile, normal,
                        p00, u0, v0, a00, p01, u0, v1, a01, p11, u1, v1, a11, p10, u1, v0, a10);
                // Reverse winding: the slash has to be visible from whichever side the player is on.
                quad(consumer, pose, profile, normal.scale(-1.0),
                        p10, u1, v0, a10, p11, u1, v1, a11, p01, u0, v1, a01, p00, u0, v0, a00);
            }
        }
    }

    private static void quad(VertexConsumer consumer, PoseStack.Pose pose, SlashProfile profile, Vec3 normal,
                             Vec3 a, float au, float av, float aa,
                             Vec3 b, float bu, float bv, float ba,
                             Vec3 c, float cu, float cv, float ca,
                             Vec3 d, float du, float dv, float da) {
        vertex(consumer, pose, profile, normal, a, au, av, aa);
        vertex(consumer, pose, profile, normal, b, bu, bv, ba);
        vertex(consumer, pose, profile, normal, c, cu, cv, ca);
        vertex(consumer, pose, profile, normal, d, du, dv, da);
    }

    private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, SlashProfile profile,
                               Vec3 normal, Vec3 position, float u, float v, float alpha) {
        consumer.addVertex(pose, (float) position.x, (float) position.y, (float) position.z)
                .setColor(profile.red(), profile.green(), profile.blue(), Mth.clamp(alpha, 0.0f, 1.0f))
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(FULL_BRIGHT)
                .setNormal(pose, (float) normal.x, (float) normal.y, (float) normal.z);
    }
}
