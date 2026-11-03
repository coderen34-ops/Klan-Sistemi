package me.klansistemi.olay;

import java.lang.reflect.Method;

import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Hata ayıklama: her olayı konsola BİR satır yazar ("her olay tam bir kez tetikleniyor" doğrulaması için).
 * Varsayılan KAPALI. plugins/KlanSistemi/mobil.yml içine "olay-log: true" yazılıp sunucu yeniden başlatılırsa açılır.
 */
public class OlayGunlukcusu implements Listener {
    private final JavaPlugin plugin;

    public OlayGunlukcusu(JavaPlugin plugin) { this.plugin = plugin; }

    private void yaz(Event e) {
        StringBuilder sb = new StringBuilder("[OLAY] ").append(e.getEventName());
        for (Method m : e.getClass().getDeclaredMethods()) {
            String ad = m.getName();
            if (!ad.startsWith("get") || m.getParameterCount() != 0 || ad.equals("getHandlers")) continue;
            try { sb.append(' ').append(ad.substring(3)).append('=').append(m.invoke(e)); } catch (Exception ignored) { }
        }
        plugin.getLogger().info(sb.toString());
    }

    @EventHandler public void o0(me.klansistemi.olay.KlanKurulduEvent e) { yaz(e); }
    @EventHandler public void o1(me.klansistemi.olay.KlanIliskiDegistiEvent e) { yaz(e); }
    @EventHandler public void o2(me.klansistemi.olay.KlanGorusEvent e) { yaz(e); }
}
