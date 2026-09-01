package ru.sortix.parkourbeat.player;

import ru.sortix.parkourbeat.utils.lang.PlayerLang;

import ru.sortix.parkourbeat.utils.lang.Lang;

import lombok.NonNull;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import ru.sortix.parkourbeat.ParkourBeat;
import ru.sortix.parkourbeat.lifecycle.PluginManager;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import ru.sortix.parkourbeat.utils.text.PbText;
public class AfkManager implements PluginManager, Listener {
    public static final long TOGGLE_DELAY_MILLIS = 3_000L;
    public static final int[] AUTO_AFK_MINUTES = {0, 5, 15, 30};

    private final @NonNull ParkourBeat plugin;
    private final Set<UUID> afkPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastMoveAt = new ConcurrentHashMap<>();
    private final Map<UUID, Long> pendingSince = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> pendingState = new ConcurrentHashMap<>();
    private final BukkitTask task;

    public AfkManager(@NonNull ParkourBeat plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public boolean isAfk(@NonNull UUID playerId) {
        return this.afkPlayers.contains(playerId);
    }

    public boolean isPending(@NonNull UUID playerId) {
        return this.pendingSince.containsKey(playerId);
    }

    public void requestToggle(@NonNull Player player, boolean afk) {
        UUID id = player.getUniqueId();
        if (this.afkPlayers.contains(id) == afk) return;
        this.pendingSince.put(id, System.currentTimeMillis());
        this.pendingState.put(id, afk);
    }

    private void apply(@NonNull UUID id, boolean afk) {
        if (afk) this.afkPlayers.add(id);
        else this.afkPlayers.remove(id);

        Player player = Bukkit.getPlayer(id);
        if (player == null || !player.isOnline()) return;
        net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer legacy =
            net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand();
        player.sendMessage(PbText.of(afk
            ? Lang.raw(PlayerLang.of(player), "auto.afk_manager.apply.1")
            : Lang.raw(PlayerLang.of(player), "auto.afk_manager.apply.2")));
    }

    private void tick() {
        long now = System.currentTimeMillis();
        PlayerSettingsManager settings = this.plugin.get(PlayerSettingsManager.class);

        for (Map.Entry<UUID, Long> entry : this.pendingSince.entrySet()) {
            if (now - entry.getValue() < TOGGLE_DELAY_MILLIS) continue;
            UUID id = entry.getKey();
            Boolean target = this.pendingState.remove(id);
            this.pendingSince.remove(id);
            if (target != null) this.apply(id, target);
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            if (this.afkPlayers.contains(id) || this.isPending(id)) continue;

            int minutes = settings.getAutoAfkMinutes(id);
            if (minutes <= 0) continue;

            Long last = this.lastMoveAt.get(id);
            if (last == null) {
                this.lastMoveAt.put(id, now);
                continue;
            }
            if (now - last < minutes * 60_000L) continue;
            this.requestToggle(player, true);
        }
    }

    @EventHandler
    private void on(@NonNull PlayerMoveEvent event) {
        if (event.getTo() == null) return;
        if (event.getFrom().getX() == event.getTo().getX()
            && event.getFrom().getY() == event.getTo().getY()
            && event.getFrom().getZ() == event.getTo().getZ()) return;

        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        this.lastMoveAt.put(id, System.currentTimeMillis());

        if (this.afkPlayers.contains(id) && !Boolean.FALSE.equals(this.pendingState.get(id))) {
            this.requestToggle(player, false);
        }
    }

    /** Из АФК по чату выходим сразу: ждать 3 секунды тут бессмысленно. */
    public void wakeImmediately(@NonNull Player player) {
        UUID id = player.getUniqueId();
        this.lastMoveAt.put(id, System.currentTimeMillis());

        boolean wasPending = Boolean.TRUE.equals(this.pendingState.get(id));
        if (wasPending) {
            this.pendingSince.remove(id);
            this.pendingState.remove(id);
        }
        if (!this.afkPlayers.contains(id)) return;

        this.pendingSince.remove(id);
        this.pendingState.remove(id);
        this.apply(id, false);
    }

    @EventHandler(ignoreCancelled = true)
    private void on(@NonNull AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(this.plugin, () -> this.wakeImmediately(player));
    }

    @EventHandler(ignoreCancelled = true)
    private void on(@NonNull PlayerCommandPreprocessEvent event) {
        this.wakeImmediately(event.getPlayer());
    }

    @EventHandler
    private void on(@NonNull PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        this.afkPlayers.remove(id);
        this.lastMoveAt.remove(id);
        this.pendingSince.remove(id);
        this.pendingState.remove(id);
    }

    @Override
    public void disable() {
        if (this.task != null) this.task.cancel();
        HandlerList.unregisterAll(this);
        this.afkPlayers.clear();
    }
}
