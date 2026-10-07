package me.klansistemi.buyu;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import me.klansistemi.KlanSistemi;

/**
 * Büyülü eşyaların merkezi kayıt defteri (buyu-defteri.yml). Doğruluk kaynağıdır:
 * her kimlik için sahibi klanı ve eşyanın nerede olduğunu tutar.
 *   KASADA  : sahibi klanın eşya kasasında
 *   ODUNC   : bir üye kasadan almış, kullanıyor (oyuncu UUID'si tutulur)
 *   TICARI  : dışarıya satılmış (kasa-bağlı değil)
 *   SILINDI : geçersiz (kırıldı, klan dağıldı, kopya tespit edildi...) — kayıt geçmiş için saklanır
 */
public class KayitDefteri {

    public enum Durum { KASADA, ODUNC, TICARI, SILINDI }

    public static class Kayit {
        public final String kod;          // Kısa kod (lore'daki ID)
        public UUID uuid;                 // Eşyadaki uzun kimlik; kısa kodla birlikte doğrulanır (ganimette yenilenebilir)
        public String malzeme;            // Eşya türü (örn. DIAMOND_PICKAXE); fiziksel eşya yeniden üretilirken kullanılır
        public final UUID kaynakKlan;
        public final String kaynakKlanAdi;
        public UUID sahipKlan;            // Şu an eşyanın ait olduğu klan (ganimetle değişebilir)
        public final String alan;
        public final BuyuEsyasi.Katman katman;
        public Durum durum;
        public UUID oduncOyuncu;
        public final long uretim;
        public long guncelleme;
        public String not;                // Son durum değişikliğinin sebebi

        public Kayit(String kod, UUID uuid, UUID kaynakKlan, String kaynakKlanAdi, UUID sahipKlan, String alan,
                     BuyuEsyasi.Katman katman, Durum durum, long uretim) {
            this.kod = kod;
            this.uuid = uuid;
            this.kaynakKlan = kaynakKlan;
            this.kaynakKlanAdi = kaynakKlanAdi;
            this.sahipKlan = sahipKlan;
            this.alan = alan;
            this.katman = katman;
            this.durum = durum;
            this.uretim = uretim;
            this.guncelleme = uretim;
        }
    }

    private static final char[] HARFLER = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray(); // Karışan 0/O, 1/I yok
    private final SecureRandom rastgele = new SecureRandom();

    private final KlanSistemi plugin;
    private final File dosya;
    private final Map<String, Kayit> kayitlar = new LinkedHashMap<>();
    private boolean kayitPlanlandi = false;
    private volatile boolean anaDosyaBozuk = false;

    public KayitDefteri(KlanSistemi plugin) {
        this.plugin = plugin;
        this.dosya = new File(plugin.getDataFolder(), "buyu-defteri.yml");
        yukle();
    }

    // ------------------------------------------------------------------ SORGULAR
    public Kayit get(String kod) { return kod == null ? null : kayitlar.get(kod); }

    public Collection<Kayit> tumu() { return kayitlar.values(); }

    /** Bir klana ait, silinmemiş kayıtlar. */
    public List<Kayit> klanKayitlari(UUID klan) {
        List<Kayit> liste = new ArrayList<>();
        for (Kayit k : kayitlar.values()) if (klan.equals(k.sahipKlan) && k.durum != Durum.SILINDI) liste.add(k);
        return liste;
    }

    /** Daha önce hiç kullanılmamış 6 karakterlik kısa kod. */
    public String yeniKod() {
        while (true) {
            char[] c = new char[6];
            for (int i = 0; i < c.length; i++) c[i] = HARFLER[rastgele.nextInt(HARFLER.length)];
            String kod = new String(c);
            if (!kayitlar.containsKey(kod)) return kod;
        }
    }

    // ------------------------------------------------------------------ DEĞİŞİKLİKLER
    public void ekle(Kayit k) {
        kayitlar.put(k.kod, k);
        kaydet();
    }

    public void durumAyarla(Kayit k, Durum durum, UUID oduncOyuncu, String not) {
        k.durum = durum;
        k.oduncOyuncu = durum == Durum.ODUNC ? oduncOyuncu : null;
        k.guncelleme = System.currentTimeMillis();
        k.not = not;
        kaydet();
    }

    // ------------------------------------------------------------------ KAYIT (asenkron, .bak yedekli)
    public void kaydet() {
        if (!plugin.isEnabled() || plugin.kapaniyor()) { hemenKaydet(); return; }
        if (kayitPlanlandi) return;
        kayitPlanlandi = true;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            kayitPlanlandi = false;
            String icerik = olustur().saveToString();
            plugin.arkaPlanda(() -> diskeYaz(icerik));
        }, 20L);
    }

    public void hemenKaydet() {
        diskeYaz(olustur().saveToString());
    }

    private YamlConfiguration olustur() {
        YamlConfiguration y = new YamlConfiguration();
        for (Kayit k : kayitlar.values()) {
            String yol = "kayitlar." + k.kod;
            y.set(yol + ".uuid", k.uuid.toString());
            y.set(yol + ".kaynak-klan", k.kaynakKlan.toString());
            y.set(yol + ".kaynak-klan-adi", k.kaynakKlanAdi);
            y.set(yol + ".sahip-klan", k.sahipKlan == null ? null : k.sahipKlan.toString());
            y.set(yol + ".alan", k.alan);
            y.set(yol + ".katman", k.katman.name());
            y.set(yol + ".durum", k.durum.name());
            y.set(yol + ".odunc", k.oduncOyuncu == null ? null : k.oduncOyuncu.toString());
            y.set(yol + ".uretim", k.uretim);
            y.set(yol + ".guncelleme", k.guncelleme);
            y.set(yol + ".not", k.not);
            y.set(yol + ".malzeme", k.malzeme);
        }
        return y;
    }

    private synchronized void diskeYaz(String icerik) {
        try {
            Files.createDirectories(dosya.getParentFile().toPath());
            if (anaDosyaBozuk) {
                if (dosya.exists()) {
                    Files.move(dosya.toPath(), new File(dosya.getParentFile(), "buyu-defteri.yml.bozuk-" + System.currentTimeMillis()).toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                }
                anaDosyaBozuk = false;
            } else if (dosya.exists()) {
                Files.copy(dosya.toPath(), new File(dosya.getParentFile(), "buyu-defteri.yml.bak").toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            Path gecici = new File(dosya.getParentFile(), "buyu-defteri.yml.tmp").toPath();
            Files.writeString(gecici, icerik, StandardCharsets.UTF_8);
            try {
                Files.move(gecici, dosya.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(gecici, dosya.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "buyu-defteri.yml kaydedilemedi!", e);
        }
    }

    private void yukle() {
        YamlConfiguration y = oku(dosya);
        if (y == null && dosya.exists()) anaDosyaBozuk = true;
        if (y == null) {
            y = oku(new File(dosya.getParentFile(), "buyu-defteri.yml.bak"));
            if (y != null) plugin.getLogger().warning("buyu-defteri.yml okunamadı, yedekten yüklendi!");
        }
        if (y == null) return;
        ConfigurationSection bolum = y.getConfigurationSection("kayitlar");
        if (bolum == null) return;
        for (String kod : bolum.getKeys(false)) {
            try {
                ConfigurationSection c = bolum.getConfigurationSection(kod);
                String sahip = c.getString("sahip-klan"), odunc = c.getString("odunc");
                Kayit k = new Kayit(kod, UUID.fromString(c.getString("uuid")), UUID.fromString(c.getString("kaynak-klan")),
                        c.getString("kaynak-klan-adi", "?"), sahip == null ? null : UUID.fromString(sahip), c.getString("alan"),
                        BuyuEsyasi.Katman.valueOf(c.getString("katman", "KLAN")), Durum.valueOf(c.getString("durum", "KASADA")),
                        c.getLong("uretim"));
                k.oduncOyuncu = odunc == null ? null : UUID.fromString(odunc);
                k.guncelleme = c.getLong("guncelleme", k.uretim);
                k.not = c.getString("not");
                k.malzeme = c.getString("malzeme");
                kayitlar.put(kod, k);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Büyü kaydı yüklenemedi: " + kod, e);
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
