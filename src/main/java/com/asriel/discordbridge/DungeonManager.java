package com.asriel.discordbridge;

import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.event.entity.PlayerDeathEvent;
import java.util.*;

public class DungeonManager implements Listener {

    private final JavaPlugin plugin;
    private final Map<UUID, DungeonSession> activeSessions = new HashMap<>();
    private DungeonMenu dungeonMenu;

    public void setDungeonMenu(DungeonMenu dungeonMenu) {
        this.dungeonMenu = dungeonMenu;
    }

    // ==============================
    // 副本等級設定（數值調整區）
    // ==============================
    private static final Map<Integer, DungeonConfig> DUNGEON_CONFIGS = new LinkedHashMap<>();
    static {
        // 格式：等級, new DungeonConfig(怪物種類, 怪物數量, 怪物血量倍率, 怪物攻擊倍率)
        DUNGEON_CONFIGS.put(1, new DungeonConfig(EntityType.ZOMBIE,    5,  1.0, 1.0));
        DUNGEON_CONFIGS.put(2, new DungeonConfig(EntityType.SKELETON,  6,  1.2, 1.2));
        DUNGEON_CONFIGS.put(3, new DungeonConfig(EntityType.SPIDER,    8,  1.5, 1.3));
        DUNGEON_CONFIGS.put(4, new DungeonConfig(EntityType.CREEPER,   5,  2.0, 1.5));
        DUNGEON_CONFIGS.put(5, new DungeonConfig(EntityType.WITCH,     4,  2.5, 2.0));
    }
    // ==============================

    // ==============================
    // Chunk 分配設定（可調整）
    // ==============================
    private static final int DUNGEON_BASE_X = 100000; // 副本起始 X 座標
    private static final int DUNGEON_BASE_Z = 0;      // 副本起始 Z 座標
    private static final int CHUNK_SPACING  = 2;      // 每個副本之間的 chunk 間距
    // ==============================

    // ==============================
    // 屏障設定（可調整）
    // ==============================
    private static final int BARRIER_MIN_Y = -64;  // 屏障最低高度（配合 1.18+ 地圖下限）
    private static final int BARRIER_MAX_Y = 319;  // 屏障最高高度
    private static final int PLAYER_SPAWN_Y = 319; // 玩家傳送高度
    private static final int INVINCIBLE_SECONDS = 10; // 無敵秒數
    // ==============================

    public DungeonManager(JavaPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void startDungeon(Player player, int level) {
        if (activeSessions.containsKey(player.getUniqueId())) {
            player.sendMessage("§c你已經在副本中了！");
            return;
        }

        DungeonConfig config = DUNGEON_CONFIGS.get(level);
        if (config == null) {
            player.sendMessage("§c副本等級不存在。");
            return;
        }

        World world = Bukkit.getWorlds().get(0); // 使用主世界

        // 分配 chunk 位置
        int sessionIndex = activeSessions.size();
        int chunkX = (DUNGEON_BASE_X >> 4) + sessionIndex * CHUNK_SPACING;
        int chunkZ = DUNGEON_BASE_Z >> 4;

        // 強制載入 chunk 讓 MC 自然生成地形
        Chunk chunk = world.getChunkAt(chunkX, chunkZ);
        world.loadChunk(chunk);

        int blockX = chunkX * 16 + 8; // chunk 中心 X
        int blockZ = chunkZ * 16 + 8; // chunk 中心 Z

        // 傳送玩家到 319 格高
        Location spawnLoc = new Location(world, blockX, PLAYER_SPAWN_Y, blockZ);
        player.teleport(spawnLoc);

        // 給予無敵效果
        player.addPotionEffect(new PotionEffect(
            PotionEffectType.DAMAGE_RESISTANCE,
            INVINCIBLE_SECONDS * 20, // 秒數轉 tick
            4,     // 等級 5（0-indexed），等同完全無敵
            false,
            true
        ));
        player.sendMessage("§b你有 " + INVINCIBLE_SECONDS + " 秒的無敵保護！");

        // 放置 barrier 圍牆（非同步執行避免卡頓）
        Bukkit.getScheduler().runTask(plugin, () -> {
            placeBarriers(world, chunkX, chunkZ);

            List<UUID> mobUUIDs = spawnMobs(world, config, level, blockX, blockZ);
            DungeonSession session = new DungeonSession(
                player.getUniqueId(), level, mobUUIDs,
                player.getLocation(), chunkX, chunkZ
            );
            activeSessions.put(player.getUniqueId(), session);

            player.sendMessage("§a已進入第 " + level + " 關副本！消滅所有怪物即可通關！");
            player.sendMessage("§e剩餘怪物：§f" + mobUUIDs.size());
        });
    }

    private void placeBarriers(World world, int chunkX, int chunkZ) {
        int minX = chunkX * 16;
        int maxX = minX + 15;
        int minZ = chunkZ * 16;
        int maxZ = minZ + 15;

        for (int y = BARRIER_MIN_Y; y <= BARRIER_MAX_Y; y++) {
            // 西牆 (minX)
            for (int z = minZ; z <= maxZ; z++) {
                world.getBlockAt(minX - 1, y, z).setType(Material.BARRIER);
            }
            // 東牆 (maxX)
            for (int z = minZ; z <= maxZ; z++) {
                world.getBlockAt(maxX + 1, y, z).setType(Material.BARRIER);
            }
            // 北牆 (minZ)
            for (int x = minX - 1; x <= maxX + 1; x++) {
                world.getBlockAt(x, y, minZ - 1).setType(Material.BARRIER);
            }
            // 南牆 (maxZ)
            for (int x = minX - 1; x <= maxX + 1; x++) {
                world.getBlockAt(x, y, maxZ + 1).setType(Material.BARRIER);
            }
        }
    }

    private List<UUID> spawnMobs(World world, DungeonConfig config, int level, int centerX, int centerZ) {
        List<UUID> mobUUIDs = new ArrayList<>();
        for (int i = 0; i < config.mobCount; i++) {

            // ==============================
            // 怪物生成位置偏移（可調整範圍）
            // ==============================
            double offsetX = (Math.random() - 0.5) * 10;
            double offsetZ = (Math.random() - 0.5) * 10;
            // ==============================

            // 找到地表高度生成怪物
            int surfaceY = world.getHighestBlockYAt(centerX + (int) offsetX, centerZ + (int) offsetZ);
            Location mobLoc = new Location(world, centerX + offsetX, surfaceY + 1, centerZ + offsetZ);
            LivingEntity mob = (LivingEntity) world.spawnEntity(mobLoc, config.entityType);

            // ==============================
            // 套用血量和攻擊倍率
            // ==============================
            double maxHp = mob.getMaxHealth() * config.healthMultiplier;
            mob.setMaxHealth(maxHp);
            mob.setHealth(maxHp);
            var attackAttr = mob.getAttribute(org.bukkit.attribute.Attribute.GENERIC_ATTACK_DAMAGE);
            if (attackAttr != null) {
                attackAttr.setBaseValue(attackAttr.getBaseValue() * config.attackMultiplier);
            }
            // ==============================

            mob.setCustomName("§c[Lv." + level + "] " + mob.getType().name());
            mob.setCustomNameVisible(true);
            mob.setGlowing(true);
            mobUUIDs.add(mob.getUniqueId());
        }
        return mobUUIDs;
    }

    @EventHandler
    public void onMobDeath(EntityDeathEvent event) {
        UUID mobId = event.getEntity().getUniqueId();

        for (Map.Entry<UUID, DungeonSession> entry : activeSessions.entrySet()) {
            DungeonSession session = entry.getValue();
            if (session.mobUUIDs.contains(mobId)) {
                session.mobUUIDs.remove(mobId);

                Player player = Bukkit.getPlayer(entry.getKey());
                if (player != null) {
                    if (session.mobUUIDs.isEmpty()) {
                        completeDungeon(player, session);
                    } else {
                        player.sendMessage("§e剩餘怪物：§f" + session.mobUUIDs.size());
                    }
                }
                break;
            }
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (activeSessions.containsKey(player.getUniqueId())) {
            DungeonSession session = activeSessions.get(player.getUniqueId());
            activeSessions.remove(player.getUniqueId());
            clearBarriers(player.getWorld(), session.chunkX, session.chunkZ);
            player.sendMessage("§c你在副本中死亡，副本已結束。");
        }
    }

    private void completeDungeon(Player player, DungeonSession session) {
        activeSessions.remove(player.getUniqueId());

        // 清空 barrier 和 chunk 內怪物
        Bukkit.getScheduler().runTask(plugin, () -> {
            clearBarriers(player.getWorld(), session.chunkX, session.chunkZ);
        });

        player.sendMessage("§a§l副本通關！");

        // ==============================
        // 通關獎勵（之後在這裡新增）
        // ==============================
        if (dungeonMenu != null) {
                dungeonMenu.unlockNextLevel(player, session.level);
        }
        // ==============================

        // 傳送回主世界出生點
        player.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
    }

    private void clearBarriers(World world, int chunkX, int chunkZ) {
        int minX = chunkX * 16;
        int maxX = minX + 15;
        int minZ = chunkZ * 16;
        int maxZ = minZ + 15;

        for (int y = BARRIER_MIN_Y; y <= BARRIER_MAX_Y; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                world.getBlockAt(minX - 1, y, z).setType(Material.AIR);
                world.getBlockAt(maxX + 1, y, z).setType(Material.AIR);
            }
            for (int x = minX - 1; x <= maxX + 1; x++) {
                world.getBlockAt(x, y, minZ - 1).setType(Material.AIR);
                world.getBlockAt(x, y, maxZ + 1).setType(Material.AIR);
            }
        }
    }

    public boolean isInDungeon(Player player) {
        return activeSessions.containsKey(player.getUniqueId());
    }

    // 副本設定資料類別
    static class DungeonConfig {
        EntityType entityType;
        int mobCount;
        double healthMultiplier;
        double attackMultiplier;

        DungeonConfig(EntityType entityType, int mobCount, double healthMultiplier, double attackMultiplier) {
            this.entityType = entityType;
            this.mobCount = mobCount;
            this.healthMultiplier = healthMultiplier;
            this.attackMultiplier = attackMultiplier;
        }
    }

    // 副本進行中的狀態
    static class DungeonSession {
        UUID playerUUID;
        int level;
        List<UUID> mobUUIDs;
        Location origin;
        int chunkX;
        int chunkZ;

        DungeonSession(UUID playerUUID, int level, List<UUID> mobUUIDs, Location origin, int chunkX, int chunkZ) {
            this.playerUUID = playerUUID;
            this.level = level;
            this.mobUUIDs = new ArrayList<>(mobUUIDs);
            this.origin = origin;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }
    }
}