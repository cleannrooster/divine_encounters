package com.cleannrooster.divineencounters.content.malice.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/// A brief false silhouette at the arena's edge.
///
/// Its entire purpose is uncertainty, never deception without counterplay: it renders, it fades,
/// and it can do nothing else. It has no AI, no collision, no attacks, and cannot be damaged or
/// damage anything. The real Malice always announces itself with a distinct strong cue, so an
/// attentive player can always tell an echo from the thing that is about to hit them.
///
/// Deliberately a real entity rather than a client-side effect: it has to be visible to every
/// player in a multiplayer fight, and reusing the normal entity-tracking path is far cheaper than
/// inventing a second synchronisation mechanism for something this small.
public class MaliceEchoEntity extends Entity implements GeoEntity {
    /// Reuses Malice's own rig, so a decoy has exactly the silhouette the real thing does. Anything
    /// less and the player could tell them apart on shape alone, which would defeat the point.
    private static final RawAnimation IDLE =
            RawAnimation.begin().thenLoop("animation.visage_of_malice.perch");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    /// Short by design. A lingering fake becomes a landmark, and landmarks are information.
    private static final int LIFETIME = 26;

    private int age;

    public MaliceEchoEntity(EntityType<? extends MaliceEchoEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    /// How far through its life it is, 0-1 — the renderer fades it out on this.
    public float fade(float partialTick) {
        return net.minecraft.util.Mth.clamp((this.age + partialTick) / LIFETIME, 0.0f, 1.0f);
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide && ++this.age >= LIFETIME) {
            this.discard();
        }
        this.setDeltaMovement(Vec3.ZERO);
    }

    /// Nothing can interact with it in any way.
    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        return false;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "idle", 0,
                state -> state.setAndContinue(IDLE)));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.age = tag.getInt("Age");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("Age", this.age);
    }
}
