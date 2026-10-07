package me.klansistemi;

import java.util.List;

import org.bukkit.configuration.file.FileConfiguration;

/** config.yml değerleri (her okumada güncel; /klan admin yenile ile değişiklikler hemen geçerli olur). */
public class Ayarlar {

    private final KlanSistemi plugin;

    public Ayarlar(KlanSistemi plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration c() {
        return plugin.getConfig();
    }

    // --- Klan ---
    public double kurmaUcreti() { return c().getDouble("klan.kurma-ucreti", 50000); }
    public int maxUye() { return c().getInt("klan.max-uye", 10); }
    public int isimMin() { return c().getInt("klan.isim-min", 3); }
    public int isimMax() { return c().getInt("klan.isim-max", 16); }
    public long ayrilmaBeklemeMs() { return (long) (c().getDouble("klan.ayrilma-bekleme-saat", 24) * Zaman.SAAT); }
    public long yeniKlanKorumaMs() { return (long) (c().getDouble("klan.yeni-klan-savas-koruma-saat", 24) * Zaman.SAAT); }
    public long davetGecerlilikMs() { return (long) (c().getDouble("klan.davet-gecerlilik-dakika", 5) * 60000L); }
    public List<String> yasakliKelimeler() { return c().getStringList("yasakli-kelimeler"); }

    // --- Kasa ---
    public double kasaUstSinir() { return c().getDouble("kasa.ust-sinir", 10000000); }
    public double yardimciGunlukLimit() { return c().getDouble("kasa.yardimci-gunluk-cekim-limiti", 20000); }

    // --- Aidat ---
    public boolean aidatAktif() { return c().getBoolean("aidat.aktif", true); }
    public double aidatVarsayilan() { return c().getDouble("aidat.miktar", 1000); }
    public double aidatMin() { return c().getDouble("aidat.min-miktar", 1000); }
    public double aidatMax() { return c().getDouble("aidat.max-miktar", 50000); }
    public long aidatPeriyotMs() { return (long) (Math.max(0.01, c().getDouble("aidat.periyot-gun", 7)) * Zaman.GUN); }
    public long aidatEkSureMs() { return (long) (c().getDouble("aidat.ek-sure-gun", 2) * Zaman.GUN); }
    public boolean liderMuaf() { return c().getBoolean("aidat.lider-muaf", true); }
    public long yeniUyeMuafMs() { return (long) (c().getDouble("aidat.yeni-uye-muaf-gun", 7) * Zaman.GUN); }
    public boolean otomatikAtma() { return c().getBoolean("aidat.otomatik-atma", true); }
    public int otomatikAtmaDonem() { return c().getInt("aidat.otomatik-atma-donem", 3); }
    public int liderBildirimDonem() { return c().getInt("aidat.lider-bildirim-donem", 2); }
    public boolean cevrimdisiKoruma() { return c().getBoolean("aidat.cevrimdisi-koruma", true); }
}
