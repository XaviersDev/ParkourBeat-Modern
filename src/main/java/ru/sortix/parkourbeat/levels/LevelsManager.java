// ФАЙЛ: src/main/java/ru/sortix/parkourbeat/levels/LevelsManager.java
package ru.sortix.parkourbeat.levels;

import com.google.common.collect.Lists;
import lombok.Getter;
import lombok.NonNull;
import net.kyori.adventure.text.Component;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitTask;
import ru.sortix.parkourbeat.ParkourBeat;
import ru.sortix.parkourbeat.data.Settings;
import ru.sortix.parkourbeat.item.editor.type.EditTrackPointsItem;
import ru.sortix.parkourbeat.levels.dao.LevelSettingDAO;
import ru.sortix.parkourbeat.levels.dao.files.FileLevelSettingDAO;
import ru.sortix.parkourbeat.levels.settings.GameSettings;
import ru.sortix.parkourbeat.levels.settings.LevelSettings;
import ru.sortix.parkourbeat.levels.settings.WorldSettings;
import ru.sortix.parkourbeat.lifecycle.PluginManager;
import ru.sortix.parkourbeat.utils.StringUtils;
import ru.sortix.parkourbeat.world.OutsideBlocksCleaner;
import ru.sortix.parkourbeat.world.WorldsManager;

import javax.annotation.Nullable;
import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import ru.sortix.parkourbeat.utils.text.PbText;

public class LevelsManager implements PluginManager {
    @Getter
    private final ParkourBeat plugin;

    private final WorldsManager worldsManager;

    @Getter
    private final LevelSettingsManager levelsSettings;

    private final AvailableLevelsCollection availableLevels;
    private final Map<UUID, Level> loadedLevelsById = new HashMap<>();
    private final Map<World, Level> loadedLevelsByWorld = new HashMap<>();
    private final Set<ParticleController> particleControllers = new HashSet<>();
    private final BukkitTask particlesRenderingTask;
    private final BukkitTask autoSaveTask;
    private int nextLevelNumber = 1;

    private static final long AUTOSAVE_PERIOD_TICKS = 20L * 15L;

    private final java.util.Set<UUID> changedWorlds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<UUID> lockedLevels = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public LevelsManager(@NonNull ParkourBeat plugin) {
        this.plugin = plugin;
        this.worldsManager = plugin.get(WorldsManager.class);
        this.levelsSettings = new LevelSettingsManager(new FileLevelSettingDAO(this));
        this.availableLevels = new AvailableLevelsCollection(this.plugin.getLogger());
        this.loadAvailableLevelNames();

        this.particlesRenderingTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            for (ParticleController controller : this.particleControllers) {
                controller.tickParticles();
            }
        }, 0, 5);

        this.autoSaveTask = plugin.getServer().getScheduler().runTaskTimer(plugin,
            this::saveEditedLevels, AUTOSAVE_PERIOD_TICKS, AUTOSAVE_PERIOD_TICKS);
    }

    public void markWorldChanged(@NonNull UUID levelId) {
        this.changedWorlds.add(levelId);
    }

    public boolean isLevelLocked(@NonNull UUID levelId) {
        return this.lockedLevels.contains(levelId);
    }

    public void lockLevel(@NonNull UUID levelId) {
        this.lockedLevels.add(levelId);
    }

    public void unlockLevel(@NonNull UUID levelId) {
        this.lockedLevels.remove(levelId);
    }

    public void markWorldChanged(@NonNull World world) {
        Level level = this.getLoadedLevel(world);
        if (level != null) this.changedWorlds.add(level.getUniqueId());
    }

    public void saveEditedLevels() {
        UUID worldToSave = null;

        for (Level level : new ArrayList<>(this.loadedLevelsById.values())) {
            if (!level.isEditing()) continue;

            try {
                this.saveLevelSettings(level.getUniqueId());
            } catch (Exception e) {
                this.plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "Не удалось сохранить настройки уровня " + level.getUniqueId(), e);
            }

            if (worldToSave == null && this.changedWorlds.contains(level.getUniqueId())) {
                worldToSave = level.getUniqueId();
            }
        }

        if (worldToSave == null) return;
        this.changedWorlds.remove(worldToSave);

        Level level = this.loadedLevelsById.get(worldToSave);
        if (level == null) return;

        try {
            this.dropBlocksOutsideLevel(level);
            level.getWorld().save();
        } catch (Exception e) {
            this.changedWorlds.add(worldToSave);
            this.plugin.getLogger().log(java.util.logging.Level.SEVERE,
                "Не удалось автосохранить мир уровня " + worldToSave, e);
        }
    }

    public File getDefaultLevelDirectory(World.Environment env) {
        return this.getDefaultLevelDirectory(env, 1);
    }

    /**
     * @param chunkWidth ширина будущего уровня в чанках
     * @return папка шаблона; для широких уровней сначала ищется своя база,
     * а если её ещё не сохранили - берётся обычная
     */
    /**
     * @param twoD ищем базу для 2D-уровня
     */
    public File getDefaultLevelDirectory(World.Environment env, int chunkWidth, boolean twoD) {
        if (twoD) {
            if (chunkWidth >= 4) {
                File wide = new File(this.plugin.getDataFolder(),
                    "pb_default_level_2D_" + env.name() + "_4C");
                if (wide.isDirectory()) return wide;
            }
            File dir = new File(this.plugin.getDataFolder(), "pb_default_level_2D_" + env.name());
            if (dir.isDirectory()) return dir;
        }
        return this.getDefaultLevelDirectory(env, chunkWidth);
    }

    public File getDefaultLevelDirectory(World.Environment env, int chunkWidth) {
        if (chunkWidth >= 4) {
            File wide = new File(this.plugin.getDataFolder(),
                "pb_default_level_" + env.name() + "_4C");
            if (wide.isDirectory()) return wide;
        }
        File dir = new File(this.plugin.getDataFolder(), "pb_default_level_" + env.name());
        if (dir.isDirectory()) {
            return dir;
        }
        return new File(this.plugin.getDataFolder(), "pb_default_level");
    }

    private void loadAvailableLevelNames() {
        for (GameSettings gameSettings :
            this.levelsSettings.getLevelSettingDAO().loadAllAvailableLevelGameSettingsSync()
        ) {
            this.availableLevels.add(gameSettings);
        }
        for (GameSettings gameSettings : this.availableLevels) {
            if (this.nextLevelNumber <= gameSettings.getUniqueNumber()) {
                this.nextLevelNumber = gameSettings.getUniqueNumber() + 1;
            }
        }
    }

    @NonNull
    public Collection<GameSettings> getAvailableLevelsSettings() {
        return Collections.unmodifiableCollection(Lists.newArrayList(this.availableLevels.iterator()));
    }

    @NonNull
    public CompletableFuture<Level> createLevel(
        @NonNull World.Environment environment, @NonNull UUID ownerId, @NonNull String ownerName, @NonNull String levelName) {
        return this.createLevel(environment, ownerId, ownerName, levelName, 1);
    }

    /**
     * @param chunkWidth ширина уровня в чанках (1 или 4)
     */
    @NonNull
    public CompletableFuture<Level> createLevel(
        @NonNull World.Environment environment, @NonNull UUID ownerId, @NonNull String ownerName,
        @NonNull String levelName, int chunkWidth) {
        return this.createLevel(environment, ownerId, ownerName, levelName, chunkWidth,
            ru.sortix.parkourbeat.twod.LevelMode.THREE_D);
    }

    /**
     * @param levelMode обычный 3D-уровень или 2D-уровень
     */
    @NonNull
    public CompletableFuture<Level> createLevel(
        @NonNull World.Environment environment, @NonNull UUID ownerId, @NonNull String ownerName,
        @NonNull String levelName, int chunkWidth,
        @NonNull ru.sortix.parkourbeat.twod.LevelMode levelMode) {
        boolean twoD = levelMode.isTwoD();
        CompletableFuture<Level> result = new CompletableFuture<>();
        UUID levelId = this.getNextLevelId();
        WorldCreator worldCreator = this.levelsSettings.getLevelSettingDAO().newWorldCreator(levelId);
        worldCreator.generator(this.worldsManager.getEmptyGenerator());
        worldCreator.environment(environment);
        worldCreator.generateStructures(false);

        File defaultLevelDirectory = this.getDefaultLevelDirectory(environment, chunkWidth, twoD);
        if (!defaultLevelDirectory.isDirectory()) {
            this.plugin
                .getLogger()
                .severe("Default level directory not found: " + defaultLevelDirectory.getAbsolutePath());
            result.complete(null);
            return result;
        }

        this.worldsManager
            .createWorldFromCustomDirectory(worldCreator, defaultLevelDirectory)
            .thenAccept(world -> {
                if (world == null) {
                    result.complete(null);
                    return;
                }
                try {
                    this.prepareLevelWorld(world, true);

                    int uniqueNumber = this.nextLevelNumber++;
                    Component displayName = PbText.vanilla(levelName);

                    LevelSettings levelSettings = LevelSettings.create(
                        this.plugin,
                        world,
                        environment,
                        levelId,
                        uniqueNumber,
                        displayName,
                        ownerId,
                        ownerName
                    );

                    // Ширину выставляем до первой записи настроек, иначе область
                    // редактирования посчитается по значению по умолчанию.
                    levelSettings.getGameSettings().setChunkWidth(chunkWidth);
                    levelSettings.getGameSettings().setLevelMode(levelMode);

                    // Для широких уровней берём их собственный шаблон, если он сохранён:
                    // старт, финиш и спавн у него свои, от узкой базы они не подходят.
                    WorldSettings defaultSettings =
                        Settings.getDefaultSettings(environment, chunkWidth, twoD);

                    // ТОЛЬКО СТАРТ, БЕЗ ШАБЛОННОГО ПУТИ.
                    //
                    // Раньше новый уровень получал сразу пару "старт-финиш" из шаблона.
                    // Финиш при этом стоял в заранее заданном месте, а строитель вёл
                    // трассу от старта куда ему нужно - и, как правило, проходил мимо
                    // шаблонного финиша или дальше него. В списке точек порядок - это
                    // порядок прохождения, поэтому уровень оказывался с финишем ПЕРЕД
                    // стартом: сначала конец, потом начало. Пройти такое нельзя.
                    //
                    // Теперь ставится ровно одна точка - стартовая. Финишем становится
                    // последняя точка пути из частиц, то есть та, которую строитель
                    // поставил последней; раньше старта она не окажется никогда.
                    // Сам старт при необходимости переносится через меню редактора
                    // (кнопка над "Точкой спавна").
                    org.bukkit.Location templateStart =
                        defaultSettings.getStartWaypoint().toLocation(world);

                    levelSettings.getWorldSettings().getWaypoints().clear();
                    levelSettings.getWorldSettings().getWaypoints().add(new Waypoint(
                        templateStart, 0, EditTrackPointsItem.DEFAULT_PARTICLES_COLOR));

                    // Спавн тоже берётся из шаблона: без этого новый уровень появлялся
                    // со спавном по умолчанию, а не там, где его поставил админ.
                    // Мир у скопированной точки чужой - переставляем на новый, иначе
                    // телепорт уедет в мир-шаблон.
                    org.bukkit.Location templateSpawn = defaultSettings.getSpawn().clone();
                    templateSpawn.setWorld(world);
                    levelSettings.getWorldSettings().setSpawn(templateSpawn);

                    // Список точек заменили, но границы уровня (старт, финиш и нижняя
                    // высота мира) считаются из него отдельным вызовом. Без него в полях
                    // оставались значения от прежней базы: путь из частиц рисовался уже
                    // новый, а маркеры старта и финиша висели на старом месте - и
                    // перескакивали только после первого клика по частице, потому что
                    // редактор как раз и дёргает updateBorders().
                    levelSettings.getWorldSettings().updateBorders();

                    levelSettings.recalculateWaypoints(world);
                    levelSettings.updateParticleLocations();

                    world.setSpawnLocation(levelSettings.getWorldSettings().getSpawn());
                    Level level = new Level(levelSettings, world);
                    level.setEditing(true);

                    this.availableLevels.add(level.getLevelSettings().getGameSettings());
                    this.levelsSettings.addLevelSettings(levelId, levelSettings);
                    this.loadedLevelsById.put(levelId, level);
                    this.loadedLevelsByWorld.put(world, level);
                    result.complete(level);
                } catch (Exception e) {
                    this.plugin.getLogger().log(java.util.logging.Level.SEVERE,
                        "Unable to create level", e);
                    result.complete(null);
                }
            });
        return result;
    }

    @NonNull
    private UUID getNextLevelId() {
        UUID result;
        do {
            result = UUID.randomUUID();
        } while (this.availableLevels.byUniqueId(result) != null);
        return result;
    }

    @NonNull
    public CompletableFuture<Level> loadLevel(@NonNull UUID levelId, @Nullable GameSettings gameSettings) {
        if (gameSettings == null) {
            gameSettings = this.availableLevels.byUniqueId(levelId);
        }
        CompletableFuture<Level> result = new CompletableFuture<>();

        Level level = getLoadedLevel(levelId);
        if (level != null) {
            result.complete(level);
            return result;
        }

        if (this.lockedLevels.contains(levelId)) {
            result.complete(null);
            return result;
        }

        WorldCreator worldCreator = this.levelsSettings.getLevelSettingDAO().newWorldCreator(levelId);
        worldCreator.generator(this.worldsManager.getEmptyGenerator());
        World.Environment env = this.levelsSettings.getLevelSettingDAO().loadLevelEnvironment(levelId);
        worldCreator.environment(env);
        worldCreator.generateStructures(false);

        GameSettings finalGameSettings = gameSettings;
        this.worldsManager
            .createWorldFromDefaultContainer(worldCreator, this.worldsManager.getSyncExecutor())
            .thenAccept(world -> {
                if (world == null) {
                    result.complete(null);
                    return;
                }
                try {
                    this.prepareLevelWorld(world, false);

                    LevelSettings levelSettings = this.levelsSettings.loadLevelSettings(levelId, finalGameSettings);
                    Level loadedLevel = new Level(levelSettings, world);
                    this.loadedLevelsById.put(levelId, loadedLevel);
                    this.loadedLevelsByWorld.put(world, loadedLevel);

                    result.complete(loadedLevel);
                } catch (Exception e) {
                    this.plugin
                        .getLogger()
                        .log(java.util.logging.Level.SEVERE, "Не удалось загрузить уровень " + levelId, e);
                    result.complete(null);
                }
            });
        return result;
    }

    @NonNull
    public CompletableFuture<Boolean> deleteLevelAsync(@NonNull GameSettings settings) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        UUID levelId = settings.getUniqueId();
        this.unloadLevelAsync(levelId, false).thenAccept(success -> {
            if (!success) {
                result.complete(false);
                return;
            }


            this.availableLevels.remove(settings);
            this.levelsSettings.getLevelSettingDAO().deleteLevelWorldAndSettings(levelId);
            try {
                this.plugin.get(ru.sortix.parkourbeat.activity.EditorSessionsManager.class)
                    .removeLevel(levelId);
            } catch (Exception ignored) {
            }
            result.complete(true);
        });
        return result;
    }

    @NonNull
    public CompletableFuture<Boolean> unloadLevelAsync(@NonNull UUID levelId, boolean saveChunks) {
        Level level = this.getLoadedLevel(levelId);
        if (level == null) return CompletableFuture.completedFuture(true);

        CompletableFuture<Boolean> result = new CompletableFuture<>();
        LevelSettingDAO dao = this.levelsSettings.getLevelSettingDAO();

        ru.sortix.parkourbeat.twod.TwoDCoins.despawn(level);
        CompletableFuture<Boolean> worldUnloading;
        World world = dao.getBukkitWorld(levelId);
        if (world == null) {
            worldUnloading = CompletableFuture.completedFuture(true);
        } else {
            worldUnloading = new CompletableFuture<>();
            this.plugin
                .get(WorldsManager.class)
                .unloadBukkitWorld(
                    world,
                    saveChunks,
                    level::isChunkInside,
                    Settings.getLobbySpawn(),
                    true
                )
                .thenAccept(worldUnloading::complete);
        }

        worldUnloading.thenAccept(success -> {
            if (!success) {
                result.complete(false);
                return;
            }
            this.levelsSettings.unloadLevelSettings(levelId);
            this.loadedLevelsById.remove(levelId);
            this.loadedLevelsByWorld.remove(world);
            result.complete(true);
        });

        return result;
    }

    @NonNull
    public CompletableFuture<Boolean> upgradeDataAsync(
        @NonNull UUID levelId, @Nullable Consumer<LevelSettings> updater) {
        boolean unload = this.getLoadedLevel(levelId) == null;
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        this.loadLevel(levelId, null).thenAccept(level -> {
            LevelSettings settings;
            try {
                settings = this.levelsSettings.loadLevelSettings(
                    levelId, level.getLevelSettings().getGameSettings());
            } catch (Exception e) {
                this.plugin
                    .getLogger()
                    .log(
                        java.util.logging.Level.SEVERE,
                        "Не удалось загрузить данные уровня " + levelId + " для конвертации",
                        e);
                result.complete(false);
                return;
            }
            boolean success = true;
            if (updater != null) {
                try {
                    updater.accept(settings);
                } catch (Exception e) {
                    this.plugin
                        .getLogger()
                        .log(
                            java.util.logging.Level.SEVERE,
                            "Не удалось произвести конвертацию уровня " + levelId,
                            e);
                    success = false;
                }
            }
            try {
                this.levelsSettings.saveLevelSettings(levelId);
            } catch (Exception e) {
                this.plugin
                    .getLogger()
                    .log(
                        java.util.logging.Level.SEVERE,
                        "Не удалось сохранить данные уровня " + levelId + " после конвертации",
                        e);
                success = false;
            }
            if (unload) {
                boolean finalSuccess = success;
                this.unloadLevelAsync(levelId, false).thenAccept(success2 -> result.complete(finalSuccess && success2));
            } else {
                result.complete(success);
            }
        });
        return result;
    }

    @NonNull
    public Collection<Level> getLoadedLevels() {
        return new ArrayList<>(this.loadedLevelsById.values());
    }

    public void saveLevelSettings(@NonNull UUID levelId) {
        this.levelsSettings.saveLevelSettings(levelId);
    }

    public void saveGameSettings(@NonNull GameSettings gameSettings) {
        this.levelsSettings.saveGameSettings(gameSettings);
    }

    /**
     * Убирает всё, что построено за границей уровня, чтобы оно не попало в сохранение.
     * <p>
     * Вызывать строго ПЕРЕД {@code world.save()}. Раньше здесь стояла отгрузка таких чанков
     * без сохранения, но чанк, в котором стоит сам строитель, отгрузить невозможно - и его
     * содержимое всё равно уходило на диск. Теперь блоки удаляются, а очищенный чанк
     * сохраняется намеренно: так стирается и то, что успело записаться раньше.
     */
    private void dropBlocksOutsideLevel(@NonNull Level level) {
        for (org.bukkit.Chunk chunk : level.getWorld().getLoadedChunks()) {
            if (level.isChunkInside(chunk)) continue;
            // Пустой чанк за границей записывать на диск незачем - отгружаем без сохранения.
            if (!OutsideBlocksCleaner.clearChunk(chunk)) chunk.unload(false);
        }
    }

    public void saveLevelSettingsAndBlocks(@NonNull Level level) {
        this.saveLevelSettings(level.getUniqueId());
        try {
            World world = level.getWorld();
            this.dropBlocksOutsideLevel(level);

            world.save();
        } catch (Exception e) {
            this.plugin
                .getLogger()
                .log(
                    java.util.logging.Level.SEVERE,
                    "Unable to save world " + level.getWorld().getName(),
                    e);
        }
    }

    @Nullable
    public Level getLoadedLevel(@NonNull UUID levelId) {
        return this.loadedLevelsById.get(levelId);
    }

    @Nullable
    public Level getLoadedLevel(@NonNull World world) {
        return this.loadedLevelsByWorld.get(world);
    }

    @Nullable
    public GameSettings getAvailableLevelSettings(@NonNull UUID levelId) {
        return this.availableLevels.byUniqueId(levelId);
    }

    @NonNull
    public List<String> getUniqueLevelNames(@NonNull String levelNamePrefix, @Nullable CommandSender owner, boolean bypassForAdmins) {
        levelNamePrefix = levelNamePrefix.toLowerCase();

        List<String> result = new ArrayList<>();

        String uniqueName;
        if (owner == null) {
            for (GameSettings gameSettings : this.availableLevels.withUniqueNames()) {
                uniqueName = gameSettings.getUniqueName();
                if (uniqueName == null || !uniqueName.startsWith(levelNamePrefix)) continue;
                result.add(uniqueName);
            }
        } else {
            for (GameSettings gameSettings : this.availableLevels.withUniqueNames()) {
                if (!gameSettings.canEdit(owner, bypassForAdmins, false)) continue;
                uniqueName = gameSettings.getUniqueName();
                if (uniqueName == null || !uniqueName.startsWith(levelNamePrefix)) continue;
                result.add(uniqueName);
            }
        }

        return result;
    }

    public void prepareLevelWorld(@NonNull World world, boolean updateGameRules) {
        world.setKeepSpawnInMemory(false);
        world.setAutoSave(false);

        setBooleanGameRule(world, "SPECTATORS_GENERATE_CHUNKS", true);

        if (!updateGameRules) return;

        setBooleanGameRule(world, "ANNOUNCE_ADVANCEMENTS", false);
        setBooleanGameRule(world, "DISABLE_ELYTRA_MOVEMENT_CHECK", true);
        setBooleanGameRule(world, "DO_DAYLIGHT_CYCLE", false);
        setBooleanGameRule(world, "DO_ENTITY_DROPS", false);
        setBooleanGameRule(world, "DO_FIRE_TICK", false);
        setBooleanGameRule(world, "DO_LIMITED_CRAFTING", true);
        setBooleanGameRule(world, "DO_MOB_LOOT", false);
        setBooleanGameRule(world, "DO_MOB_SPAWNING", false);
        setBooleanGameRule(world, "DO_TILE_DROPS", false);
        setBooleanGameRule(world, "DO_WEATHER_CYCLE", false);
        setBooleanGameRule(world, "KEEP_INVENTORY", true);
        setBooleanGameRule(world, "LOG_ADMIN_COMMANDS", true);
        setBooleanGameRule(world, "MOB_GRIEFING", false);
        setBooleanGameRule(world, "NATURAL_REGENERATION", false);
        setBooleanGameRule(world, "REDUCED_DEBUG_INFO", false);
        setBooleanGameRule(world, "SHOW_DEATH_MESSAGES", false);
        setBooleanGameRule(world, "DISABLE_RAIDS", true);
        setBooleanGameRule(world, "DO_INSOMNIA", false);
        setBooleanGameRule(world, "DO_IMMEDIATE_RESPAWN", true);
        setBooleanGameRule(world, "DROWNING_DAMAGE", false);
        setBooleanGameRule(world, "FALL_DAMAGE", false);
        setBooleanGameRule(world, "FIRE_DAMAGE", false);
        setBooleanGameRule(world, "DO_PATROL_SPAWNING", false);
        setBooleanGameRule(world, "DO_TRADER_SPAWNING", false);
        setBooleanGameRule(world, "FORGIVE_DEAD_PLAYERS", true);
        setBooleanGameRule(world, "UNIVERSAL_ANGER", false);
        setIntegerGameRule(world, "RANDOM_TICK_SPEED", 0);
        setIntegerGameRule(world, "SPAWN_RADIUS", 0);
    }

    @Nullable
    private static GameRule<?> findGameRule(@NonNull String name) {
        try {
            Object value = GameRule.class.getField(name).get(null);
            if (value instanceof GameRule) return (GameRule<?>) value;
        } catch (Throwable ignored) {
        }
        return GameRule.getByName(name);
    }

    private void setBooleanGameRule(@NonNull World world, @NonNull String name, boolean newValue) {
        GameRule<?> rule = findGameRule(name);
        if (rule == null || rule.getType() != Boolean.class) return;
        world.setGameRule((GameRule<Boolean>) rule, newValue);
    }

    private void setIntegerGameRule(@NonNull World world, @NonNull String name, int newValue) {
        GameRule<?> rule = findGameRule(name);
        if (rule == null || rule.getType() != Integer.class) return;
        world.setGameRule((GameRule<Integer>) rule, newValue);
    }

    @Nullable
    public GameSettings findLevel(@NonNull String levelUniqueNameOrIdOrNumber) {
        UUID levelId = StringUtils.parseUUID(levelUniqueNameOrIdOrNumber);
        if (levelId != null) {
            return this.availableLevels.byUniqueId(levelId);
        }
        try {
            return this.availableLevels.byUniqueNumber(Integer.parseInt(levelUniqueNameOrIdOrNumber));
        } catch (NumberFormatException e) {
            return this.availableLevels.byUniqueName(levelUniqueNameOrIdOrNumber);
        }
    }

    @Override
    public void disable() {
        if (!this.particlesRenderingTask.isCancelled()) {
            this.particlesRenderingTask.cancel();
        }
        if (!this.autoSaveTask.isCancelled()) {
            this.autoSaveTask.cancel();
        }

        Location spawn = Settings.getLobbySpawn();
        for (Map.Entry<World, Level> entry : this.loadedLevelsByWorld.entrySet()) {
            Level level = entry.getValue();
            if (!level.isEditing()) continue;

            World world = entry.getKey();

            this.levelsSettings.saveLevelSettings(level.getUniqueId());
            try {
                this.dropBlocksOutsideLevel(level);
                world.save();
            } catch (Exception e) {
                this.plugin.getLogger().log(
                    java.util.logging.Level.SEVERE,
                    "Unable to save world " + world.getName() + " on disable",
                    e);
            }

            level.setEditing(false);

            this.worldsManager.unloadBukkitWorld(
                world,
                true,
                level::isChunkInside,
                spawn,
                false
            );
        }
    }

    public void addParticleController(@NonNull ParticleController controller) {
        this.particleControllers.add(controller);
    }

    public void removeParticleController(@NonNull ParticleController controller) {
        this.particleControllers.remove(controller);
    }
}
