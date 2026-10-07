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
import me.ailesistemi.model.Gorus;
import me.ailesistemi.model.IliskiDurumu;
import me.ailesistemi.EsyaKasasi;
import me.ailesistemi.savas.ArenaManager;
import me.ailesistemi.savas.SavasKaydi;
import me.ailesistemi.savas.SavasManager;
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
            y.set(yol + ".prestij", a.prestij);
            for (Map.Entry<UUID, Integer> e : a.prestijRakip.entrySet()) y.set(yol + ".prestij-rakip." + e.getKey(), e.getValue());
            List<List<String>> esya = plugin.esya().kayitVerisi(a.id);
            for (int s = 0; s < esya.size(); s++) y.set(yol + ".esya-kasasi." + s, esya.get(s));
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
        for (Map.Entry<UUID, List<org.bukkit.inventory.ItemStack>> e : plugin.esya().bekleyenler().entrySet()) {
            List<String> liste = new ArrayList<>();
            for (org.bukkit.inventory.ItemStack item : e.getValue()) liste.add(EsyaKasasi.yaz(item));
            y.set("bekleyen-esyalar." + e.getKey(), liste);
        }
        for (Map<UUID, Gorus> hedefler : plugin.iliski().tumGorusler().values()) {
            for (Gorus g : hedefler.values()) {
                String gy = "gorusler." + g.yazanAile + "." + g.hedefAile;
                y.set(gy + ".durum", g.durum.name());
                y.set(gy + ".not", g.not);
                y.set(gy + ".yazan", g.yazan);
                y.set(gy + ".zaman", g.zaman);
            }
        }
        for (Map.Entry<String, Long> e : plugin.iliski().dostlukBitisleri().entrySet()) {
            y.set("dostluk-bitis." + e.getKey().replace(':', '_'), e.getValue());
        }

        SavasManager sm = plugin.savas();
        if (sm != null) {
            for (Map.Entry<UUID, Long> e : sm.kaybedenBeklemeleri().entrySet()) y.set("savas.kaybeden-bekleme." + e.getKey(), e.getValue());
            for (Map.Entry<String, Long> e : sm.ciftBeklemeleri().entrySet()) y.set("savas.cift-bekleme." + e.getKey(), e.getValue());
            // Savaş sırasında sunucu çökerse oyuncular girişte eski konumlarına döner
            for (Map.Entry<UUID, org.bukkit.Location> e : sm.donusKonumlari().entrySet()) {
                String konum = ArenaManager.konumYaz(e.getValue());
                if (konum != null) y.set("savas.donus." + e.getKey(), konum);
            }
            List<Map<String, Object>> gecmis = new ArrayList<>();
            for (SavasKaydi k : sm.gecmis()) {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("aileA", k.aileA.toString());
                m.put("aileB", k.aileB.toString());
                m.put("isimA", k.isimA);
                m.put("isimB", k.isimB);
                m.put("puanA", k.puanA);
                m.put("puanB", k.puanB);
                m.put("kazanan", k.kazanan == null ? "" : k.kazanan.toString());
                m.put("sonuc", k.sonuc);
                m.put("ganimetPara", k.ganimetPara);
                m.put("ganimetEsya", k.ganimetEsya);
                m.put("zaman", k.zaman);
                m.put("sure", k.sure);
                gecmis.add(m);
            }
            y.set("savas.gecmis", gecmis);
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
                    a.prestij = c.getInt("prestij");
                    ConfigurationSection pr = c.getConfigurationSection("prestij-rakip");
                    if (pr != null) for (String k : pr.getKeys(false)) {
                        try { a.prestijRakip.put(UUID.fromString(k), pr.getInt(k)); } catch (Exception ignored) {}
                    }
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
                    ConfigurationSection esya = c.getConfigurationSection("esya-kasasi");
                    List<List<String>> sayfalar = new ArrayList<>();
                    if (esya != null) {
                        for (int s = 0; esya.contains(String.valueOf(s)); s++) sayfalar.add(esya.getStringList(String.valueOf(s)));
                    }
                    plugin.esya().yukle(a, sayfalar);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Aile kaydı yüklenemedi: " + idStr, e);
                }
            }
        }
        ConfigurationSection bekleyen = y.getConfigurationSection("bekleyen-esyalar");
        if (bekleyen != null) {
            for (String uStr : bekleyen.getKeys(false)) {
                try {
                    UUID u = UUID.fromString(uStr);
                    for (String veri : bekleyen.getStringList(uStr)) {
                        org.bukkit.inventory.ItemStack item = EsyaKasasi.oku(veri);
                        if (item != null) plugin.esya().bekleyenEkle(u, item);
                    }
                } catch (Exception ignored) {}
            }
        }
        ConfigurationSection gorusler = y.getConfigurationSection("gorusler");
        if (gorusler != null) {
            for (String yazanStr : gorusler.getKeys(false)) {
                ConfigurationSection hedefler = gorusler.getConfigurationSection(yazanStr);
                for (String hedefStr : hedefler.getKeys(false)) {
                    try {
                        UUID yazan = UUID.fromString(yazanStr), hedef = UUID.fromString(hedefStr);
                        if (am.aileGetir(yazan) == null || am.aileGetir(hedef) == null) continue;
                        ConfigurationSection g = hedefler.getConfigurationSection(hedefStr);
                        plugin.iliski().yukle(new Gorus(yazan, hedef, IliskiDurumu.valueOf(g.getString("durum", "TARAFSIZ")),
                                g.getString("not", ""), g.getString("yazan", "?"), g.getLong("zaman")));
                    } catch (Exception ignored) {}
                }
            }
        }
        ConfigurationSection dostluk = y.getConfigurationSection("dostluk-bitis");
        if (dostluk != null) {
            for (String k : dostluk.getKeys(false)) plugin.iliski().dostlukBitisleri().put(k.replace('_', ':'), dostluk.getLong(k));
        }
        SavasManager sm = plugin.savas();
        ConfigurationSection kb = y.getConfigurationSection("savas.kaybeden-bekleme");
        if (kb != null) for (String k : kb.getKeys(false)) {
            try { sm.kaybedenBeklemeleri().put(UUID.fromString(k), kb.getLong(k)); } catch (Exception ignored) {}
        }
        ConfigurationSection cb = y.getConfigurationSection("savas.cift-bekleme");
        if (cb != null) for (String k : cb.getKeys(false)) sm.ciftBeklemeleri().put(k, cb.getLong(k));
        ConfigurationSection donus = y.getConfigurationSection("savas.donus");
        if (donus != null) for (String k : donus.getKeys(false)) {
            try {
                org.bukkit.Location loc = ArenaManager.konumOku(donus.getString(k));
                if (loc != null) sm.donusKonumlari().put(UUID.fromString(k), loc);
            } catch (Exception ignored) {}
        }
        for (Map<?, ?> m : y.getMapList("savas.gecmis")) {
            try {
                String kazanan = String.valueOf(m.get("kazanan"));
                sm.gecmis().add(new SavasKaydi(UUID.fromString(String.valueOf(m.get("aileA"))), UUID.fromString(String.valueOf(m.get("aileB"))),
                        String.valueOf(m.get("isimA")), String.valueOf(m.get("isimB")),
                        ((Number) m.get("puanA")).intValue(), ((Number) m.get("puanB")).intValue(),
                        kazanan.isEmpty() ? null : UUID.fromString(kazanan), String.valueOf(m.get("sonuc")),
                        ((Number) m.get("ganimetPara")).doubleValue(), ((Number) m.get("ganimetEsya")).intValue(),
                        ((Number) m.get("zaman")).longValue(), ((Number) m.get("sure")).longValue()));
            } catch (Exception ignored) {}
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
