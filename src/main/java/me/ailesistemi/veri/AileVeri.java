package me.ailesistemi.veri;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import me.ailesistemi.AileManager;
import me.ailesistemi.AileSistemi;
import me.ailesistemi.model.Aile;
import me.ailesistemi.model.AileUyesi;
import me.ailesistemi.model.Rol;

/**
 * families.yml okuma/yazma.
 * - Kayıt istekleri 1 saniye içinde birleştirilir; YAML ana thread'de üretilir, dosya arka planda yazılır.
 * - Yazmadan önce mevcut dosya families.yml.bak olarak yedeklenir; yeni içerik önce geçici dosyaya
 *   yazılıp sonra yerine taşınır (yarım kalan yazım dosyayı bozmaz).
 * - Ana dosya okunamazsa yedekten yüklenir.
 */
public class AileVeri {

    private final AileSistemi plugin;
    private final File dosya;
    private boolean kayitPlanlandi = false;
    // Ana dosya bozuk bulunduysa: ilk kayıtta iyi yedeğin üzerine yazılmasın, bozuk dosya ayrıca saklansın
    private volatile boolean anaDosyaBozuk = false;

    public AileVeri(AileSistemi plugin) {
        this.plugin = plugin;
        this.dosya = new File(plugin.getDataFolder(), "families.yml");
    }

    // ------------------------------------------------------------------ KAYIT
    public void kaydet() {
        if (!plugin.isEnabled() || plugin.kapaniyor()) {
            hemenKaydet();
            return;
        }
        if (kayitPlanlandi) return;
        kayitPlanlandi = true;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            kayitPlanlandi = false;
            String icerik = olustur().saveToString();
            plugin.arkaPlanda(() -> diskeYaz(icerik));
        }, 20L);
    }

    /** Kapanışta: senkron ve eksiksiz. */
    public void hemenKaydet() {
        diskeYaz(olustur().saveToString());
    }

    private synchronized void diskeYaz(String icerik) {
        try {
            Files.createDirectories(dosya.getParentFile().toPath());
            if (anaDosyaBozuk) {
                if (dosya.exists()) {
                    Files.move(dosya.toPath(), new File(dosya.getParentFile(), "families.yml.bozuk-" + System.currentTimeMillis()).toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                anaDosyaBozuk = false;
            } else if (dosya.exists()) {
                Files.copy(dosya.toPath(), new File(dosya.getParentFile(), "families.yml.bak").toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            Path gecici = new File(dosya.getParentFile(), "families.yml.tmp").toPath();
            Files.writeString(gecici, icerik, StandardCharsets.UTF_8);
            try {
                Files.move(gecici, dosya.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(gecici, dosya.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "families.yml kaydedilemedi!", e);
        }
    }

    private YamlConfiguration olustur() {
        AileManager am = plugin.aileManager();
        YamlConfiguration y = new YamlConfiguration();
        for (Aile a : am.aileler()) {
            String yol = "aileler." + a.id;
            y.set(yol + ".isim", a.isim);
            y.set(yol + ".kurulus", a.kurulus);
            y.set(yol + ".kasa", a.kasa);
            y.set(yol + ".aidat-miktari", a.aidatMiktari);
            y.set(yol + ".aidat-baslangic", a.aidatBaslangic);
            y.set(yol + ".toplam-aidat", a.toplamAidat);
            for (AileUyesi u : a.uyeler.values()) {
                String uy = yol + ".uyeler." + u.uuid;
                y.set(uy + ".isim", u.isim);
                y.set(uy + ".rol", u.rol.name());
                y.set(uy + ".katilma", u.katilma);
                y.set(uy + ".kidem", u.kidem);
                y.set(uy + ".muaf-bitis", u.muafBitis);
                y.set(uy + ".odenen-donemler", new ArrayList<>(u.odenenDonemler));
                y.set(uy + ".islenen-donem", u.islenenDonem);
                y.set(uy + ".ardisik-odenmeyen", u.ardisikOdenmeyen);
                y.set(uy + ".borclu", u.borclu);
                y.set(uy + ".bekleyen-donem", u.bekleyenDonem);
                y.set(uy + ".ek-sure-bitis", u.ekSureBitis);
                y.set(uy + ".son-odeme", u.sonOdeme);
                y.set(uy + ".hatirlatma-24", u.hatirlatma24Donem);
                y.set(uy + ".hatirlatma-1", u.hatirlatma1Donem);
                y.set(uy + ".cekim-gunu", u.cekimGunu);
                y.set(uy + ".gunluk-cekilen", u.gunlukCekilen);
            }
        }
        for (Map.Entry<UUID, Long> e : am.ayrilmaBeklemeleri().entrySet()) {
            y.set("ayrilma-bekleme." + e.getKey(), e.getValue());
        }
        return y;
    }

    // ------------------------------------------------------------------ YÜKLEME
    public void yukle() {
        YamlConfiguration y = oku(dosya);
        if (y == null && dosya.exists()) anaDosyaBozuk = true;
        if (y == null) {
            File yedek = new File(dosya.getParentFile(), "families.yml.bak");
            y = oku(yedek);
            if (y != null) plugin.getLogger().warning("families.yml okunamadı, yedekten (families.yml.bak) yüklendi!");
        }
        if (y == null) return;

        AileManager am = plugin.aileManager();
        ConfigurationSection aileler = y.getConfigurationSection("aileler");
        if (aileler != null) {
            for (String idStr : aileler.getKeys(false)) {
                try {
                    ConfigurationSection c = aileler.getConfigurationSection(idStr);
                    Aile a = new Aile(UUID.fromString(idStr), c.getString("isim"), c.getLong("kurulus"));
                    a.kasa = c.getDouble("kasa");
                    a.aidatMiktari = c.getDouble("aidat-miktari");
                    a.aidatBaslangic = c.getLong("aidat-baslangic");
                    a.toplamAidat = c.getDouble("toplam-aidat");
                    ConfigurationSection uyeler = c.getConfigurationSection("uyeler");
                    if (uyeler != null) {
                        for (String uStr : uyeler.getKeys(false)) {
                            ConfigurationSection uc = uyeler.getConfigurationSection(uStr);
                            AileUyesi u = new AileUyesi(UUID.fromString(uStr), uc.getString("isim", "?"),
                                    Rol.valueOf(uc.getString("rol", "UYE")), uc.getLong("katilma"));
                            u.kidem = uc.getInt("kidem");
                            u.muafBitis = uc.getLong("muaf-bitis");
                            for (Integer d : uc.getIntegerList("odenen-donemler")) u.odenenDonemler.add(d);
                            u.islenenDonem = uc.getInt("islenen-donem");
                            u.ardisikOdenmeyen = uc.getInt("ardisik-odenmeyen");
                            u.borclu = uc.getBoolean("borclu");
                            u.bekleyenDonem = uc.getInt("bekleyen-donem", -1);
                            u.ekSureBitis = uc.getLong("ek-sure-bitis");
                            u.sonOdeme = uc.getLong("son-odeme");
                            u.hatirlatma24Donem = uc.getInt("hatirlatma-24", -1);
                            u.hatirlatma1Donem = uc.getInt("hatirlatma-1", -1);
                            u.cekimGunu = uc.getLong("cekim-gunu");
                            u.gunlukCekilen = uc.getDouble("gunluk-cekilen");
                            a.uyeler.put(u.uuid, u);
                        }
                    }
                    if (a.patron() == null) {
                        plugin.getLogger().warning("'" + a.isim + "' ailesinin patronu yok, kayıt atlandı.");
                        continue;
                    }
                    a.kidemleriDuzenle();
                    am.yuklenenAileyiEkle(a);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Aile kaydı yüklenemedi: " + idStr, e);
                }
            }
        }
        ConfigurationSection bekleme = y.getConfigurationSection("ayrilma-bekleme");
        if (bekleme != null) {
            for (String uStr : bekleme.getKeys(false)) {
                try { am.ayrilmaBeklemeleri().put(UUID.fromString(uStr), bekleme.getLong(uStr)); } catch (Exception ignored) {}
            }
        }
    }

    private YamlConfiguration oku(File f) {
        if (!f.exists()) return null;
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.load(f);
            return y;
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().log(Level.SEVERE, f.getName() + " okunamadı!", e);
            return null;
        }
    }

}
