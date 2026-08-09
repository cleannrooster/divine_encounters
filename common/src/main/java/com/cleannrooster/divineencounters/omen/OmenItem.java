package com.cleannrooster.divineencounters.omen;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/// The item that binds an omen to whoever uses it.
///
/// Using it does not build anything. It arms the player: the arena appears at the next place the
/// world will accept one, which is what makes carrying an omen a decision about *where you go*
/// rather than a button that spawns a boss.
///
/// The item is consumed on binding rather than on manifestation. Binding is the commitment; if the
/// player then never finds suitable ground, the binding simply persists until they do.
public class OmenItem extends Item {
    private final OmenType omen;

    public OmenItem(OmenType omen, Properties properties) {
        super(properties);
        this.omen = omen;
    }

    public OmenType omen() {
        return this.omen;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        var held = player.getItemInHand(hand);
        if (level.isClientSide || !(level instanceof ServerLevel serverLevel)) {
            return InteractionResultHolder.success(held);
        }
        var data = OmenSavedData.get(serverLevel);

        // One binding at a time: two armed omens would race for the same location.
        var existing = data.bound(player.getUUID());
        if (existing != null) {
            player.displayClientMessage(Component.translatable("omen.divine_encounters.already_bound",
                    Component.translatable(existing.descriptionId())).withStyle(ChatFormatting.RED), true);
            return InteractionResultHolder.fail(held);
        }

        data.bind(player.getUUID(), this.omen, serverLevel.getGameTime());
        held.consume(1, player);

        level.playSound(null, player.blockPosition(), SoundEvents.BEACON_ACTIVATE,
                SoundSource.PLAYERS, 0.8f, 0.6f);
        player.displayClientMessage(Component.translatable(this.omen.descriptionId() + ".bound")
                .withStyle(ChatFormatting.DARK_PURPLE), true);
        return InteractionResultHolder.consume(held);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        tooltip.add(Component.translatable(this.omen.descriptionId() + ".tooltip")
                .withStyle(ChatFormatting.GRAY));
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }
}
