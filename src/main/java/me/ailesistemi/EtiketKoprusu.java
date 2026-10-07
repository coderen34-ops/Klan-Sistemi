package me.ailesistemi;

import org.bukkit.Bukkit;

import me.ailesistemi.model.Aile;
import me.ailesistemi.model.AileUyesi;

/**
 * Tag-Manager (TagPlugin) entegrasyonu: aile etiketi, oyuncunun meslek öneki ve soneki bozulmadan
 * onların önüne "ek etiket" olarak eklenir (/tag setek, /tag removeek). TagPlugin kurulu değilse
 * hiçbir şey yapılmaz. Etiketi TagPlugin saklar; oyuncu girince kendisi uygular.
 */
public class EtiketKoprusu {

    private final AileSistemi plugin;

    public EtiketKoprusu(AileSistemi plugin) {
        this.plugin = plugin;
    }

    private boolean aktif() {
        return plugin.getConfig().getBoolean("etiket.aktif", true)
                && Bukkit.getPluginManager().isPluginEnabled("TagPlugin");
    }

    /** Üyenin aile etiketini (aile adı ve rolüyle) atar ya da günceller. */
    public void guncelle(Aile a, AileUyesi u) {
        if (!aktif()) return;
        String format = plugin.getConfig().getString("etiket.format", "&8[&6{aile}&8]")
                .replace("{aile}", a.isim)
                .replace("{rol}", u.rol.ad);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tag setek " + u.isim + " " + format);
    }

    public void ailedekileriGuncelle(Aile a) {
        for (AileUyesi u : a.uyeler.values()) guncelle(a, u);
    }

    public void kaldir(String oyuncuAdi) {
        if (!aktif()) return;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tag removeek " + oyuncuAdi);
    }

    /** Tüm ailelerin etiketlerini yeniden uygular (/aile admin etiketyenile). */
    public int tumunuYenile() {
        if (!aktif()) return -1;
        int sayi = 0;
        for (Aile a : plugin.aileManager().aileler()) {
            ailedekileriGuncelle(a);
            sayi += a.uyeler.size();
        }
        return sayi;
    }
}
