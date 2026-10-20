package dev.guildquests.service;

import net.minecraft.nbt.CompoundTag;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.*;

public class BossAttackSnapshotTest {
    private final BossAttackSnapshot snapshot = new BossAttackSnapshot(UUID.randomUUID(), UUID.randomUUID(),
            new BossDifficulty.Profile(5, 750000, 4, 5000, 20, 8, 3, 2));

    @Test public void savedProjectileKeepsAllOffensiveInputs() {
        assertEquals(snapshot, BossAttackSnapshot.load(snapshot.save()));
        CompoundTag saved = snapshot.save();
        saved.putDouble("attack", 1);
        assertEquals(750000, snapshot.profile().attack(), 0);
        assertNotEquals(snapshot, BossAttackSnapshot.load(saved));
    }

    @Test public void missingOrInvalidSavedDataCannotBecomeAnAttack() {
        assertNull(BossAttackSnapshot.load(new CompoundTag()));
        for (String field : new String[]{"attack", "speed", "health", "armor", "toughness", "beneficial", "harmful"}) {
            CompoundTag saved = snapshot.save(); saved.remove(field);
            assertNull(BossAttackSnapshot.load(saved));
            saved = snapshot.save(); saved.putDouble(field, Double.POSITIVE_INFINITY);
            assertNull(BossAttackSnapshot.load(saved));
        }
        CompoundTag saved = snapshot.save(); saved.putInt("version", 99);
        assertNull(BossAttackSnapshot.load(saved));
    }
}
