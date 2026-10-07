package me.klansistemi;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class OyuncuListener implements Listener {

    private final KlanSistemi plugin;

    public OyuncuListener(KlanSistemi plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        plugin.klanManager().isimGuncelle(p);
        // Diğer giriş mesajlarının arasında kaybolmasın
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            plugin.aidat().girisKontrolu(p);
            int bekleyen = plugin.esya().bekleyenSayisi(p.getUniqueId());
            if (bekleyen > 0) {
                plugin.mesaj().gonder(p, "bekleyen-hatirlatma", "&6{sayi} yığın bekleyen eşyanız var. Almak için: &e/klan ganimet", "sayi", bekleyen);
            }
        }, 60L);
    }

    // Klan sohbeti açık oyuncunun mesajı genel sohbete düşmez, klanına gider.
    // Başka bir eklenti mesajı zaten iptal ettiyse (örn. Meslek'in "chat'e yaz" adımları) dokunulmaz.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player p = event.getPlayer();
        String mesaj = PlainTextComponentSerializer.plainText().serialize(event.message());

        // Diplomasi panelinden görüş notu bekleniyorsa mesaj not olarak alınır
        if (plugin.iliski().notBekliyorMu(p.getUniqueId())) {
            event.setCancelled(true);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline()) plugin.iliski().notGeldi(p, mesaj);
            });
            return;
        }

        if (!plugin.klanManager().sohbetAcikMi(p.getUniqueId())) return;
        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) plugin.klanManager().klanSohbeti(p, mesaj);
        });
    }
}
