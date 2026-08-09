package com.cleannrooster.divineencounters.shrine;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import org.jetbrains.annotations.Nullable;

/// Recognising a shrine, and reading what has been left on it.
///
/// A shrine is a lodestone with a lectern directly on top. Both are deliberate choices: a lodestone
/// is already the game's "this place is fixed and findable" block, and a lectern is the only vanilla
/// block whose entire purpose is holding a book where others can read it.
///
/// Structure validation lives here rather than in the interaction handler so the same definition
/// serves the interaction, the debug command, and anything added later.
public final class Shrine {
    private Shrine() {
    }

    /// A validated shrine and its contents.
    ///
    /// @param lodestone the base, which is what the player interacts with
    /// @param lectern   the block entity holding the prayer
    /// @param book      the signed book on the lectern
    public record Found(BlockPos lodestone, LecternBlockEntity lectern, ItemStack book) {
    }

    /// Whether a position holds the base of a shrine, regardless of what is on the lectern.
    public static boolean isShrineBase(Level level, BlockPos pos) {
        return level.getBlockState(pos).is(Blocks.LODESTONE)
                && level.getBlockState(pos.above()).is(Blocks.LECTERN);
    }

    /// Resolve a shrine at a lodestone, including its book.
    ///
    /// Returns null when the structure is wrong, the lectern is empty, or the book is an unsigned
    /// draft — a writable book is a work in progress, not a prayer.
    public static @Nullable Found at(Level level, BlockPos lodestone) {
        if (!isShrineBase(level, lodestone)) {
            return null;
        }
        var lecternPos = lodestone.above();
        if (!(level.getBlockEntity(lecternPos) instanceof LecternBlockEntity lectern)) {
            return null;
        }
        if (!lectern.hasBook()) {
            return null;
        }
        var book = lectern.getBook();
        if (!PrayerText.isSignedBook(book)) {
            return null;
        }
        return new Found(lodestone, lectern, book);
    }

    /// Find a shrine near a position — used by the debug command so a tester can stand next to one
    /// rather than having to target it precisely.
    public static @Nullable Found near(Level level, BlockPos origin, int radius) {
        for (var pos : BlockPos.betweenClosed(origin.offset(-radius, -radius, -radius),
                origin.offset(radius, radius, radius))) {
            var found = at(level, pos.immutable());
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
