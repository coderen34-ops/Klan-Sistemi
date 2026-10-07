package me.ailesistemi;

import java.util.List;

import org.bukkit.configuration.file.FileConfiguration;

/** config.yml değerleri (her okumada güncel; /aile admin yenile ile değişiklikler hemen geçerli olur). */
public class Ayarlar {

    private final AileSistemi plugin;

    public Ayarlar(AileSistemi plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration c() {
        return plugin.getConfig();
    }

    // --- Aile ---
    public double kurmaUcreti() { return c().getDouble("aile.kurma-ucreti", 50000); }
    public int maxUye() { return c().getInt("aile.max-uye", 10); }
    public int isimMin() { return c().getInt("aile.isim-min", 3); }
    public int isimMax() { return c().getInt("aile.isim-max", 16); }
    public long ayrilmaBeklemeMs() { return (long) (c().getDouble("aile.ayrilma-bekleme-saat", 24) * Zaman.SAAT); }
    public long yeniAileKorumaMs() { return (long) (c().getDouble("aile.yeni-aile-savas-koruma-saat", 24) * Zaman.SAAT); }
    public long davetGecerlilikMs() { return (long) (c().getDouble("aile.davet-gecerlilik-dakika", 5) * 60000L); }
    public List<String> yasakliKelimeler() { return c().getStringList("yasakli-kelimeler"); }

    // --- Kasa ---
    public double kasaUstSinir() { return c().getDouble("kasa.ust-sinir", 10000000); }
    public double yardimciGunlukLimit() { return c().getDouble("kasa.yardimci-gunluk-cekim-limiti", 20000); }

    // --- Aidat ---
    public boolean aidatAktif() { return c().getBoolean("aidat.aktif", true); }
    public double aidatVarsayilan() { return c().getDouble("aidat.miktar", 5000); }
    public double aidatMin() { return c().getDouble("aidat.min-miktar", 1000); }
    public double aidatMax() { return c().getDouble("aidat.max-miktar", 50000); }
    public long aidatPeriyotMs() { return (long) (Math.max(0.01, c().getDouble("aidat.periyot-gun", 7)) * Zaman.GUN); }
    public long aidatEkSureMs() { return (long) (c().getDouble("aidat.ek-sure-gun", 2) * Zaman.GUN); }
    public boolean patronMuaf() { return c().getBoolean("aidat.patron-muaf", true); }
    public long yeniUyeMuafMs() { return (long) (c().getDouble("aidat.yeni-uye-muaf-gun", 7) * Zaman.GUN); }
    public boolean otomatikAtma() { return c().getBoolean("aidat.otomatik-atma", true); }
    public int otomatikAtmaDonem() { return c().getInt("aidat.otomatik-atma-donem", 3); }
    public int patronBildirimDonem() { return c().getInt("aidat.patron-bildirim-donem", 2); }
    public boolean cevrimdisiKoruma() { return c().getBoolean("aidat.cevrimdisi-koruma", true); }
}
