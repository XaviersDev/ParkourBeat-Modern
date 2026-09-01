package ru.sortix.parkourbeat.levels.settings;

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import ru.sortix.parkourbeat.constant.PermissionConstants;
import ru.sortix.parkourbeat.levels.LevelDifficulty;
import ru.sortix.parkourbeat.levels.ModerationStatus;
import ru.sortix.parkourbeat.player.music.MusicTrack;
import ru.sortix.parkourbeat.utils.lang.LangOptions;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Getter
public class GameSettings {
    public static final int MAX_CO_EDITORS = 16;

    private final @NonNull UUID uniqueId;
    private final @Nullable String uniqueName;
    private final int uniqueNumber;

    private final @NonNull UUID ownerId;
    private final @NonNull String ownerName;
    @Setter
    private @NonNull Component displayName;

    private final long createdAtMills;
    private @Setter boolean customPhysicsEnabled;
    @Nullable
    private @Setter MusicTrack musicTrack;
    private @Setter boolean useTrackPieces;
    @Setter
    private @NonNull ModerationStatus moderationStatus;
    @Setter
    private boolean publicVisible;
    @Setter
    private @NonNull LevelBossBarColor bossBarColor = LevelBossBarColor.DEFAULT;
    @Setter
    private boolean hideBossBar = false;
    @Setter
    private double borderPushStrength = 0.0D;

    @Setter
    private @NonNull LevelDifficulty difficulty = LevelDifficulty.N_A;

    /**
     * Минимально возможный множитель сложности уровня (обычная игра).
     */
    public static final double MIN_DIFFICULTY_MULTIPLIER = 1.0D;
    /**
     * Максимально возможный множитель сложности уровня.
     */
    public static final double MAX_DIFFICULTY_MULTIPLIER = 10.0D;

    /**
     * Реальный множитель сложности уровня, который задаёт строитель в редакторе.
     * <p>
     * Это НЕ рейтинговое название сложности ({@link LevelDifficulty}: EXPERT, EXPERT+ и т.д.),
     * а именно физическая жёсткость геймплея:
     * <ul>
     *     <li>сильно сужаются окна попадания по прыжкам (+300 становится почти игольным ушком);</li>
     *     <li>резко ужесточается проверка точности движения по пути;</li>
     *     <li>урон по игроку становится намного сильнее и наносится намного чаще.</li>
     * </ul>
     * По умолчанию 1.0 — ровно то поведение, которое было до появления этой настройки.
     */
    private double difficultyMultiplier = MIN_DIFFICULTY_MULTIPLIER;

    public void setDifficultyMultiplier(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) value = MIN_DIFFICULTY_MULTIPLIER;
        this.difficultyMultiplier = Math.max(MIN_DIFFICULTY_MULTIPLIER,
            Math.min(MAX_DIFFICULTY_MULTIPLIER, value));
    }

    /**
     * @return true, если строитель поднял сложность выше стандартной.
     */
    public boolean isHardcoreDifficulty() {
        return this.difficultyMultiplier > MIN_DIFFICULTY_MULTIPLIER + 1.0E-6D;
    }

    /**
     * Сложность в виде "+9" без цвета.
     */
    @NonNull
    public static String formatDifficultyValue(double value) {
        String formatted = String.format(java.util.Locale.ROOT, "%.2f", value);
        while (formatted.endsWith("0")) formatted = formatted.substring(0, formatted.length() - 1);
        if (formatted.endsWith(".")) formatted = formatted.substring(0, formatted.length() - 1);
        return "+" + formatted;
    }

    /**
     * Цветовой префикс сложности: чем выше значение, тем горячее цвет.
     * До 3 — оранжевый, до 6 — светло-красный, дальше — красный.
     */
    @NonNull
    public static String getDifficultyColorPrefix(double value) {
        // Сложность читается по цвету, поэтому цвета прямые, а не из палитры.
        if (value < 3.0D) return ru.sortix.parkourbeat.utils.text.Theme.V_GOLD;
        if (value < 6.0D) return ru.sortix.parkourbeat.utils.text.Theme.V_RED;
        return ru.sortix.parkourbeat.utils.text.Theme.V_DARK_RED;
    }

    /**
     * Сложность в виде цветного "+9" для лора и чата.
     */
    @NonNull
    public static String formatDifficultyColored(double value) {
        return getDifficultyColorPrefix(value) + formatDifficultyValue(value);
    }

    /**
     * Сложность этого уровня в виде цветного "+9".
     */
    @NonNull
    public String getFormattedDifficulty() {
        return formatDifficultyColored(this.difficultyMultiplier);
    }

    // ==================== ПОПЫТКИ НА ЧЕКПОИНТАХ ====================

    public static final int MIN_CHECKPOINT_ATTEMPTS = 1;
    public static final int MAX_CHECKPOINT_ATTEMPTS = 4;
    public static final int DEFAULT_CHECKPOINT_ATTEMPTS = 3;

    /**
     * Сколько раз игрока вернёт на чекпоинт, прежде чем уровень будет провален.
     * Ограничение нужно, чтобы игрок не застревал на уровне навсегда.
     */
    private int checkpointAttempts = DEFAULT_CHECKPOINT_ATTEMPTS;

    public void setCheckpointAttempts(int value) {
        this.checkpointAttempts = Math.max(MIN_CHECKPOINT_ATTEMPTS,
            Math.min(MAX_CHECKPOINT_ATTEMPTS, value));
    }

    // ==================== НАРЕЗКА ТРЕКА ПОД ЧЕКПОИНТЫ ====================

    /**
     * Id плейлиста AMusic, в котором лежит нарезка трека под чекпоинты этого уровня.
     * Отдельный плейлист нужен потому, что один и тот же трек может стоять сразу на
     * нескольких уровнях с разными чекпоинтами — резать исходник на месте нельзя.
     * <p>
     * null — нарезки нет, играется обычный цельный трек.
     */
    @Setter
    private @Nullable String slicedPlaylistId = null;

    /**
     * Реальные длительности кусков трека в миллисекундах, как их отдал ffmpeg на прокси.
     * Именно по ним заводится следующий кусок, а не по расчётным отметкам чекпоинтов:
     * ffmpeg режет по границам страниц ogg и промахивается на десятки миллисекунд.
     */
    private final java.util.List<Integer> sliceDurationsMillis = new java.util.ArrayList<>();

    /**
     * Отметки чекпоинтов (мс от начала трека), под которые делалась текущая нарезка.
     * Если строитель после нарезки подвинул чекпоинт — отметки разойдутся, и уровень
     * честно скажет, что нарезка устарела.
     */
    private final java.util.List<Integer> sliceOffsetsMillis = new java.util.ArrayList<>();

    @NonNull
    public java.util.List<Integer> getSliceDurationsMillis() {
        return Collections.unmodifiableList(this.sliceDurationsMillis);
    }

    @NonNull
    public java.util.List<Integer> getSliceOffsetsMillis() {
        return Collections.unmodifiableList(this.sliceOffsetsMillis);
    }

    public void setSliceResult(@Nullable String playlistId,
                               @NonNull java.util.List<Integer> offsetsMillis,
                               @NonNull java.util.List<Integer> durationsMillis) {
        this.slicedPlaylistId = playlistId;
        this.sliceOffsetsMillis.clear();
        this.sliceOffsetsMillis.addAll(offsetsMillis);
        this.sliceDurationsMillis.clear();
        this.sliceDurationsMillis.addAll(durationsMillis);
    }

    public void clearSliceResult() {
        this.slicedPlaylistId = null;
        this.sliceOffsetsMillis.clear();
        this.sliceDurationsMillis.clear();
    }

    /**
     * @return true, если нарезка есть и она рабочая: кусков ровно на один больше,
     * чем чекпоинтов, и у каждого куска известна длительность.
     */
    public boolean hasUsableSlices(int checkpointsAmount) {
        if (this.slicedPlaylistId == null || this.slicedPlaylistId.isEmpty()) return false;
        if (checkpointsAmount <= 0) return false;
        if (this.sliceOffsetsMillis.size() != checkpointsAmount) return false;
        if (this.sliceDurationsMillis.size() != checkpointsAmount + 1) return false;
        for (int duration : this.sliceDurationsMillis) {
            if (duration <= 0) return false;
        }
        return true;
    }

    /**
     * Нарезка перестала соответствовать чекпоинтам: их подвинули, добавили или удалили.
     */
    public boolean isSliceOutdated(@NonNull java.util.List<Integer> currentOffsetsMillis) {
        if (this.slicedPlaylistId == null) return !currentOffsetsMillis.isEmpty();
        if (this.sliceOffsetsMillis.size() != currentOffsetsMillis.size()) return true;
        for (int i = 0; i < currentOffsetsMillis.size(); i++) {
            // 250 мс запаса: отметка считается по позиции игрока и слегка плавает
            if (Math.abs(this.sliceOffsetsMillis.get(i) - currentOffsetsMillis.get(i)) > 250) return true;
        }
        return false;
    }

    // ==================== РЕЖИМ УРОВНЯ (3D/2D) ====================

    /**
     * Обычный паркур или двумерный 2D-уровень.
     * <p>
     * У старых уровней поля в файле нет, и читаются они как 3D — то есть ровно так,
     * как работали всегда.
     */
    private @NonNull ru.sortix.parkourbeat.twod.LevelMode levelMode =
        ru.sortix.parkourbeat.twod.LevelMode.THREE_D;

    /** Настройки 2D-уровня: спавн кубика, длина линии, монетки. */
    private @NonNull ru.sortix.parkourbeat.twod.TwoDLevelSettings twoDSettings =
        new ru.sortix.parkourbeat.twod.TwoDLevelSettings();

    public void setLevelMode(@NonNull ru.sortix.parkourbeat.twod.LevelMode levelMode) {
        this.levelMode = levelMode;
    }

    public void setTwoDSettings(@NonNull ru.sortix.parkourbeat.twod.TwoDLevelSettings twoDSettings) {
        this.twoDSettings = twoDSettings;
    }

    public boolean isTwoDLevel() {
        return this.levelMode.isTwoD();
    }

    /** У уровня загружен собственный ресурспак с текстурами. */
    @Setter
    private boolean customTextures = false;

    /**
     * Ширина уровня в чанках: 1 (как было всегда) или 4.
     * <p>
     * Хранится числом, а не флагом, чтобы позже можно было добавить другие размеры,
     * не трогая формат сохранения.
     */
    private int chunkWidth = 1;

    /**
     * @return ширина уровня в блоках по поперечной оси
     */
    public int getWidthInBlocks() {
        return Math.max(1, this.chunkWidth) * 16;
    }

    public void setChunkWidth(int chunkWidth) {
        // Поддерживаем ровно два размера: всё прочее ломает границы редактирования.
        this.chunkWidth = chunkWidth >= 4 ? 4 : 1;
    }
    @Setter
    private @Nullable ru.sortix.parkourbeat.levels.TextureVersionRange textureVersionRange = null;

    private final Map<UUID, String> coEditors = new LinkedHashMap<>();
    private final Set<UUID> trustedCoEditors = new HashSet<>();
    private final Map<UUID, LevelDifficulty> playerRatings = new java.util.HashMap<>();

    public Map<UUID, LevelDifficulty> getPlayerRatings() {
        return Collections.unmodifiableMap(this.playerRatings);
    }

    public void setPlayerRating(UUID uuid, LevelDifficulty diff) {
        this.playerRatings.put(uuid, diff);
    }

    public GameSettings(@NonNull UUID uniqueId, @Nullable String uniqueName, int uniqueNumber, @NonNull UUID ownerId, @NonNull String ownerName, @NonNull Component displayName, long createdAtMills, @NonNull ModerationStatus moderationStatus) {
        this.uniqueId = uniqueId;
        this.uniqueName = uniqueName;
        this.uniqueNumber = uniqueNumber;
        this.ownerId = ownerId;
        this.ownerName = ownerName;
        this.displayName = displayName;
        this.createdAtMills = createdAtMills;
        this.moderationStatus = moderationStatus;
    }

    public GameSettings(@NonNull UUID uniqueId, @Nullable String uniqueName, int uniqueNumber, @NonNull UUID ownerId, @NonNull String ownerName, @NonNull Component displayName, long createdAtMills, boolean customPhysicsEnabled, @Nullable MusicTrack musicTrack, boolean useTrackPieces, @NonNull ModerationStatus moderationStatus, boolean publicVisible) {
        this(uniqueId, uniqueName, uniqueNumber, ownerId, ownerName, displayName, createdAtMills, moderationStatus);
        this.customPhysicsEnabled = customPhysicsEnabled;
        this.musicTrack = musicTrack;
        this.useTrackPieces = useTrackPieces;
        this.publicVisible = publicVisible;
    }

    @NonNull
    public Component getDisplayName() {
        return this.displayName.colorIfAbsent(NamedTextColor.GOLD);
    }

    @NonNull
    public String getDisplayNameLegacy() {
        return LegacyComponentSerializer.legacySection().serialize(this.displayName.colorIfAbsent(NamedTextColor.GOLD));
    }

    @NonNull
    public String getDisplayNameLegacy(boolean useDefaultColor) {
        if (useDefaultColor) return this.getDisplayNameLegacy();
        return LegacyComponentSerializer.legacySection().serialize(this.displayName);
    }

    public boolean isOwner(@NonNull UUID playerId) {
        return this.ownerId.equals(playerId);
    }

    public boolean isOwner(@NonNull CommandSender sender, boolean bypassForAdmins, boolean bypassMsg) {
        if (sender instanceof Player) {
            if (this.ownerId.equals(((Player) sender).getUniqueId())) return true;
            if (bypassForAdmins && sender.hasPermission(PermissionConstants.EDIT_OTHERS_LEVELS)) {
                if (bypassMsg) LangOptions.level_editor_permissionbypass.sendMsg(sender);
                return true;
            }
            return false;
        }
        return sender instanceof ConsoleCommandSender;
    }

    @NonNull
    public Map<UUID, String> getCoEditors() { return Collections.unmodifiableMap(this.coEditors); }

    @NonNull
    public Set<UUID> getTrustedCoEditors() { return Collections.unmodifiableSet(this.trustedCoEditors); }

    public boolean isTrusted(@NonNull UUID playerId) { return this.trustedCoEditors.contains(playerId); }

    public void setTrusted(@NonNull UUID playerId, boolean trusted) {
        if (trusted) this.trustedCoEditors.add(playerId);
        else this.trustedCoEditors.remove(playerId);
    }

    public boolean isCoEditor(@NonNull UUID playerId) { return this.coEditors.containsKey(playerId); }

    @Nullable
    public String getCoEditorName(@NonNull UUID playerId) { return this.coEditors.get(playerId); }

    public boolean addCoEditor(@NonNull UUID playerId, @NonNull String playerName) {
        if (this.isOwner(playerId) || this.coEditors.containsKey(playerId) || this.coEditors.size() >= MAX_CO_EDITORS) return false;
        this.coEditors.put(playerId, playerName);
        return true;
    }

    public boolean removeCoEditor(@NonNull UUID playerId) {
        this.trustedCoEditors.remove(playerId);
        return this.coEditors.remove(playerId) != null;
    }

    public boolean canEdit(@NonNull CommandSender sender, boolean bypassForAdmins, boolean bypassMsg) {
        if (sender instanceof Player player) {
            UUID playerId = player.getUniqueId();
            if (this.ownerId.equals(playerId) || this.coEditors.containsKey(playerId)) return true;
            // Друг с правом строить - такой же строитель, как соредактор.
            if (friendCanBuild(this.ownerId, playerId)) return true;
            if (bypassForAdmins && sender.hasPermission(PermissionConstants.EDIT_OTHERS_LEVELS)) {
                if (bypassMsg) LangOptions.level_editor_permissionbypass.sendMsg(sender);
                return true;
            }
            return false;
        }
        return sender instanceof ConsoleCommandSender;
    }

    public boolean canEdit(@NonNull UUID playerId) {
        return this.isOwner(playerId)
            || this.coEditors.containsKey(playerId)
            || friendCanBuild(this.ownerId, playerId);
    }

    public boolean isAccessibleForPlaying(@NonNull CommandSender sender, boolean bypassForAdmins) {
        if (this.publicVisible) return true;
        if (sender instanceof Player player
            && friendCanVisitPrivate(this.ownerId, player.getUniqueId())) {
            return true;
        }
        return this.canEdit(sender, bypassForAdmins, false);
    }

    // ==================== ДОСТУП ПО ДРУЖБЕ ====================

    /**
     * Мост к системе друзей.
     * <p>
     * {@link GameSettings} - «глупый» объект настроек без ссылки на плагин, а права по дружбе
     * нужно спрашивать в тех же местах, где проверяются владелец и соредакторы. Поэтому
     * резолвер выставляется один раз при включении плагина, а до этого (и в тестах) все
     * проверки дружбы просто возвращают false - поведение ровно как до её появления.
     */
    public interface FriendAccessResolver {
        boolean canVisitPrivateLevels(@NonNull UUID ownerId, @NonNull UUID playerId);

        boolean canBuildOnLevels(@NonNull UUID ownerId, @NonNull UUID playerId);
    }

    private static volatile FriendAccessResolver friendAccessResolver = null;

    public static void setFriendAccessResolver(@Nullable FriendAccessResolver resolver) {
        friendAccessResolver = resolver;
    }

    private static boolean friendCanVisitPrivate(@NonNull UUID ownerId, @NonNull UUID playerId) {
        FriendAccessResolver resolver = friendAccessResolver;
        if (resolver == null || ownerId.equals(playerId)) return false;
        try {
            return resolver.canVisitPrivateLevels(ownerId, playerId);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean friendCanBuild(@NonNull UUID ownerId, @NonNull UUID playerId) {
        FriendAccessResolver resolver = friendAccessResolver;
        if (resolver == null || ownerId.equals(playerId)) return false;
        try {
            return resolver.canBuildOnLevels(ownerId, playerId);
        } catch (Exception e) {
            return false;
        }
    }
}
