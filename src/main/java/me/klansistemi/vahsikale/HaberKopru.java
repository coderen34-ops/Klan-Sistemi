package me.klansistemi.vahsikale;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/** MeslekSistemi'nin gazete (HaberManager) sistemine yansıma ile haber yazar; Meslek yoksa sessizce atlar. */
final class HaberKopru {
    private HaberKopru() {}

    static void yaz(String kategori, String baslik, String metin) {
        try {
            Plugin m = Bukkit.getPluginManager().getPlugin("MeslekSistemi");
            if (m == null || !m.isEnabled()) return;
            Object hm = m.getClass().getField("haberManager").get(m);
            if (hm == null) return;
            hm.getClass().getMethod("yaz", String.class, String.class, String.class).invoke(hm, kategori, baslik, metin);
        } catch (Throwable ignored) { }
    }
}
