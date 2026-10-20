package dev.guildquests.debug;

import dev.guildquests.config.ConfigManager;
import dev.guildquests.service.BossCombat;
import dev.guildquests.service.BossEvent;
import dev.guildquests.service.GuildService;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/** Exercises concurrent encounters through real admin spawn, damage and death hooks. */
final class BossEventChecks {
    private final ServerLevel world;
    private final ServerPlayer rookie, veteran;
    private final BossProjectileChecks.BossFactory factory;
    private final BiConsumer<Boolean, String> check;

    BossEventChecks(ServerLevel world, ServerPlayer rookie, ServerPlayer veteran,
                    BossProjectileChecks.BossFactory factory, BiConsumer<Boolean, String> check) {
        this.world = world; this.rookie = rookie; this.veteran = veteran;
        this.factory = factory; this.check = check;
    }

    void run() throws Exception {
        var server = world.getServer();
        LivingEntity first = factory.create();
        rookie.removeAllEffects(); rookie.clearFire(); rookie.setAbsorptionAmount(0);
        rookie.setHealth(rookie.getMaxHealth());
        float initial = first.getHealth();
        for (int i = 0; i < 20; i++) hit(first, rookie);
        double firstCredit = credit(states().get(first.getUUID()), rookie);

        int spawned = server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "guildadmin boss 2");
        check.accept(spawned == 2 && states().size() == 3, "admin count command spawns two additional concurrent bosses");
        var others = new ArrayList<LivingEntity>();
        for (Object state : states().values()) {
            LivingEntity entity = (LivingEntity) field(state, "entity");
            if (!entity.getUUID().equals(first.getUUID())) others.add(entity);
            if (entity instanceof Mob mob) mob.setNoAi(true);
        }
        LivingEntity second = others.get(0);
        second.getAttribute(Attributes.MAX_HEALTH).setBaseValue(first.getMaxHealth() / 2);
        second.setHealth(second.getMaxHealth());
        var update = BossEvent.class.getDeclaredMethod("updateBars", net.minecraft.server.MinecraftServer.class);
        update.setAccessible(true);
        for (int i = 0; i < 20; i++) update.invoke(null, server);
        ServerBossEvent firstBar = bar(first), secondBar = bar(second);
        check.accept(firstBar != secondBar && firstBar.getPlayers().contains(rookie) && secondBar.getPlayers().contains(rookie),
                "concurrent bosses keep separate bars and viewers");

        float before = first.getHealth(); hit(first, rookie);
        check.accept(first.getHealth() == before && credit(states().get(first.getUUID()), rookie) == firstCredit,
                "additional spawn does not refresh an existing player's outgoing cap");
        float secondInitial = second.getHealth();
        for (int i = 0; i < 20; i++) hit(second, rookie);
        check.accept(second.getHealth() < secondInitial && secondInitial - second.getHealth() <= secondInitial / 40 + .001,
                "different-HP bosses have independent correctly scaled outgoing caps");
        check.accept(credit(states().get(first.getUUID()), rookie) == firstCredit
                        && Math.abs(credit(states().get(second.getUUID()), rookie) - (secondInitial - second.getHealth())) < .001,
                "concurrent damage ledgers do not mix contribution between bosses");
        before = first.getHealth(); hit(first, veteran);
        check.accept(first.getHealth() < before && initial - first.getHealth() <= initial / 20 + .001,
                "another player keeps an independent contribution allowance on the same boss");

        rookie.invulnerableTime = 0;
        float playerHealth = rookie.getHealth();
        for (int i = 0; i < 20; i++) {
            rookie.invulnerableTime = 0;
            rookie.hurt(world.damageSources().mobAttack(i % 2 == 0 ? first : second), 1e30f);
        }
        check.accept(playerHealth - rookie.getHealth() > 0 && playerHealth - rookie.getHealth() <= rookie.getMaxHealth() / 4 + .001,
                "simultaneous bosses share one incoming damage cap per player");
        check.accept(BossEvent.spawnNow(server) && states().size() == 4, "single-spawn API still adds a boss during a fight");
        UUID pinned = (UUID) staticField("pinned");
        LivingEntity newest = (LivingEntity) field(states().get(pinned), "entity");
        if (newest instanceof Mob mob) mob.setNoAi(true);
        before = rookie.getHealth(); rookie.invulnerableTime = 0;
        rookie.hurt(world.damageSources().mobAttack(newest), 1e30f);
        check.accept(rookie.getHealth() == before, "new boss spawn does not refresh the shared incoming cap");

        long oldReward = ConfigManager.config().bossEventReward;
        ConfigManager.config().bossEventReward = 100;
        long rookieCoins = GuildService.data(rookie).coins, veteranCoins = GuildService.data(veteran).coins;
        ServerBossEvent removedBar = bar(newest);
        newest.setHealth(.5f); hit(newest, veteran);
        ConfigManager.config().bossEventReward = oldReward;
        check.accept(!newest.isAlive() && states().size() == 3 && removedBar.getPlayers().isEmpty(),
                "defeating one concurrent boss removes only its bar and encounter");
        check.accept(GuildService.data(rookie).coins == rookieCoins && GuildService.data(veteran).coins == veteranCoins + 105,
                "defeated boss pays only its own attacker rather than other bosses' contributors");
        check.accept(staticField("pinned") != null && states().containsKey(staticField("pinned"))
                        && bar(first) == firstBar && bar(second) == secondBar,
                "defeating pinned boss retargets the pin and preserves surviving bars");
        before = first.getHealth(); hit(first, rookie);
        check.accept(first.getHealth() == before, "another boss's death does not refresh an ongoing outgoing cap");

        int stopped = server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "guildadmin boss stop");
        check.accept(stopped == 1 && states().isEmpty() && first.isRemoved() && second.isRemoved()
                        && firstBar.getPlayers().isEmpty() && secondBar.getPlayers().isEmpty() && staticField("pinned") == null
                        && GuildService.data(rookie).coins == rookieCoins && GuildService.data(veteran).coins == veteranCoins + 105,
                "admin stop clears all concurrent bosses, bars and pin without extra rewards");
    }

    private void hit(LivingEntity target, ServerPlayer player) {
        target.invulnerableTime = 0; target.hurtTime = 0; target.getRandom().setSeed(12345);
        target.hurt(world.damageSources().playerAttack(player), 1e30f);
    }

    static double totalCredit(ServerPlayer player) throws Exception {
        double total = 0;
        for (Object state : states().values()) total += credit(state, player);
        return total;
    }

    @SuppressWarnings("unchecked") private static Map<UUID, ?> states() throws Exception {
        return (Map<UUID, ?>) staticField("bosses");
    }

    @SuppressWarnings("unchecked") private static double credit(Object state, ServerPlayer player) throws Exception {
        return ((Map<UUID, Double>) field(state, "damage")).getOrDefault(player.getUUID(), 0d);
    }

    private static ServerBossEvent bar(LivingEntity entity) throws Exception {
        return (ServerBossEvent) field(states().get(entity.getUUID()), "bar");
    }

    private static Object field(Object instance, String name) throws Exception {
        var field = instance.getClass().getDeclaredField(name); field.setAccessible(true);
        return field.get(instance);
    }

    private static Object staticField(String name) throws Exception {
        var field = BossEvent.class.getDeclaredField(name); field.setAccessible(true);
        return field.get(null);
    }
}
