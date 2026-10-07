package me.klansistemi;

import org.bukkit.Bukkit;

import me.klansistemi.model.Klan;
import me.klansistemi.model.KlanUyesi;

/**
 * Tag-Manager (TagPlugin) entegrasyonu: klan etiketi, oyuncunun meslek öneki ve soneki bozulmadan
 * onların önüne "ek etiket" olarak eklenir (/tag setek, /tag removeek). TagPlugin kurulu değilse
 * hiçbir şey yapılmaz. Etiketi TagPlugin saklar; oyuncu girince kendisi uygular.
 */
public class EtiketKoprusu {

    private final KlanSistemi plugin;

    public EtiketKoprusu(KlanSistemi plugin) {
        this.plugin = plugin;
    }

    private boolean aktif() {
        return plugin.getConfig().getBoolean("etiket.aktif", true)
                && Bukkit.getPluginManager().isPluginEnabled("TagPlugin");
    }

    /** Üyenin klan etiketini (klan adı ve rolüyle) atar ya da günceller. */
    public void guncelle(Klan a, KlanUyesi u) {
        if (!aktif()) return;
        String format = plugin.getConfig().getString("etiket.format", "&8[&6{klan}&8]")
                .replace("{klan}", a.isim)
                .replace("{rol}", u.rol.ad);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tag setek " + u.isim + " " + format);
    }

    public void klandakileriGuncelle(Klan a) {
        for (KlanUyesi u : a.uyeler.values()) guncelle(a, u);
    }

    public void kaldir(String oyuncuAdi) {
        if (!aktif()) return;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tag removeek " + oyuncuAdi);
    }

    /** Tüm klanların etiketlerini yeniden uygular (/klan admin etiketyenile). */
    public int tumunuYenile() {
        if (!aktif()) return -1;
        int sayi = 0;
        for (Klan a : plugin.klanManager().klanlar()) {
            klandakileriGuncelle(a);
            sayi += a.uyeler.size();
        }
        return sayi;
    }
}
