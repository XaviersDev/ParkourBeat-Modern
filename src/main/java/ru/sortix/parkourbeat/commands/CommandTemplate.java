package ru.sortix.parkourbeat.commands;

import ru.sortix.parkourbeat.utils.lang.PlayerLang;

import ru.sortix.parkourbeat.utils.lang.Lang;

import dev.rollczi.litecommands.annotations.argument.Arg;
import dev.rollczi.litecommands.annotations.command.Command;
import dev.rollczi.litecommands.annotations.context.Context;
import dev.rollczi.litecommands.annotations.execute.Execute;
import dev.rollczi.litecommands.annotations.permission.Permission;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import lombok.NonNull;
import ru.sortix.parkourbeat.ParkourBeat;
import ru.sortix.parkourbeat.activity.ActivityManager;
import ru.sortix.parkourbeat.activity.UserActivity;
import ru.sortix.parkourbeat.activity.type.EditActivity;
import ru.sortix.parkourbeat.data.Settings;
import ru.sortix.parkourbeat.levels.Level;
import ru.sortix.parkourbeat.levels.LevelsManager;
import ru.sortix.parkourbeat.levels.dao.LevelSettingDAO;
import ru.sortix.parkourbeat.levels.dao.files.FileLevelSettingDAO;
import ru.sortix.parkourbeat.levels.settings.WorldSettings;
import ru.sortix.parkourbeat.utils.java.CopyDirVisitor;

import java.io.File;
import java.nio.file.Files;

@Command(name = "template")
public class CommandTemplate {

    private final ParkourBeat plugin;

    public CommandTemplate(ParkourBeat plugin) {
        this.plugin = plugin;
    }

    @Execute(name = "set")
    @Permission("parkourbeat.command.template.set")
    public void onCommand(@Context Player sender, @Arg("environment") String envName,
                          @Arg("size") java.util.Optional<String> sizeArg) {
        // Второй аргумент - размер базы: "4c" сохраняет шаблон для широких уровней,
        // без него всё работает как раньше, для обычных одночанковых.
        boolean wide = sizeArg.isPresent() && sizeArg.get().equalsIgnoreCase("4c");

        // 2d_normal / 2d_nether / 2d_the_end - шаблоны двумерных уровней.
        // Измерение у них обычное, отличается только папка, в которую ложится база.
        String rawEnv = envName.trim();
        boolean twoD = rawEnv.toLowerCase(java.util.Locale.ROOT).startsWith("2d_");
        if (twoD) rawEnv = rawEnv.substring(3);

        World.Environment targetEnv;
        try {
            targetEnv = World.Environment.valueOf(rawEnv.toUpperCase());
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Lang.raw(PlayerLang.of(sender), "auto.command_template.on_command.1")
                + Lang.raw(PlayerLang.of(sender), "auto.command_template.on_command.2"));
            return;
        }

        UserActivity activity = plugin.get(ActivityManager.class).getActivity(sender);
        if (!(activity instanceof EditActivity)) {
            sender.sendMessage(Lang.raw(PlayerLang.of(sender), "auto.command_template.on_command.3"));
            return;
        }
        EditActivity editActivity = (EditActivity) activity;
        Level level = editActivity.getLevel();
        World world = level.getWorld();

        final boolean isTwoD = twoD;
        sender.sendMessage(Lang.raw(PlayerLang.of(sender), "auto.command_template.on_command.4")
            + (isTwoD ? "2D " : "") + (wide ? Lang.raw(PlayerLang.of(sender), "auto.command_template.on_command.5") : "")
            + Lang.raw(PlayerLang.of(sender), "auto.command_template.on_command.6") + targetEnv.name() + "...");

        world.save();
        plugin.get(LevelsManager.class).saveLevelSettings(level.getUniqueId());

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                LevelSettingDAO baseDao = plugin.get(LevelsManager.class).getLevelsSettings().getLevelSettingDAO();
                if (!(baseDao instanceof FileLevelSettingDAO)) {
                    sender.sendMessage(Lang.raw(PlayerLang.of(sender), "auto.command_template.on_command.7"));
                    return;
                }
                FileLevelSettingDAO dao = (FileLevelSettingDAO) baseDao;

                File targetDir = new File(plugin.getDataFolder(),
                    "pb_default_level_" + (isTwoD ? "2D_" : "") + targetEnv.name()
                        + (wide ? "_4C" : ""));
                deleteDirectory(targetDir);
                targetDir.mkdirs();

                // ЧЁРНОЕ НЕБО. Готовый шаблон копируется в новый мир целиком, как есть.
                // Раньше сюда клали только регионы, и level.dat терялся - Bukkit создавал
                // его заново со своими настройками измерения. Небо (точнее, нижняя его
                // половина под горизонтом) в таком мире перестаёт рисоваться: тот самый
                // MC-186115 / MC-257056. Штатный шаблон из поставки level.dat содержал,
                // поэтому до первого /template set всё выглядело правильно.
                copyWorldMeta(plugin, world.getWorldFolder(), targetDir);

                File sourceRegionDir = new File(world.getWorldFolder(), getRegionFolder(world.getEnvironment()));
                File targetRegionDir = new File(targetDir, getRegionFolder(targetEnv));

                if (targetRegionDir.getParentFile() != null) {
                    targetRegionDir.getParentFile().mkdirs();
                }
                targetRegionDir.mkdirs();

                if (sourceRegionDir.exists()) {
                    Files.walkFileTree(sourceRegionDir.toPath(), new CopyDirVisitor(plugin.getLogger(), sourceRegionDir.toPath(), targetRegionDir.toPath()));
                }

                File sourceSettingsDir = new File(dao.getBukkitWorldDirectory(level.getUniqueId()), "parkourbeat");
                File targetSettingsDir = new File(targetDir, "parkourbeat");
                targetSettingsDir.mkdirs();

                File sourceWorldSettingsFile = new File(sourceSettingsDir, "world_settings.yml");
                File targetWorldSettingsFile = new File(targetSettingsDir, "world_settings.yml");

                if (sourceWorldSettingsFile.exists()) {
                    YamlConfiguration config = YamlConfiguration.loadConfiguration(sourceWorldSettingsFile);
                    config.set("environment", targetEnv.name());

                    // ЧЁРНОЕ НЕБО. Раньше в шаблон уезжал файл настроек уровня ЦЕЛИКОМ,
                    // вместе со световым шоу: базовым небом, метками смены неба, вспышками,
                    // погодой и биомными зонами. Всё это привязано к музыке конкретного
                    // уровня, а новый уровень пустой - метки срабатывают в неподходящий
                    // момент и оставляют небо тёмным. Ночное базовое небо строителя точно
                    // так же наследовалось каждым новым уровнем.
                    //
                    // Шаблон - это постройка, спавн и точки маршрута. Оформление света
                    // у каждого уровня своё, поэтому сбрасываем его на стандартное.
                    config.set("lightshow", null);
                    config.set("glowing_barriers", null);

                    // Старт и финиш нигде отдельно не хранятся - они вычисляются как
                    // первая и последняя точка пути из частиц. Значит, в шаблон должен
                    // попасть сам путь; проверяем это явно, чтобы админ не гадал, почему
                    // новые уровни получились с точками из старой базы.
                    int waypointsCount = config.getStringList("waypoints").size();

                    config.save(targetWorldSettingsFile);

                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        try {
                            WorldSettings newDefault = dao.loadLevelWorldSettings(targetSettingsDir);
                            if (isTwoD) {
                                Settings.getTwoDDefaultSettings().put(targetEnv, newDefault);
                            } else if (wide) {
                                Settings.getWideDefaultSettings().put(targetEnv, newDefault);
                            } else {
                                Settings.getDefaultSettings().put(targetEnv, newDefault);
                            }
                            sender.sendMessage(Lang.raw(PlayerLang.of(sender), "auto.command_template.on_command.8") + (isTwoD ? "2D " : "")
                                + (wide ? Lang.raw(PlayerLang.of(sender), "auto.command_template.on_command.9") : "")
                                + Lang.raw(PlayerLang.of(sender), "auto.command_template.on_command.10") + targetEnv.name() + Lang.raw(PlayerLang.of(sender), "auto.command_template.on_command.11"));
                            sender.sendMessage(Lang.raw(PlayerLang.of(sender), "auto.command_template.on_command.12")
                                + Lang.raw(PlayerLang.of(sender), "auto.command_template.on_command.13"));
                            if (waypointsCount >= 2) {
                                sender.sendMessage(Lang.raw(PlayerLang.of(sender), "auto.command_template.on_command.14")
                                    + waypointsCount + Lang.raw(PlayerLang.of(sender), "auto.command_template.on_command.15")
                                    + Lang.raw(PlayerLang.of(sender), "auto.command_template.on_command.16"));
                            } else {
                                sender.sendMessage(Lang.raw(PlayerLang.of(sender), "auto.command_template.on_command.17")
                                    + Lang.raw(PlayerLang.of(sender), "auto.command_template.on_command.18")
                                    + Lang.raw(PlayerLang.of(sender), "auto.command_template.on_command.19"));
                            }
                        } catch (Exception e) {
                            sender.sendMessage(Lang.raw(PlayerLang.of(sender), "auto.command_template.on_command.20"));
                            e.printStackTrace();
                        }
                    });
                } else {
                    sender.sendMessage(Lang.raw(PlayerLang.of(sender), "auto.command_template.on_command.21"));
                }
            } catch (Exception e) {
                sender.sendMessage(Lang.raw(PlayerLang.of(sender), "auto.command_template.on_command.22"));
                e.printStackTrace();
            }
        });
    }

    /**
     * Файлы мира, которые нельзя переносить в шаблон: они привязаны к конкретному
     * запущенному миру или к конкретным игрокам.
     */
    private static final java.util.Set<String> SKIPPED_WORLD_FILES = java.util.Set.of(
        "session.lock", "uid.dat", "level.dat_old",
        "playerdata", "stats", "advancements", "parkourbeat",
        "region", "DIM-1", "DIM1"
    );

    /**
     * Переносит в шаблон всё, кроме регионов (их кладут отдельно, с учётом смены
     * измерения) и служебных файлов. Главное здесь - level.dat.
     */
    private void copyWorldMeta(@NonNull ParkourBeat plugin, @NonNull File worldFolder,
                               @NonNull File targetDir) throws java.io.IOException {
        File[] entries = worldFolder.listFiles();
        if (entries == null) return;

        for (File entry : entries) {
            if (SKIPPED_WORLD_FILES.contains(entry.getName())) continue;

            File target = new File(targetDir, entry.getName());
            if (entry.isDirectory()) {
                target.mkdirs();
                Files.walkFileTree(entry.toPath(),
                    new CopyDirVisitor(plugin.getLogger(), entry.toPath(), target.toPath()));
            } else {
                Files.copy(entry.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private String getRegionFolder(World.Environment env) {
        switch (env) {
            case NETHER: return "DIM-1/region";
            case THE_END: return "DIM1/region";
            default: return "region";
        }
    }

    private void deleteDirectory(File directory) {
        if (!directory.exists()) return;
        File[] allContents = directory.listFiles();
        if (allContents != null) {
            for (File file : allContents) {
                deleteDirectory(file);
            }
        }
        directory.delete();
    }
}
