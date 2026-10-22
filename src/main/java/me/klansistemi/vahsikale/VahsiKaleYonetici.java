package me.klansistemi.vahsikale;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vindicator;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.persistence.PersistentDataType;

import me.klansistemi.KlanSistemi;
import me.klansistemi.Mesaj;
import me.klansistemi.Para;
import me.klansistemi.model.Klan;
import me.klansistemi.savas.ArenaManager;

/**
 * Vahşi Kaleler / NPC Klanları (Faz 4, Entry #8).
 *
 * KAPSAM (2. sürüm - tamamen prosedürel): rastgele güvenli konum bulma, Bastion tarzı kapalı
 * yapının KaleInsaatYardimcisi ile WorldEdit'siz saf Bukkit API'siyle inşası (demir kapı, zincir/
 * fener, köşe kuleleri), sandıkların LootTablosu ile gerçek item'larla doldurulması, nöbetçi/lider
 * NPC'leri (Vindicator + LibsDisguises insan görünümü) iç mekana doğurma, tespit/uyarı + saldırı
 * yarıçapı, sandık kilidi (idempotent - tüm nöbetçiler+lider öldürülene kadar kırılamaz), loot'un
 * doğrudan klan kasasına aktarımı + sandıktaki gerçek item'ların düşmesi, fetih prestiji, kuşatma
 * sırasında /home-/back-/tpa engeli, kuşatmada ölümde kale girişine respawn + klan prestij kaybı,
 * fethedilmemiş kale sayısı asgarinin altına düşerse otomatik yeni kale üretimi (bkz. KlanSistemi
 * onEnable'daki zamanlayıcı + otomatikUret/otomatikIlkDoldurma).
 *
 * SONRAYA BIRAKILANLAR (changelog'da not edilecek): elçi NPC (ticaret/görev/barış menüsü).
 */
public class VahsiKaleYonetici implements Listener {

    private final KlanSistemi plugin;
    private final File dbFile;
    private Connection baglanti;

    private final NamespacedKey kaleIdKey;
    private final NamespacedKey rolKey; // "NOBETCI" ya da "LIDER"

    private final Map<UUID, VahsiKale> kaleler = new LinkedHashMap<>();
    // Oyuncu -> son uyarı anı (actionbar throttle), kale bazlı tutmaya gerek yok, genel throttle yeterli
    private final Map<UUID, Long> sonUyari = new LinkedHashMap<>();

    public VahsiKaleYonetici(KlanSistemi plugin) {
        this.plugin = plugin;
        this.dbFile = new File(plugin.getDataFolder(), "vahsikaleler.db");
        this.kaleIdKey = new NamespacedKey(plugin, "vahsikale-id");
        this.rolKey = new NamespacedKey(plugin, "vahsikale-rol");
        baglan();
        tablolariOlustur();
        yukle();
    }

    private Mesaj m() { return plugin.mesaj(); }

    // ------------------------------------------------------------------ AYARLAR (config.yml)
    private double varsayilanTespit() { return plugin.getConfig().getDouble("vahsikale.tespit-yaricap", 40); }
    private double varsayilanSaldiri() { return plugin.getConfig().getDouble("vahsikale.saldiri-yaricap", 20); }
    private int nobetciSayisi(int seviye) { return plugin.getConfig().getInt("vahsikale.seviye-" + seviye + ".nobetci-sayisi", Math.max(2, seviye * 2)); }
    private double nobetciCan(int seviye) { return plugin.getConfig().getDouble("vahsikale.seviye-" + seviye + ".nobetci-can", 20 + seviye * 10); }
    private double liderCan(int seviye) { return plugin.getConfig().getDouble("vahsikale.seviye-" + seviye + ".lider-can", 60 + seviye * 20); }
    private double nobetciHasar(int seviye) { return plugin.getConfig().getDouble("vahsikale.seviye-" + seviye + ".nobetci-hasar", 4 + seviye); }
    private double sandikLootMin(int seviye) { return plugin.getConfig().getDouble("vahsikale.seviye-" + seviye + ".sandik-min", 3000 * seviye); }
    private double sandikLootMax(int seviye) { return plugin.getConfig().getDouble("vahsikale.seviye-" + seviye + ".sandik-max", 8000 * seviye); }
    private int fetihPrestiji() { return plugin.getConfig().getInt("vahsikale.fetih-prestiji", 10); }
    private int olumPrestijKaybi() { return plugin.getConfig().getInt("vahsikale.olum-prestij-kaybi", 30); }

    // ------------------------------------------------------------------ KOMUT
    public void komut(CommandSender s, String[] args) {
        if (args.length < 1) { kullanim(s); return; }
        String alt = args[0].toLowerCase(Locale.ROOT);
        switch (alt) {
            case "uret" -> uret(s, args);
            case "sil" -> sil(s, args);
            case "liste" -> liste(s);
            case "bilgi" -> bilgi(s);
            case "giris" -> girisAyarla(s);
            default -> kullanim(s);
        }
    }

    private void kullanim(CommandSender s) {
        s.sendMessage(Mesaj.renk("&cKullanım: &e/vahsikale <uret [seviye 1-5]|sil <isim>|liste|bilgi|giris>"));
    }

    /**
     * Tamamen prosedürel üretim: rastgele güvenli bir konum bulur, Bastion tarzı kapalı yapıyı
     * inşa eder, sandıkları loot ile doldurur, nöbetçi+lider NPC'lerini içeri doğurur - hepsi
     * tek adımda. Oyuncu konumuna bağımlı DEĞİLDİR, konsoldan da çalışır (admin istediği yerde
     * değil, sistemin bulduğu uzak/boş bir noktada kurulur - bu yüzden konum argümanı almaz).
     */
    private void uret(CommandSender s, String[] args) {
        int seviye = 1 + new Random().nextInt(5);
        if (args.length >= 2) {
            try {
                seviye = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) { kullanim(s); return; }
            if (seviye < 1 || seviye > 5) { s.sendMessage(Mesaj.renk("&cSeviye 1-5 arasında olmalı.")); return; }
        }
        VahsiKale kale = otomatikUret(seviye);
        if (kale == null) {
            s.sendMessage(Mesaj.renk("&cUygun/güvenli bir konum bulunamadı (deneme limiti doldu). Tekrar dene."));
            return;
        }
        s.sendMessage(Mesaj.renk("&a" + kale.isim + " &7(seviye " + kale.seviye + ") üretildi: &f"
                + kale.merkez.getWorld().getName() + " " + kale.merkez.getBlockX() + ", " + kale.merkez.getBlockZ()
                + " &7- " + kale.nobetciRolleri.size() + " nöbetçi/lider, " + kale.sandiklar.size() + " sandık."));
    }

    /** Dışarıdan (otomatik tetikleyici ya da komut) çağrılır. Başarısızsa null döner. */
    public VahsiKale otomatikUret(int seviye) {
        World dunya = Bukkit.getWorlds().get(0);
        Random rng = new Random();
        Location konum = rastgeleKonumBul(dunya, rng);
        if (konum == null) return null;

        KaleInsaatYardimcisi.Sonuc yapi = KaleInsaatYardimcisi.insaEt(konum, seviye, rng);

        VahsiKale kale = new VahsiKale(UUID.randomUUID());
        kale.seviye = seviye;
        kale.isim = IsimUretici.uret(seviye);
        kale.merkez = yapi.girisIci.clone();
        kale.giris = yapi.girisIci.clone();
        kale.tespitYaricap = varsayilanTespit();
        kale.saldiriYaricap = varsayilanSaldiri();
        kale.klanTuru = KlanSkinHavuzu.rastgeleKlanTuru(rng);
        kaleler.put(kale.id, kale);
        kaydet(kale);

        for (Location sandikKonum : yapi.sandikKonumlari) {
            BlockState durum = sandikKonum.getBlock().getState();
            if (durum instanceof Chest sandik) {
                LootTablosu.doldur(sandik.getInventory(), seviye, rng);
                sandik.update(true, false);
            }
            String konumYazisi = ArenaManager.konumYaz(sandikKonum);
            kale.sandiklar.put(konumYazisi, false);
            sandikKaydet(kale.id, konumYazisi, false);
        }

        nobetcileriDogur(kale, yapi, rng);
        plugin.getLogger().info("Vahşi Kale otomatik üretildi: " + kale.isim + " (seviye " + seviye + ", klan türü: "
                + kale.klanTuru + ") @ " + dunya.getName() + " " + konum.getBlockX() + "," + konum.getBlockZ());
        return kale;
    }

    private int aktifKaleSayisi() {
        int sayi = 0;
        for (VahsiKale k : kaleler.values()) if (!k.fethedildi) sayi++;
        return sayi;
    }

    /** Sunucu açılışında bir kere çağrılır: aktif kale sayısı asgarinin altındaysa eksikleri üretir
     *  (ağır olduğundan hepsini aynı anda değil, aralıklarla - sunucuyu kilitlemesin). */
    public void otomatikIlkDoldurma() {
        int hedef = plugin.getConfig().getInt("vahsikale.min-aktif-kale-sayisi", 3);
        int eksik = hedef - aktifKaleSayisi();
        for (int i = 0; i < eksik; i++) {
            final int sira = i;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                VahsiKale yeni = otomatikUret(1 + new Random().nextInt(5));
                if (yeni == null) plugin.getLogger().warning("Vahşi Kale otomatik ilk doldurma: uygun konum bulunamadı (deneme " + (sira + 1) + ").");
            }, 100L + sira * 80L);
        }
    }

    /** Periyodik olarak çağrılır (bkz. KlanSistemi onEnable): fethedilen/silinen kalelerin yerine otomatik yeni kale üretir. */
    public void otomatikKontrolEt() {
        int hedef = plugin.getConfig().getInt("vahsikale.min-aktif-kale-sayisi", 3);
        int eksik = hedef - aktifKaleSayisi();
        for (int i = 0; i < eksik; i++) {
            final int sira = i;
            Bukkit.getScheduler().runTaskLater(plugin, () -> otomatikUret(1 + new Random().nextInt(5)), 40L + sira * 60L);
        }
    }

    /**
     * Dünya spawn'ından ve diğer Vahşi Kalelerden uzak, kuru/düzgün zeminli rastgele bir konum arar.
     * GriefPrevention yüklüyse (opsiyonel, yansıma ile - eksik/uyumsuz olsa sessizce atlanır) arazi
     * talebiyle çakışmayı da kontrol eder.
     */
    private Location rastgeleKonumBul(World dunya, Random rng) {
        double minUzaklik = plugin.getConfig().getDouble("vahsikale.min-spawn-uzakligi", 1200);
        double maxUzaklik = plugin.getConfig().getDouble("vahsikale.max-spawn-uzakligi", 6000);
        double kalelerArasiMin = plugin.getConfig().getDouble("vahsikale.kaleler-arasi-min-uzaklik", 300);
        Location spawn = dunya.getSpawnLocation();

        for (int deneme = 0; deneme < 60; deneme++) {
            double aci = rng.nextDouble() * Math.PI * 2;
            double uzaklik = minUzaklik + rng.nextDouble() * (maxUzaklik - minUzaklik);
            int x = spawn.getBlockX() + (int) Math.round(Math.cos(aci) * uzaklik);
            int z = spawn.getBlockZ() + (int) Math.round(Math.sin(aci) * uzaklik);

            boolean baskaKaleyeCokYakin = false;
            for (VahsiKale k : kaleler.values()) {
                if (k.merkez == null || !dunya.equals(k.merkez.getWorld())) continue;
                if (k.merkez.distance(new Location(dunya, x, k.merkez.getY(), z)) < kalelerArasiMin) {
                    baskaKaleyeCokYakin = true;
                    break;
                }
            }
            if (baskaKaleyeCokYakin) continue;

            dunya.getChunkAt(x >> 4, z >> 4).load();
            int yuksekBlok = dunya.getHighestBlockYAt(x, z);
            if (yuksekBlok < 55 || yuksekBlok > 180) continue; // okyanus tabanı/çok yüksek dağ zirvesi gibi uç durumları ele

            org.bukkit.block.Block ustBlok = dunya.getBlockAt(x, yuksekBlok, z);
            Material ustTur = ustBlok.getType();
            if (ustTur == Material.WATER || ustTur == Material.LAVA || ustTur == Material.ICE
                    || ustTur == Material.PACKED_ICE || ustTur == Material.FROSTED_ICE) continue;

            Location aday = new Location(dunya, x + 0.5, yuksekBlok + 1, z + 0.5);
            if (ariziClaimdeMi(aday)) continue;
            return aday;
        }
        return null;
    }

    /** GriefPrevention yüklüyse claim kontrolü yapar; plugin yoksa/API uyumsuzsa sessizce false döner. */
    private boolean ariziClaimdeMi(Location konum) {
        if (!Bukkit.getPluginManager().isPluginEnabled("GriefPrevention")) return false;
        try {
            Class<?> gpSinifi = Class.forName("me.ryanhamshire.GriefPrevention.GriefPrevention");
            Object instance = gpSinifi.getField("instance").get(null);
            Object dataStore = gpSinifi.getMethod("getDataStore").invoke(instance);
            Object claim = dataStore.getClass()
                    .getMethod("getClaimAt", Location.class, boolean.class, Object.class)
                    .invoke(dataStore, konum, false, null);
            return claim != null;
        } catch (Throwable t) {
            return false; // API uyumsuz/beklenmedik - güvenlik için engellemiyoruz, mesafe kontrolü zaten var
        }
    }

    private void sil(CommandSender s, String[] args) {
        if (args.length < 2) { kullanim(s); return; }
        String aranan = String.join(" ", java.util.Arrays.asList(args).subList(1, args.length));
        VahsiKale kale = bul(aranan);
        if (kale == null) { s.sendMessage(Mesaj.renk("&cBöyle bir kale bulunamadı.")); return; }
        for (UUID npcId : kale.nobetciRolleri.keySet()) {
            org.bukkit.entity.Entity e = Bukkit.getEntity(npcId);
            if (e != null) e.remove();
        }
        kaleler.remove(kale.id);
        silDb(kale.id);
        s.sendMessage(Mesaj.renk("&a" + kale.isim + " silindi."));
    }

    private void liste(CommandSender s) {
        if (kaleler.isEmpty()) { s.sendMessage(Mesaj.renk("&7Hiç Vahşi Kale yok. &e/vahsikale uret")); return; }
        s.sendMessage(Mesaj.renk("&6&l--- VAHŞİ KALELER ---"));
        for (VahsiKale k : kaleler.values()) {
            String durum = k.fethedildi ? "&cFethedildi" : "&a" + k.oluNobetciler.size() + "/" + k.nobetciRolleri.size() + " nöbetçi öldü";
            String tur = "VINDICATOR".equals(k.klanTuru) ? "Vindicator" : "İnsan";
            s.sendMessage(Mesaj.renk("&e" + k.isim + " &7(seviye " + k.seviye + ", " + tur + ") - " + durum));
        }
    }

    private void bilgi(CommandSender s) {
        if (!(s instanceof Player p)) return;
        VahsiKale en = enYakin(p.getLocation());
        if (en == null) { p.sendMessage(Mesaj.renk("&7Yakında kayıtlı bir Vahşi Kale yok.")); return; }
        String turGosterim = "VINDICATOR".equals(en.klanTuru) ? "&cVahşi Vindicator Klanı" : "&eİnsansı Klan";
        p.sendMessage(Mesaj.renk("&6" + en.isim + " &7(" + turGosterim + "&7) | Seviye: &f" + en.seviye + " &7| Durum: "
                + (en.fethedildi ? "&cFethedildi" : "&a" + en.oluNobetciler.size() + "/" + en.nobetciRolleri.size() + " öldü")
                + " &7| Sandık: &f" + en.sandiklar.values().stream().filter(b -> !b).count() + " kilitli / " + en.sandiklar.size() + " toplam"));
    }

    private void girisAyarla(CommandSender s) {
        if (!(s instanceof Player p)) { s.sendMessage(Mesaj.renk("&cBu komut sadece oyun içinden kullanılabilir.")); return; }
        VahsiKale en = enYakin(p.getLocation());
        if (en == null) { p.sendMessage(Mesaj.renk("&cYakında kayıtlı bir Vahşi Kale yok.")); return; }
        en.giris = p.getLocation().clone();
        kaydet(en);
        p.sendMessage(Mesaj.renk("&a" + en.isim + " kalesinin giriş/respawn noktası ayarlandı."));
    }

    private VahsiKale bul(String isimVeyaKismi) {
        for (VahsiKale k : kaleler.values()) if (k.isim.equalsIgnoreCase(isimVeyaKismi)) return k;
        for (VahsiKale k : kaleler.values()) if (k.isim.toLowerCase(Locale.ROOT).contains(isimVeyaKismi.toLowerCase(Locale.ROOT))) return k;
        return null;
    }

    private VahsiKale enYakin(Location l) {
        VahsiKale en = null;
        double enYakinMesafe = Double.MAX_VALUE;
        for (VahsiKale k : kaleler.values()) {
            if (k.merkez == null || k.merkez.getWorld() == null || !k.merkez.getWorld().equals(l.getWorld())) continue;
            double d = k.merkez.distanceSquared(l);
            if (d < enYakinMesafe) { enYakinMesafe = d; en = k; }
        }
        return en;
    }

    // ------------------------------------------------------------------ NÖBETÇİ/LİDER DOĞURMA
    /** Yapı zaten inşa edilmişken (KaleInsaatYardimcisi.insaEt) döndürdüğü iç mekan noktalarına doğurur.
     *  Klan türü (VINDICATOR ham / INSAN giydirilmiş) kale.klanTuru'ya göre - bkz. otomatikUret. */
    private void nobetcileriDogur(VahsiKale kale, KaleInsaatYardimcisi.Sonuc yapi, Random rng) {
        for (Location konum : yapi.nobetciKonumlari) {
            String skin = KlanSkinHavuzu.nobetciSkin(kale.klanTuru, rng);
            dogurBir(kale, konum, "NOBETCI", nobetciCan(kale.seviye), nobetciHasar(kale.seviye),
                    kale.isim + " Nöbetçisi", skin);
        }
        String liderSkin = KlanSkinHavuzu.liderSkin(kale.klanTuru, rng);
        dogurBir(kale, yapi.liderKonumu, "LIDER", liderCan(kale.seviye), nobetciHasar(kale.seviye) * 1.5,
                kale.isim + " Lideri", liderSkin);
    }

    private void dogurBir(VahsiKale kale, Location konum, String rol, double can, double hasar, String goruntuAdi, String skinAdi) {
        Vindicator v = konum.getWorld().spawn(konum, Vindicator.class);
        v.setPersistent(true);
        v.setRemoveWhenFarAway(false);
        v.getPersistentDataContainer().set(kaleIdKey, PersistentDataType.STRING, kale.id.toString());
        v.getPersistentDataContainer().set(rolKey, PersistentDataType.STRING, rol);
        v.setCustomName(ChatColor.RED + goruntuAdi);
        v.setCustomNameVisible(true);
        AttributeInstance canOzniteligi = v.getAttribute(Attribute.MAX_HEALTH);
        if (canOzniteligi != null) canOzniteligi.setBaseValue(can);
        v.setHealth(can);
        AttributeInstance hasarOzniteligi = v.getAttribute(Attribute.ATTACK_DAMAGE);
        if (hasarOzniteligi != null) hasarOzniteligi.setBaseValue(hasar);
        // skinAdi null ise bu NPC "VINDICATOR" tipi bir klana ait - bilerek disguise giydirilmiyor, ham görünüm kalıyor
        if (skinAdi != null) {
            try {
                if (Bukkit.getPluginManager().isPluginEnabled("LibsDisguises")) {
                    NobetciDisguiseYardimcisi.giydir(v, skinAdi, goruntuAdi);
                }
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Vahşi Kale NPC'si giydirilemedi (LibsDisguises sorunu)", t);
            }
        }
        kale.nobetciRolleri.put(v.getUniqueId(), rol);
        kale.nobetciSkinleri.put(v.getUniqueId(), skinAdi);
        nobetciKaydet(kale.id, v.getUniqueId(), rol, false, skinAdi);
    }

    /** Sunucu restart'ında LibsDisguises tüm disguise'ları kaybeder; mevcut nöbetçiler PERSİSTE EDİLMİŞ
     *  kendi skin'leriyle (bkz. nobetciSkinleri, DB'den yüklenir) yeniden giydirilir - VINDICATOR tipi
     *  klanlarda skin null olduğundan dokunulmaz, ham görünüm korunur. */
    private void disguiseleriTazele() {
        if (!Bukkit.getPluginManager().isPluginEnabled("LibsDisguises")) return;
        for (VahsiKale kale : kaleler.values()) {
            for (Map.Entry<UUID, String> e : kale.nobetciRolleri.entrySet()) {
                if (kale.oluNobetciler.contains(e.getKey())) continue;
                String skin = kale.nobetciSkinleri.get(e.getKey());
                if (skin == null) continue; // VINDICATOR tipi - disguise yok
                org.bukkit.entity.Entity ent = Bukkit.getEntity(e.getKey());
                if (ent instanceof LivingEntity le) {
                    String goruntuAdi = kale.isim + ("LIDER".equals(e.getValue()) ? " Lideri" : " Nöbetçisi");
                    try {
                        NobetciDisguiseYardimcisi.giydir(le, skin, goruntuAdi);
                    } catch (Throwable t) {
                        plugin.getLogger().log(Level.WARNING, "Vahşi Kale disguise tazeleme hatası", t);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ TESPİT / SALDIRI (periyodik)
    public void tick() {
        long simdi = System.currentTimeMillis();
        for (VahsiKale kale : kaleler.values()) {
            if (kale.fethedildi || kale.merkez == null || kale.merkez.getWorld() == null) continue;
            for (Player p : kale.merkez.getWorld().getPlayers()) {
                double mesafe = p.getLocation().distance(kale.merkez);
                boolean icerde = mesafe <= kale.saldiriYaricap;
                if (icerde) {
                    if (kale.kusatmadakiOyuncular.add(p.getUniqueId())) {
                        Klan klan = plugin.klanManager().oyuncununKlani(p.getUniqueId());
                        if (klan != null) plugin.klanManager().klanaGonder(klan, "vahsikale-kusatma",
                                "&c{oyuncu} &7bir Vahşi Kaleye (&e{kale}&7) saldırıyor!", "oyuncu", p.getName(), "kale", kale.isim);
                    }
                    saldirTetikle(kale, p);
                } else {
                    kale.kusatmadakiOyuncular.remove(p.getUniqueId());
                    if (mesafe <= kale.tespitYaricap) {
                        Long son = sonUyari.get(p.getUniqueId());
                        if (son == null || simdi - son > 5000L) {
                            p.sendActionBar(Mesaj.renk("&c" + kale.isim + " Klanı'nın Kalesi - Buradan uzak dur!"));
                            sonUyari.put(p.getUniqueId(), simdi);
                        }
                    }
                }
            }
        }
    }

    private void saldirTetikle(VahsiKale kale, Player p) {
        for (UUID npcId : kale.nobetciRolleri.keySet()) {
            if (kale.oluNobetciler.contains(npcId)) continue;
            org.bukkit.entity.Entity e = Bukkit.getEntity(npcId);
            if (e instanceof Mob mob && mob.isValid() && (mob.getTarget() == null || !mob.getTarget().equals(p))) {
                if (mob.getLocation().distance(p.getLocation()) <= kale.saldiriYaricap + 5) mob.setTarget(p);
            }
        }
    }

    // ------------------------------------------------------------------ OLAYLAR
    @EventHandler(priority = EventPriority.MONITOR)
    public void onOlum(EntityDeathEvent event) {
        String kaleIdStr = event.getEntity().getPersistentDataContainer().get(kaleIdKey, PersistentDataType.STRING);
        if (kaleIdStr == null) return;
        VahsiKale kale = kaleler.get(UUID.fromString(kaleIdStr));
        if (kale == null) return;
        kale.oluNobetciler.add(event.getEntity().getUniqueId());
        nobetciOldu(kale.id, event.getEntity().getUniqueId());
        if (kale.tumuOldu()) {
            Bukkit.getOnlinePlayers().forEach(pl -> {
                if (pl.getLocation().getWorld().equals(kale.merkez.getWorld()) && pl.getLocation().distance(kale.merkez) <= kale.tespitYaricap * 2) {
                    pl.sendMessage(Mesaj.renk("&6" + kale.isim + " &7savunması çökertildi! Sandıklar artık kırılabilir."));
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSandikKir(BlockBreakEvent event) {
        String konum = ArenaManager.konumYaz(event.getBlock().getLocation());
        VahsiKale kale = null;
        for (VahsiKale k : kaleler.values()) if (k.sandiklar.containsKey(konum)) { kale = k; break; }
        if (kale == null) return;
        if (Boolean.TRUE.equals(kale.sandiklar.get(konum))) return; // zaten açıldı, normal blok kırma davranışı
        if (!kale.tumuOldu()) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Mesaj.renk("&cÖnce bu kalenin tüm nöbetçilerini ve liderini yok etmelisin."));
            return;
        }
        // İdempotent kilit açma: iki oyuncu aynı anda kırarsa bile sadece bir kez ödül verilir
        kale.sandiklar.put(konum, true);
        sandikKaydet(kale.id, konum, true);
        // event.setDropItems(false) KASITLI OLARAK çağrılmıyor: sandık LootTablosu ile gerçek item'larla
        // dolduruldu, kırılınca o item'lar normal şekilde yere dökülsün (önceki sürümde sandık hep boştu).

        Klan klan = plugin.klanManager().oyuncununKlani(event.getPlayer().getUniqueId());
        if (klan == null) {
            event.getPlayer().sendMessage(Mesaj.renk("&eSandığı kırdın ama bir klana üye olmadığın için ödül bir yere aktarılamadı."));
            return;
        }
        double miktar = Para.kurus(sandikLootMin(kale.seviye) + Math.random() * (sandikLootMax(kale.seviye) - sandikLootMin(kale.seviye)));
        klan.kasa = Para.kurus(klan.kasa + miktar);
        plugin.log().yaz(klan, event.getPlayer().getName(), "VAHSI_KALE_LOOT", kale.isim + " -> " + Para.yaz(miktar));
        event.getPlayer().playSound(event.getPlayer().getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        plugin.klanManager().klanaGonder(klan, "vahsikale-loot", "&a{oyuncu} &7{kale} sandığını kırdı: &e{miktar}&7 klan kasasına aktarıldı.",
                "oyuncu", event.getPlayer().getName(), "kale", kale.isim, "miktar", Para.yaz(miktar));

        if (!kale.prestijVerildi) {
            boolean hepsiAcildi = kale.sandiklar.values().stream().allMatch(Boolean::booleanValue);
            if (hepsiAcildi || kale.sandiklar.isEmpty()) {
                kale.prestijVerildi = true;
                kale.fethedildi = true;
                kale.fetihedenKlanId = klan.id;
                kale.fetihZamani = System.currentTimeMillis();
                klan.prestij += fetihPrestiji();
                plugin.veri().kaydet();
                kaydet(kale);
                plugin.klanManager().klanaGonder(klan, "vahsikale-fetih",
                        "&6{kale} &7tamamen fethedildi! Klanınız &e+{prestij} prestij&7 kazandı.",
                        "kale", kale.isim, "prestij", fetihPrestiji());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onOyuncuOlum(PlayerDeathEvent event) {
        olumNotEt(event.getEntity());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player p = event.getPlayer();
        VahsiKale kale = kaleOlumYeri.remove(p.getUniqueId());
        if (kale != null && kale.giris != null && kale.giris.getWorld() != null) {
            event.setRespawnLocation(kale.giris);
        }
    }

    private final Map<UUID, VahsiKale> kaleOlumYeri = new LinkedHashMap<>();

    /** SaglikManager/vanilla ölüm anında (öldüğü an) hangi kalede olduğunu not eder - respawn'da kullanılır. */
    public void olumNotEt(Player p) {
        for (VahsiKale kale : kaleler.values()) {
            if (!kale.fethedildi && kale.kusatmadakiOyuncular.contains(p.getUniqueId())) {
                kaleOlumYeri.put(p.getUniqueId(), kale);
                Klan klan = plugin.klanManager().oyuncununKlani(p.getUniqueId());
                if (klan != null) {
                    klan.prestij = Math.max(0, klan.prestij - olumPrestijKaybi());
                    plugin.veri().kaydet();
                    plugin.klanManager().klanaGonder(klan, "vahsikale-olum-prestij",
                            "&c{oyuncu} &7bir Vahşi Kalede öldü, klan &e-{kayip} prestij&7 kaybetti.",
                            "oyuncu", p.getName(), "kayip", olumPrestijKaybi());
                }
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onKomutEngeli(PlayerCommandPreprocessEvent event) {
        String komut = event.getMessage().toLowerCase(Locale.ROOT).split(" ")[0].substring(1);
        if (!komut.equals("home") && !komut.equals("back") && !komut.equals("tpa") && !komut.equals("tpaccept")
                && !komut.equals("tpyes") && !komut.equals("spawn")) return;
        Player p = event.getPlayer();
        for (VahsiKale kale : kaleler.values()) {
            if (!kale.fethedildi && kale.kusatmadakiOyuncular.contains(p.getUniqueId())) {
                event.setCancelled(true);
                p.sendMessage(Mesaj.renk("&cKuşatma altındayken bu komutu kullanamazsın! Önce kale alanından çık."));
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEtkilesim(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        // Elçi NPC (ticaret/görev/barış) bu sürümde YOK - ileride eklenecek. Şimdilik sadece sandık kilidi geçerli.
    }

    // ------------------------------------------------------------------ SQLITE
    private void baglan() {
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            Class.forName("org.sqlite.JDBC");
            baglanti = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "vahsikaleler.db'ye bağlanılamadı! Vahşi Kale sistemi devre dışı kalabilir.", e);
        }
    }

    private void tablolariOlustur() {
        if (baglanti == null) return;
        try (Statement st = baglanti.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS kaleler (id TEXT PRIMARY KEY, isim TEXT, seviye INTEGER, " +
                    "merkez TEXT, giris TEXT, tespit_yaricap REAL, saldiri_yaricap REAL, " +
                    "fethedildi INTEGER DEFAULT 0, fetheden_klan TEXT, fetih_zamani INTEGER DEFAULT 0, prestij_verildi INTEGER DEFAULT 0)");
            st.execute("CREATE TABLE IF NOT EXISTS nobetciler (kale_id TEXT, npc_uuid TEXT, rol TEXT, oldu INTEGER DEFAULT 0, " +
                    "PRIMARY KEY (kale_id, npc_uuid))");
            st.execute("CREATE TABLE IF NOT EXISTS sandiklar (kale_id TEXT, konum TEXT, acildi INTEGER DEFAULT 0, " +
                    "PRIMARY KEY (kale_id, konum))");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "vahsikaleler.db tabloları oluşturulamadı!", e);
        }
        // Migration: önceki sürümlerde olmayan kolonlar - zaten varsa SQLException atar, sessizce yutulur
        migrasyonKolonEkle("kaleler", "klan_turu TEXT DEFAULT 'INSAN'");
        migrasyonKolonEkle("nobetciler", "skin TEXT");
    }

    private void migrasyonKolonEkle(String tablo, String kolonTanimi) {
        if (baglanti == null) return;
        try (Statement st = baglanti.createStatement()) {
            st.execute("ALTER TABLE " + tablo + " ADD COLUMN " + kolonTanimi);
        } catch (SQLException ignored) {
            // Kolon zaten var - beklenen durum, sessizce geç
        }
    }

    private void kaydet(VahsiKale kale) {
        if (baglanti == null) return;
        plugin.arkaPlanda(() -> {
            try (PreparedStatement ps = baglanti.prepareStatement(
                    "INSERT INTO kaleler (id, isim, seviye, merkez, giris, tespit_yaricap, saldiri_yaricap, fethedildi, fetheden_klan, fetih_zamani, prestij_verildi, klan_turu) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET isim=excluded.isim, seviye=excluded.seviye, " +
                            "merkez=excluded.merkez, giris=excluded.giris, tespit_yaricap=excluded.tespit_yaricap, saldiri_yaricap=excluded.saldiri_yaricap, " +
                            "fethedildi=excluded.fethedildi, fetheden_klan=excluded.fetheden_klan, fetih_zamani=excluded.fetih_zamani, prestij_verildi=excluded.prestij_verildi, " +
                            "klan_turu=excluded.klan_turu")) {
                ps.setString(1, kale.id.toString());
                ps.setString(2, kale.isim);
                ps.setInt(3, kale.seviye);
                ps.setString(4, ArenaManager.konumYaz(kale.merkez));
                ps.setString(5, ArenaManager.konumYaz(kale.giris));
                ps.setDouble(6, kale.tespitYaricap);
                ps.setDouble(7, kale.saldiriYaricap);
                ps.setInt(8, kale.fethedildi ? 1 : 0);
                ps.setString(9, kale.fetihedenKlanId == null ? null : kale.fetihedenKlanId.toString());
                ps.setLong(10, kale.fetihZamani);
                ps.setInt(11, kale.prestijVerildi ? 1 : 0);
                ps.setString(12, kale.klanTuru);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Vahşi Kale kaydedilemedi: " + kale.isim, e);
            }
        });
    }

    private void silDb(UUID id) {
        if (baglanti == null) return;
        plugin.arkaPlanda(() -> {
            try (PreparedStatement p1 = baglanti.prepareStatement("DELETE FROM kaleler WHERE id=?");
                 PreparedStatement p2 = baglanti.prepareStatement("DELETE FROM nobetciler WHERE kale_id=?");
                 PreparedStatement p3 = baglanti.prepareStatement("DELETE FROM sandiklar WHERE kale_id=?")) {
                p1.setString(1, id.toString()); p1.executeUpdate();
                p2.setString(1, id.toString()); p2.executeUpdate();
                p3.setString(1, id.toString()); p3.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Vahşi Kale silinemedi: " + id, e);
            }
        });
    }

    private void nobetciKaydet(UUID kaleId, UUID npcId, String rol, boolean oldu, String skin) {
        if (baglanti == null) return;
        plugin.arkaPlanda(() -> {
            try (PreparedStatement ps = baglanti.prepareStatement(
                    "INSERT INTO nobetciler (kale_id, npc_uuid, rol, oldu, skin) VALUES (?,?,?,?,?) " +
                            "ON CONFLICT(kale_id, npc_uuid) DO UPDATE SET oldu=excluded.oldu, skin=excluded.skin")) {
                ps.setString(1, kaleId.toString());
                ps.setString(2, npcId.toString());
                ps.setString(3, rol);
                ps.setInt(4, oldu ? 1 : 0);
                ps.setString(5, skin);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Nöbetçi kaydedilemedi", e);
            }
        });
    }

    private void nobetciOldu(UUID kaleId, UUID npcId) {
        if (baglanti == null) return;
        plugin.arkaPlanda(() -> {
            try (PreparedStatement ps = baglanti.prepareStatement(
                    "UPDATE nobetciler SET oldu=1 WHERE kale_id=? AND npc_uuid=?")) {
                ps.setString(1, kaleId.toString());
                ps.setString(2, npcId.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Nöbetçi ölüm durumu kaydedilemedi", e);
            }
        });
    }

    private void sandikKaydet(UUID kaleId, String konum, boolean acildi) {
        if (baglanti == null) return;
        plugin.arkaPlanda(() -> {
            try (PreparedStatement ps = baglanti.prepareStatement(
                    "INSERT INTO sandiklar (kale_id, konum, acildi) VALUES (?,?,?) " +
                            "ON CONFLICT(kale_id, konum) DO UPDATE SET acildi=excluded.acildi")) {
                ps.setString(1, kaleId.toString());
                ps.setString(2, konum);
                ps.setInt(3, acildi ? 1 : 0);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Sandık kaydedilemedi", e);
            }
        });
    }

    private void yukle() {
        if (baglanti == null) return;
        try (Statement st = baglanti.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM kaleler")) {
            while (rs.next()) {
                VahsiKale kale = new VahsiKale(UUID.fromString(rs.getString("id")));
                kale.isim = rs.getString("isim");
                kale.seviye = rs.getInt("seviye");
                kale.merkez = ArenaManager.konumOku(rs.getString("merkez"));
                kale.giris = ArenaManager.konumOku(rs.getString("giris"));
                kale.tespitYaricap = rs.getDouble("tespit_yaricap");
                kale.saldiriYaricap = rs.getDouble("saldiri_yaricap");
                kale.fethedildi = rs.getInt("fethedildi") == 1;
                String fklan = rs.getString("fetheden_klan");
                kale.fetihedenKlanId = fklan == null ? null : UUID.fromString(fklan);
                kale.fetihZamani = rs.getLong("fetih_zamani");
                kale.prestijVerildi = rs.getInt("prestij_verildi") == 1;
                String klanTuru = rs.getString("klan_turu");
                kale.klanTuru = klanTuru == null ? "INSAN" : klanTuru;
                if (kale.merkez == null) {
                    plugin.getLogger().warning("'" + kale.isim + "' Vahşi Kalesinin dünyası yüklü değil, şimdilik atlanıyor.");
                    continue;
                }
                kaleler.put(kale.id, kale);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Vahşi Kaleler yüklenemedi!", e);
            return;
        }
        try (PreparedStatement ps = baglanti.prepareStatement("SELECT * FROM nobetciler WHERE kale_id=?")) {
            for (VahsiKale kale : kaleler.values()) {
                ps.setString(1, kale.id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID npcId = UUID.fromString(rs.getString("npc_uuid"));
                        kale.nobetciRolleri.put(npcId, rs.getString("rol"));
                        kale.nobetciSkinleri.put(npcId, rs.getString("skin"));
                        if (rs.getInt("oldu") == 1) kale.oluNobetciler.add(npcId);
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Vahşi Kale nöbetçileri yüklenemedi!", e);
        }
        try (PreparedStatement ps = baglanti.prepareStatement("SELECT * FROM sandiklar WHERE kale_id=?")) {
            for (VahsiKale kale : kaleler.values()) {
                ps.setString(1, kale.id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        kale.sandiklar.put(rs.getString("konum"), rs.getInt("acildi") == 1);
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Vahşi Kale sandıkları yüklenemedi!", e);
        }
        if (!kaleler.isEmpty()) {
            plugin.getLogger().info(kaleler.size() + " Vahşi Kale yüklendi.");
            Bukkit.getScheduler().runTaskLater(plugin, this::disguiseleriTazele, 100L);
        }
    }

    public void kapat() {
        if (baglanti != null) {
            try { baglanti.close(); } catch (SQLException ignored) { }
        }
    }
}
