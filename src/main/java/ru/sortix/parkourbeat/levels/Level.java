package ru.sortix.parkourbeat.levels;

import lombok.Getter;
import lombok.NonNull;
import net.kyori.adventure.text.Component;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import ru.sortix.parkourbeat.constant.PermissionConstants;
import ru.sortix.parkourbeat.data.Settings;
import ru.sortix.parkourbeat.levels.settings.GameSettings;
import ru.sortix.parkourbeat.levels.settings.LevelSettings;
import ru.sortix.parkourbeat.levels.settings.LightShowSettings;
import ru.sortix.parkourbeat.utils.lang.LangOptions;
import ru.sortix.parkourbeat.world.Cuboid;

import java.util.UUID;

@Getter
public class Level {
    private final @NonNull LevelSettings levelSettings;
    private final @NonNull World world;
    private final @NonNull Cuboid cuboid;
    private boolean isEditing = false;

    public Level(@NonNull LevelSettings levelSettings, @NonNull World world) {
        this.levelSettings = levelSettings;
        this.world = world;
        DirectionChecker.Direction direction = this.levelSettings.getWorldSettings().getDirection();
        Cuboid configured = Settings.getLevelFixedEditableArea().get(direction);
        if (configured == null) {
            throw new IllegalArgumentException("Not fond config of direction " + direction);
        }
        // В конфиге записана область на один чанк. Широкий уровень занимает столько же
        // по длине, но шире по поперечной оси, поэтому растягиваем её здесь, а не
        // держим в конфиге отдельный блок на каждый размер.
        this.cuboid = extendBack(
            widen(configured, this.levelSettings.getGameSettings().getWidthInBlocks()),
            direction);
        // Контроллер частиц обязан знать свой уровень СРАЗУ. Скрытие пути внутри порталов
        // решается на этапе сборки списка частиц, а раньше уровень проставлялся уже ПОСЛЕ
        // первой сборки — и путь сквозь порталы оставался видимым до первой правки портала.
        this.levelSettings.getParticleController().setColorCueLevel(this);
    }

    /**
     * Насколько область строительства продлевается НАЗАД, против хода уровня.
     * <p>
     * Задник уровня (то, что игрок видит за спиной на старте, и место под оформление
     * перед первым прыжком) раньше упирался в границу из конфига. Продление идёт
     * только назад, поэтому ни на длину самого уровня, ни на его ширину это не влияет.
     */
    public static final int EXTRA_BACK_LENGTH = 1024;

    @NonNull
    private static Cuboid extendBack(@NonNull Cuboid base,
                                     @NonNull DirectionChecker.Direction direction) {
        org.bukkit.util.Vector min = base.getMin();
        org.bukkit.util.Vector max = base.getMax();

        double extra = EXTRA_BACK_LENGTH;
        return switch (direction) {
            case POSITIVE_X -> new Cuboid(
                new org.bukkit.util.Vector(min.getX() - extra, min.getY(), min.getZ()),
                max.clone(), base.getWorld());
            case NEGATIVE_X -> new Cuboid(
                min.clone(),
                new org.bukkit.util.Vector(max.getX() + extra, max.getY(), max.getZ()),
                base.getWorld());
            case POSITIVE_Z -> new Cuboid(
                new org.bukkit.util.Vector(min.getX(), min.getY(), min.getZ() - extra),
                max.clone(), base.getWorld());
            case NEGATIVE_Z -> new Cuboid(
                min.clone(),
                new org.bukkit.util.Vector(max.getX(), max.getY(), max.getZ() + extra),
                base.getWorld());
        };
    }

    /**
     * @param widthInBlocks желаемая ширина по оси Z в блоках
     */
    @NonNull
    private static Cuboid widen(@NonNull Cuboid base, int widthInBlocks) {
        org.bukkit.util.Vector min = base.getMin();
        org.bukkit.util.Vector max = base.getMax();

        int currentWidth = (int) (max.getZ() - min.getZ()) + 1;
        if (widthInBlocks <= currentWidth) return base;

        // Площадка расширяется В ОБЕ СТОРОНЫ поровну, а не только вперёд по Z.
        // Тогда исходная полоса в один чанк оказывается ровно посередине широкой,
        // и при смене ширины уже построенному уровню не нужно двигать ни единого
        // блока: постройка, спавн и путь остаются на своих координатах и при этом
        // становятся центром. Перенос миллионов блоков ради того же результата -
        // долгая и опасная операция, которой здесь просто нет места.
        int extra = widthInBlocks - currentWidth;
        int back = extra / 2;
        int forward = extra - back;

        return new Cuboid(
            new org.bukkit.util.Vector(min.getX(), min.getY(), min.getZ() - back),
            new org.bukkit.util.Vector(max.getX(), max.getY(), max.getZ() + forward),
            base.getWorld());
    }

    public void setEditing(boolean isEditing) {
        this.isEditing = isEditing;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof Level)) return false;
        return ((Level) other).getUniqueId().equals(this.getUniqueId());
    }

    @Override
    public int hashCode() {
        return this.getUniqueId().hashCode();
    }

    @NonNull
    public Component getDisplayName() {
        return this.levelSettings.getGameSettings().getDisplayName();
    }

    @NonNull
    public UUID getUniqueId() {
        return this.levelSettings.getGameSettings().getUniqueId();
    }

    @NonNull
    public Location getSpawn() {
        return this.levelSettings.getWorldSettings().getSpawn();
    }

    /**
     * Pushes the view distances of this level into the runtime pieces that use them.
     */
    public void applyViewDistances() {
        this.levelSettings.getParticleController()
            .setViewDistance(this.levelSettings.getWorldSettings().getParticleViewDistance());

        this.levelSettings.getParticleController().setColorCueLevel(this);
        this.levelSettings.getParticleController()
            .setColorCues(this.getLightShow().getParticleColorCues());
    }

    public void refreshParticleColorCues() {
        this.levelSettings.getParticleController().setColorCueLevel(this);
        this.levelSettings.getParticleController()
            .setColorCues(this.getLightShow().getParticleColorCues());
    }

    @NonNull
    public LightShowSettings getLightShow() {
        return this.levelSettings.getWorldSettings().getLightShow();
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean isLevelAccessibleForPlaying(@NonNull Player player, boolean bypassForAdmins, boolean sendMessages) {
        GameSettings settings = this.levelSettings.getGameSettings();
        if (settings.isAccessibleForPlaying(player, bypassForAdmins)) return true;
        if (sendMessages) LangOptions.level_play_noaccess.sendMsg(player);
        return false;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean isLevelAccessibleForEditing(@NonNull Player player, boolean bypassForAdmins, boolean sendMessages) {
        GameSettings settings = this.levelSettings.getGameSettings();
        if (!settings.canEdit(player, bypassForAdmins, false)) {
            if (sendMessages) LangOptions.level_editor_cantedit_notowner.sendMsg(player);
            return false;
        }
        if (settings.getModerationStatus() == ModerationStatus.ON_MODERATION
            && !player.hasPermission(PermissionConstants.EDIT_OTHERS_LEVELS_ON_MODERATION)
        ) {
            if (sendMessages) LangOptions.level_editor_cantedit_onmoderation.sendMsg(player);
            return false;
        }
        if (sendMessages) settings.canEdit(player, bypassForAdmins, true); // send bypass message
        return true;
    }

    public boolean isLocationInside(@NonNull Location location) {
        if (location.getWorld() != this.world) return false;
        return this.cuboid.isInside(location);
    }

    public boolean isPositionInside(double x, double y, double z) {
        return this.cuboid.isInside(x, y, z);
    }

    @SuppressWarnings({"PointlessBitwiseExpression", "OctalInteger", "RedundantIfStatement"})
    public boolean isChunkInside(@NonNull Chunk chunk) {
        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();

        if (this.isPositionInside((chunkX << 4) | 00, 0, (chunkZ << 4) | 00)) return true;
        if (this.isPositionInside((chunkX << 4) | 15, 0, (chunkZ << 4) | 00)) return true;
        if (this.isPositionInside((chunkX << 4) | 00, 0, (chunkZ << 4) | 15)) return true;
        if (this.isPositionInside((chunkX << 4) | 15, 0, (chunkZ << 4) | 15)) return true;

        return false;
    }
}
