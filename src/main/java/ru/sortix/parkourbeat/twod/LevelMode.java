package ru.sortix.parkourbeat.twod;

import lombok.Getter;
import lombok.NonNull;
import org.bukkit.Material;

import javax.annotation.Nullable;

/**
 * Режим уровня: обычный трёхмерный паркур или двумерный «геометрический» уровень.
 * <p>
 * Хранится в {@link ru.sortix.parkourbeat.levels.settings.GameSettings} и выбирается
 * один раз при создании уровня в /create. По умолчанию - {@link #THREE_D}: все старые
 * уровни, у которых поля в файле нет, читаются именно как обычные.
 */
@Getter
public enum LevelMode {
    THREE_D("3D", "&b3D-уровень", Material.GRASS_BLOCK),
    TWO_D("2D", "&d2D-уровень", Material.WHITE_STAINED_GLASS);

    private final @NonNull String shortName;
    private final @NonNull String displayName;
    private final @NonNull Material icon;

    LevelMode(@NonNull String shortName, @NonNull String displayName, @NonNull Material icon) {
        this.shortName = shortName;
        this.displayName = displayName;
        this.icon = icon;
    }

    public boolean isTwoD() {
        return this == TWO_D;
    }

    @NonNull
    public LevelMode toggle() {
        return this == THREE_D ? TWO_D : THREE_D;
    }

    @NonNull
    public static LevelMode byName(@Nullable String name, @NonNull LevelMode fallback) {
        if (name == null) return fallback;
        String normalized = name.trim().toUpperCase(java.util.Locale.ROOT);
        if (normalized.isEmpty()) return fallback;
        if (normalized.equals("2D") || normalized.equals("TWO_D") || normalized.equals("TWOD")) return TWO_D;
        if (normalized.equals("3D") || normalized.equals("THREE_D") || normalized.equals("THREED")) return THREE_D;
        try {
            return LevelMode.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
