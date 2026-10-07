package me.ailesistemi;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class OyuncuListener implements Listener {

    private final AileSistemi plugin;

    public OyuncuListener(AileSistemi plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        plugin.aileManager().isimGuncelle(p);
        // Diğer giriş mesajlarının arasında kaybolmasın
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) plugin.aidat().girisKontrolu(p);
        }, 60L);
    }

    // Aile sohbeti açık oyuncunun mesajı genel sohbete düşmez, ailesine gider.
    // Başka bir eklenti mesajı zaten iptal ettiyse (örn. Meslek'in "chat'e yaz" adımları) dokunulmaz.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player p = event.getPlayer();
        if (!plugin.aileManager().sohbetAcikMi(p.getUniqueId())) return;
        event.setCancelled(true);
        String mesaj = PlainTextComponentSerializer.plainText().serialize(event.message());
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) plugin.aileManager().aileSohbeti(p, mesaj);
        });
    }
}
