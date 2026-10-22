package me.klansistemi.vahsikale;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
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
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vindicator;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.persistence.PersistentDataType;

import me.klansistemi.KlanSistemi;
import me.klansistemi.Mesaj;
import me.klansistemi.Para;
import me.klansistemi.model.Klan;
import me.klansistemi.model.KlanUyesi;
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
    // Oyuncu -> ışınlanma kilidi biten zaman (ms). Kuşatmadan geri çekilenlere VE 30 saniye içinde
    // "yardım" çağrısına cevap vermeyen klan üyelerine uygulanır - takviye/kaçış amaçlı anlık
    // ışınlanmayı önlemek için (bkz. tick(), kusatmaBasladi(), onKomutEngeli).
    private final Map<UUID, Long> tpKilitli = new LinkedHashMap<>();
    // Kale id -> o kaleye yönelik aktif yardım çağrısını kabul eden oyuncu UUID'leri (30 sn'lik
    // pencere boyunca tutulur, bkz. kusatmaBasladi() / "/vahsikale yardim").
    private final Map<UUID, Set<UUID>> yardimKabulEdenler = new LinkedHashMap<>();

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
    // Not: Bir kalede 2-4 sandık olabiliyor, her biri bu aralıktan AYRI AYRI nakit veriyor - eskiden
    // 3000-8000*seviye idi, bu da seviye 5'te sandık başına 15000-40000, birkaç sandıkla birlikte
    // 80000+ toplam veriyordu. Kullanıcı isteğiyle sandık başına çok daha mütevazı bir miktara düşürüldü.
    private double sandikLootMin(int seviye) { return plugin.getConfig().getDouble("vahsikale.seviye-" + seviye + ".sandik-min", 400 * seviye); }
    private double sandikLootMax(int seviye) { return plugin.getConfig().getDouble("vahsikale.seviye-" + seviye + ".sandik-max", 1000 * seviye); }
    private int fetihPrestiji() { return plugin.getConfig().getInt("vahsikale.fetih-prestiji", 10); }
    private int olumPrestijKaybi() { return plugin.getConfig().getInt("vahsikale.olum-prestij-kaybi", 30); }
    // 1 Minecraft günü = 24000 tick = 20 gerçek dakika (normal tick hızında) = 1.200.000 ms.
    // Varsayılan 2 Minecraft günü (40 gerçek dakika) - fethedilen kale bu süre sonunda otomatik silinir.
    private long fethedilenSilinmeMs() { return plugin.getConfig().getLong("vahsikale.fethedilen-silinme-minecraft-gun", 2) * 1_200_000L; }

    // ------------------------------------------------------------------ KOMUT
    public void komut(CommandSender s, String[] args) {
        if (args.length < 1) { kullanim(s); return; }
        String alt = args[0].toLowerCase(Locale.ROOT);
        switch (alt) {
            case "uret" -> uret(s, args);
            case "sil" -> sil(s, args);
            case "hepsinisil" -> hepsiniSil(s);
            case "liste" -> liste(s);
            case "bilgi" -> bilgi(s);
            case "giris" -> girisAyarla(s);
            case "yardim" -> yardimKabulEt(s);
            case "git" -> git(s, args);
            default -> kullanim(s);
        }
    }

    private void kullanim(CommandSender s) {
        s.sendMessage(Mesaj.renk("&cKullanım: &e/vahsikale <uret [seviye 1-5]|sil <isim>|hepsinisil|liste|bilgi|giris|yardim|git <isim>>"));
    }

    /** /vahsikale git <isim>: admin, kalenin giriş noktasına (ayarlanmadıysa merkezine) ışınlanır -
     *  liste()'deki tıklanabilir koordinat butonu da bunu çağırır (bkz. ClickEvent.runCommand). */
    private void git(CommandSender s, String[] args) {
        if (!(s instanceof Player p)) { s.sendMessage(Mesaj.renk("&cBu komut sadece oyun içinden kullanılabilir.")); return; }
        if (args.length < 2) { kullanim(s); return; }
        String aranan = String.join(" ", java.util.Arrays.asList(args).subList(1, args.length));
        VahsiKale kale = bul(aranan);
        if (kale == null) { p.sendMessage(Mesaj.renk("&cBöyle bir kale bulunamadı.")); return; }
        Location hedef = (kale.giris != null) ? kale.giris : kale.merkez;
        if (hedef == null || hedef.getWorld() == null) { p.sendMessage(Mesaj.renk("&cBu kalenin geçerli bir konumu yok.")); return; }
        p.teleport(hedef);
        p.sendMessage(Mesaj.renk("&a" + kale.isim + " kalesine ışınlandın."));
    }

    /** Bir klan üyesi, klanının aktif bir Vahşi Kale kuşatması için gelen yardım çağrısını 30 sn
     *  içinde kabul eder - bkz. kusatmaBasladi(). Kabul etmek, 30 sn sonra uygulanacak 5 dakikalık
     *  TP kilidinden muafiyet sağlar (fiilen gelip gelmemek oyuncunun kendi işi, bu sadece "geldim/
     *  geliyorum" bildirimi). */
    private void yardimKabulEt(CommandSender s) {
        if (!(s instanceof Player p)) return;
        Klan klan = plugin.klanManager().oyuncununKlani(p.getUniqueId());
        if (klan == null) { p.sendMessage(Mesaj.renk("&cBir klanda değilsin.")); return; }
        boolean bulundu = false;
        for (Map.Entry<UUID, Set<UUID>> e : yardimKabulEdenler.entrySet()) {
            VahsiKale kale = kaleler.get(e.getKey());
            if (kale == null) continue;
            boolean klanSaldiriyor = kale.kusatmadakiOyuncular.stream().anyMatch(uuid -> klan.uyeler.containsKey(uuid));
            if (klanSaldiriyor) { e.getValue().add(p.getUniqueId()); bulundu = true; }
        }
        if (bulundu) p.sendMessage(Mesaj.renk("&aYardım çağrısını kabul ettin - ışınlanma kilidinden muafsın."));
        else p.sendMessage(Mesaj.renk("&7Şu an klanının aktif bir kuşatma yardım çağrısı yok."));
    }

    /** Şu an kayıtlı TÜM Vahşi Kaleleri tek seferde siler: NPC'leri dünyadan kaldırır,
     *  bellekten ve SQLite'tan (kaleler/nobetciler/sandiklar) temizler. Fiziksel yapı (bloklar)
     *  dünyada kalır - sadece oyun kaydı/varlıkları temizlenir, test verisini toplu sıfırlamak için. */
    private void hepsiniSil(CommandSender s) {
        if (kaleler.isEmpty()) { s.sendMessage(Mesaj.renk("&7Silinecek Vahşi Kale yok.")); return; }
        int toplam = kaleler.size();
        List<VahsiKale> kopya = new ArrayList<>(kaleler.values());
        for (VahsiKale kale : kopya) {
            for (UUID npcId : kale.nobetciRolleri.keySet()) {
                org.bukkit.entity.Entity e = Bukkit.getEntity(npcId);
                if (e != null) e.remove();
            }
            kaleler.remove(kale.id);
            silDb(kale.id);
        }
        s.sendMessage(Mesaj.renk("&a" + toplam + " Vahşi Kale (ve nöbetçileri/sandık kayıtları) silindi."));
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

        // Klan türü yapı inşasından ÖNCE belirlenir: Vindicator/İnsan klanlarının kaleleri artık
        // farklı malzeme/süsleme paletiyle inşa ediliyor (bkz. KaleInsaatYardimcisi), sadece nöbetçi
        // skin'i değil.
        String klanTuru = KlanSkinHavuzu.rastgeleKlanTuru(rng);
        KaleInsaatYardimcisi.Sonuc yapi = KaleInsaatYardimcisi.insaEt(konum, seviye, rng, klanTuru);

        VahsiKale kale = new VahsiKale(UUID.randomUUID());
        kale.seviye = seviye;
        kale.isim = IsimUretici.uret(seviye);
        kale.merkez = yapi.girisIci.clone();
        kale.giris = yapi.girisIci.clone();
        kale.tespitYaricap = varsayilanTespit();
        kale.saldiriYaricap = varsayilanSaldiri();
        kale.klanTuru = klanTuru;
        kale.sinirMinX = yapi.sinirMinX; kale.sinirMaxX = yapi.sinirMaxX;
        kale.sinirMinY = yapi.sinirMinY; kale.sinirMaxY = yapi.sinirMaxY;
        kale.sinirMinZ = yapi.sinirMinZ; kale.sinirMaxZ = yapi.sinirMaxZ;
        kaleler.put(kale.id, kale);
        kaydet(kale);

        // DİKKAT: getState() (snapshot=true) DEĞİL, getState(false) (CANLI state) kullanılıyor.
        // Kök sebep (loglarla doğrulandı): snapshot'un getInventory()'si her çağrıldığında
        // envanterin ayrı bir KOPYASINI veriyor olabiliyor - doldur() o kopyayı doldursa da
        // update(true,false) orijinal (hâlâ boş) snapshot verisini dünyaya geri yazıyordu, sonuç:
        // sandık dünyada "chest" olarak görünüyor ama Items hep boş kalıyordu (seviye 1 tick erteleme
        // denemesi de bunu çözmedi, çünkü sorun zamanlama değil, snapshot/live state ayrımıydı).
        // Live state ile setItem DOĞRUDAN gerçek tile entity'ye yazılıyor.
        int dolduruldu = 0;
        for (Location sandikKonum : yapi.sandikKonumlari) {
            String konumYazisi = ArenaManager.konumYaz(sandikKonum);
            kale.sandiklar.put(konumYazisi, false);
            sandikKaydet(kale.id, konumYazisi, false);
            BlockState durum = sandikKonum.getBlock().getState(false);
            if (durum instanceof Chest sandik) {
                LootTablosu.doldur(sandik.getInventory(), seviye, rng);
                sandik.update(true, false);
                int itemSayisi = 0;
                for (ItemStack it : sandik.getInventory().getContents()) if (it != null) itemSayisi++;
                if (itemSayisi > 0) dolduruldu++;
                plugin.getLogger().info("[VahsiKale] Sandık dolduruldu: " + sandikKonum.getBlockX() + ","
                        + sandikKonum.getBlockY() + "," + sandikKonum.getBlockZ() + " -> " + itemSayisi
                        + " item (envanter boyutu " + sandik.getInventory().getSize() + ")");
            } else {
                plugin.getLogger().warning("[VahsiKale] Sandık konumunda CHEST bulunamadı! tür=" + durum.getType()
                        + " @ " + sandikKonum.getBlockX() + "," + sandikKonum.getBlockY() + "," + sandikKonum.getBlockZ());
            }
        }
        plugin.getLogger().info("[VahsiKale] " + kale.isim + ": " + dolduruldu + "/" + yapi.sandikKonumlari.size()
                + " sandık başarıyla loot ile dolduruldu.");

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
        silKaleyi(kale);
        s.sendMessage(Mesaj.renk("&a" + kale.isim + " silindi."));
    }

    /** Bir kaleyi (nöbetçi entity'leri dahil) hem bellekten hem DB'den kaldırır. sil() komutu ve
     *  klan silme kaskad mantığı (bkz. klanaAitKaleleriSil) tarafından paylaşılır. */
    private void silKaleyi(VahsiKale kale) {
        for (UUID npcId : kale.nobetciRolleri.keySet()) {
            org.bukkit.entity.Entity e = Bukkit.getEntity(npcId);
            if (e != null) e.remove();
        }
        kaleler.remove(kale.id);
        silDb(kale.id);
    }

    /** Bir klan silindiğinde/dağıldığında, o klanın fethettiği Vahşi Kale(ler) de silinir -
     *  aksi halde kale "fethedildi" durumunda kalır ama artık var olmayan bir klana referans tutar.
     *  Silinen kale sayısını döner (log/bilgi mesajı için). Periyodik kontrol (otomatikKontrolEt)
     *  eksik kalan aktif kale sayısını zaten otomatik tamamlayacaktır. */
    public int klanaAitKaleleriSil(UUID klanId) {
        List<VahsiKale> silinecekler = new ArrayList<>();
        for (VahsiKale kale : kaleler.values()) {
            if (klanId.equals(kale.fetihedenKlanId)) silinecekler.add(kale);
        }
        for (VahsiKale kale : silinecekler) silKaleyi(kale);
        return silinecekler.size();
    }

    private void liste(CommandSender s) {
        if (kaleler.isEmpty()) { s.sendMessage(Mesaj.renk("&7Hiç Vahşi Kale yok. &e/vahsikale uret")); return; }
        s.sendMessage(Mesaj.renk("&6&l--- VAHŞİ KALELER ---"));
        for (VahsiKale k : kaleler.values()) {
            String durum = k.fethedildi ? "&cFethedildi" : "&a" + k.oluNobetciler.size() + "/" + k.nobetciRolleri.size() + " nöbetçi öldü";
            String tur = "VINDICATOR".equals(k.klanTuru) ? "Vindicator" : "İnsan";
            net.kyori.adventure.text.Component satir = net.kyori.adventure.text.Component.text()
                    .append(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                            .deserialize(Mesaj.renk("&e" + k.isim + " &7(seviye " + k.seviye + ", " + tur + ") - " + durum + " &7| ")))
                    .append(koordinatButonu(k))
                    .build();
            s.sendMessage(satir);
        }
    }

    /** liste()'de her kale için gösterilen, tıklanınca o kaleye ışınlayan koordinat bileşeni
     *  (/vahsikale git <isim> komutunu tetikler). Konum yoksa düz "konum yok" metni döner. */
    private net.kyori.adventure.text.Component koordinatButonu(VahsiKale k) {
        if (k.merkez == null || k.merkez.getWorld() == null) {
            return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                    .deserialize(Mesaj.renk("&8[konum yok]"));
        }
        String koordMetni = k.merkez.getWorld().getName() + " " + k.merkez.getBlockX() + ", " + k.merkez.getBlockY() + ", " + k.merkez.getBlockZ();
        net.kyori.adventure.text.Component ipucu = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                .deserialize(Mesaj.renk("&7Işınlanmak için tıkla: &e/vahsikale git " + k.isim));
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                .deserialize(Mesaj.renk("&b&n[" + koordMetni + "]"))
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/vahsikale git " + k.isim))
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(ipucu));
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
        donatimGiydir(v, rol);
        // skinAdi null ise bu NPC "VINDICATOR" tipi bir klana ait - bilerek disguise giydirilmiyor, ham görünüm kalıyor
        // 1 tik sonraya bırakılıyor: entity world.spawn() ile AYNI tikte disguise edilirse (henüz dünyanın
        // entity takip listesine tam eklenmemiş olabileceğinden) LibsDisguises bazı Paper sürümlerinde
        // sessizce (hata fırlatmadan) tutmayabiliyor - bu yüzden isDisguised ile de doğrulanıp loglanıyor.
        if (skinAdi != null && Bukkit.getPluginManager().isPluginEnabled("LibsDisguises")) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    boolean basarili = NobetciDisguiseYardimcisi.giydir(v, skinAdi, goruntuAdi);
                    if (!basarili) {
                        plugin.getLogger().warning("Vahşi Kale NPC'si (" + goruntuAdi + ") disguise UYGULANAMADI (isDisguised=false) - skin: " + skinAdi);
                    }
                } catch (Throwable t) {
                    plugin.getLogger().log(Level.WARNING, "Vahşi Kale NPC'si giydirilemedi (LibsDisguises sorunu)", t);
                }
            });
        }
        kale.nobetciRolleri.put(v.getUniqueId(), rol);
        kale.nobetciSkinleri.put(v.getUniqueId(), skinAdi);
        nobetciKaydet(kale.id, v.getUniqueId(), rol, false, skinAdi);
    }

    /** Nöbetçiler büyülü demir zırh + kalkan, liderler büyülü elmas zırh giyer. Not: vanilla
     *  Vindicator AI'ının aktif "kalkanla blokla" davranışı yok (bu sadece oyuncularda/Piglinlerde
     *  var) - kalkan elde tutulur ve kol/el görünümünü değiştirir ama otomatik blok yapmaz. */
    private void donatimGiydir(Vindicator v, String rol) {
        boolean lider = "LIDER".equals(rol);
        EntityEquipment ekip = v.getEquipment();
        if (ekip == null) return;
        Material[] parcalar = lider
                ? new Material[]{Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS}
                : new Material[]{Material.IRON_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_BOOTS};
        ItemStack[] zirhlar = new ItemStack[4];
        for (int i = 0; i < parcalar.length; i++) {
            ItemStack parca = new ItemStack(parcalar[i]);
            ItemMeta meta = parca.getItemMeta();
            if (meta != null) {
                meta.addEnchant(Enchantment.PROTECTION, lider ? 3 : 2, true);
                meta.addEnchant(Enchantment.UNBREAKING, 2, true);
                parca.setItemMeta(meta);
            }
            zirhlar[i] = parca;
        }
        ekip.setHelmet(zirhlar[0]);
        ekip.setChestplate(zirhlar[1]);
        ekip.setLeggings(zirhlar[2]);
        ekip.setBoots(zirhlar[3]);
        ekip.setItemInOffHand(new ItemStack(Material.SHIELD));
        // Ölünce zırh/kalkan düşürmesin - bu görsel/savaş donanımı, gerçek loot zaten sandıklarda.
        ekip.setHelmetDropChance(0f);
        ekip.setChestplateDropChance(0f);
        ekip.setLeggingsDropChance(0f);
        ekip.setBootsDropChance(0f);
        ekip.setItemInOffHandDropChance(0f);
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
                        boolean basarili = NobetciDisguiseYardimcisi.giydir(le, skin, goruntuAdi);
                        if (!basarili) {
                            plugin.getLogger().warning("Vahşi Kale disguise TAZELEME BAŞARISIZ (isDisguised=false): " + goruntuAdi + " - skin: " + skin);
                        }
                    } catch (Throwable t) {
                        plugin.getLogger().log(Level.WARNING, "Vahşi Kale disguise tazeleme hatası", t);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ TESPİT / SALDIRI (periyodik)
    private int disguiseBekciSayaci = 0;

    /** Her tick() çağrısında (1 sn) sayaç artırılır; ~15 saniyede bir, disguise'ı düşmüş (ör. bir
     *  anti-cheat/paket eklentisinin resync'i, LibsDisguises'in geçici bir hatası) İnsan tipi
     *  nöbetçi/lider NPC'leri tespit edip yeniden giydirir - ham Vindicator görünümünde kalıp
     *  kalmadıklarını kalıcı olarak düzeltir (bkz. kullanıcı raporu: "hala vindicator'dı"). */
    private void disguiseBekcisi() {
        if (!Bukkit.getPluginManager().isPluginEnabled("LibsDisguises")) return;
        for (VahsiKale kale : kaleler.values()) {
            if (kale.fethedildi) continue;
            for (Map.Entry<UUID, String> e : kale.nobetciSkinleri.entrySet()) {
                String skin = e.getValue();
                if (skin == null) continue; // VINDICATOR tipi - ham görünüm bilerek korunuyor
                if (kale.oluNobetciler.contains(e.getKey())) continue;
                org.bukkit.entity.Entity ent = Bukkit.getEntity(e.getKey());
                if (!(ent instanceof LivingEntity le)) continue;
                if (NobetciDisguiseYardimcisi.disguisliMi(le)) continue;
                String rol = kale.nobetciRolleri.get(e.getKey());
                String goruntuAdi = kale.isim + ("LIDER".equals(rol) ? " Lideri" : " Nöbetçisi");
                try {
                    boolean basarili = NobetciDisguiseYardimcisi.giydir(le, skin, goruntuAdi);
                    plugin.getLogger().info("Vahşi Kale disguise bekçisi: " + goruntuAdi + " disguise'siz bulundu, yeniden giydirildi (sonuç: " + basarili + ", skin: " + skin + ").");
                } catch (Throwable t) {
                    plugin.getLogger().log(Level.WARNING, "Vahşi Kale disguise bekçisi hata: " + goruntuAdi, t);
                }
            }
        }
    }

    /** Bir oyuncu bir kaleye saldırmaya başladığında (ilk girişte) çağrılır: klanına bildirim
     *  gönderilir ve 30 saniyelik bir "yardıma gel" penceresi açılır - bu pencerede &e/vahsikale yardim
     *  &7diyen çevrimiçi klan üyeleri ışınlanma kilidinden muaf tutulur; 30 sn sonra hâlâ kabul etmemiş
     *  ve zaten kuşatmada olmayan çevrimiçi üyeler 5 dakikalık TP kilidine girer (geç gelip "temiz"
     *  bir kuşatmayı beklemelerini/gerilla taktiğini caydırmak için). */
    private void kusatmaBasladi(VahsiKale kale, Player p) {
        Klan klan = plugin.klanManager().oyuncununKlani(p.getUniqueId());
        if (klan == null) return;
        plugin.klanManager().klanaGonder(klan, "vahsikale-kusatma",
                "&c{oyuncu} &7bir Vahşi Kaleye (&e{kale}&7) saldırıyor! Yardım etmek istiyorsan &e/vahsikale yardim &7yaz (30 saniyen var - yoksa 5 dakika ışınlanamazsın).",
                "oyuncu", p.getName(), "kale", kale.isim);
        Set<UUID> kabulEdenler = yardimKabulEdenler.computeIfAbsent(kale.id, k -> new HashSet<>());
        kabulEdenler.add(p.getUniqueId()); // saldırıyı başlatan zaten içeride, muaf
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Set<UUID> kabul = yardimKabulEdenler.remove(kale.id);
            if (kabul == null) kabul = java.util.Collections.emptySet();
            long simdi2 = System.currentTimeMillis();
            for (KlanUyesi uye : klan.uyeler.values()) {
                Player uyeOyuncu = Bukkit.getPlayer(uye.uuid);
                if (uyeOyuncu == null || !uyeOyuncu.isOnline()) continue;
                if (kabul.contains(uye.uuid)) continue;
                if (!kale.fethedildi && kale.kusatmadakiOyuncular.contains(uye.uuid)) continue; // zaten oradaysa muaf
                tpKilitli.put(uye.uuid, simdi2 + 5 * 60 * 1000L);
            }
        }, 600L);
    }

    /** Fethedilen Vahşi Kaleler boş durup kalmasın diye, fetihten sonra ayarlanan süre (varsayılan
     *  2 Minecraft günü) geçince otomatik silinir. Silinen kalenin yerine, mevcut otomatik üretim
     *  mantığı (otomatikKontrolEt) zaten eksik kalan aktif kale sayısını bir sonraki kontrolünde
     *  tamamlayacağı için burada ayrıca yeni kale üretmeye gerek yok. tick() her çağrıldığında
     *  (1 gerçek saniyede bir) çalışır - kaleler.values() üzerinde silme yapılacağı için önce ayrı
     *  bir listeye toplanır (ConcurrentModificationException'dan kaçınmak için). */
    private void fethedilenSuresiDolanlariSil() {
        long simdi = System.currentTimeMillis();
        long sinirMs = fethedilenSilinmeMs();
        List<VahsiKale> silinecekler = null;
        for (VahsiKale kale : kaleler.values()) {
            if (kale.fethedildi && kale.fetihZamani > 0 && simdi - kale.fetihZamani >= sinirMs) {
                if (silinecekler == null) silinecekler = new ArrayList<>();
                silinecekler.add(kale);
            }
        }
        if (silinecekler == null) return;
        for (VahsiKale kale : silinecekler) {
            plugin.getLogger().info("Vahşi Kale '" + kale.isim + "' fetihten " + (sinirMs / 1_200_000L)
                    + " Minecraft günü sonra otomatik silindi (boş kalmasın diye).");
            silKaleyi(kale);
        }
    }

    public void tick() {
        if (++disguiseBekciSayaci >= 15) {
            disguiseBekciSayaci = 0;
            disguiseBekcisi();
        }
        fethedilenSuresiDolanlariSil();
        long simdi = System.currentTimeMillis();
        for (VahsiKale kale : kaleler.values()) {
            if (kale.fethedildi || kale.merkez == null || kale.merkez.getWorld() == null) continue;
            for (Player p : kale.merkez.getWorld().getPlayers()) {
                double mesafe = p.getLocation().distance(kale.merkez);
                // Eskiden sadece merkeze düz mesafe bakılıyordu - büyük/yüksek kalelerde yapının
                // fiziksel sınırları (sinirMinX/MaxX/...) merkeze olan daire yarıçapından taşabiliyordu,
                // bu da kale İÇİNDEYKEN "kuşatmadan çekildin"/"yaklaşıyorsun, uzak dur" gibi hatalı
                // mesajlara yol açıyordu. sinirIcindeMi ile yapı sınırları da kontrol edilerek, kalenin
                // fiziksel olarak HER yeri "içeride" sayılıyor artık (merkeze olan mesafe fark etmeksizin).
                boolean icerde = mesafe <= kale.saldiriYaricap || kale.sinirIcindeMi(p.getLocation());
                if (icerde) {
                    if (kale.kusatmadakiOyuncular.add(p.getUniqueId())) {
                        kusatmaBasladi(kale, p);
                    }
                    saldirTetikle(kale, p);
                } else {
                    if (kale.kusatmadakiOyuncular.remove(p.getUniqueId())) {
                        // Kuşatmadan geri çekildi (kaçtı/çıktı) - takviye çağırıp hemen geri ışınlanmasını
                        // önlemek için 5 dakika TP kilidi.
                        tpKilitli.put(p.getUniqueId(), simdi + 5 * 60 * 1000L);
                        p.sendMessage(Mesaj.renk("&c" + kale.isim + " kuşatmasından çekildin - 5 dakika boyunca ışınlanamazsın (takviye çağırmayı önlemek için)."));
                    }
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

    // Fethedilene kadar kale sınırları içindeki bloklar korunur - sadece demir kapı (ve zaten
    // kendi mantığıyla korunan sandıklar) kırılabilir. Fethedildikten sonra koruma tamamen kalkar.
    // onSandikKir'den ÖNCE çalışması için daha yüksek öncelikte (HIGHEST), böylece bir sandık
    // bloğuna gelen kırma isteği burada iptal edilmeden onSandikKir'in kendi kilit mantığına ulaşır.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onYapiKorumasi(BlockBreakEvent event) {
        Material tip = event.getBlock().getType();
        if (tip == Material.IRON_DOOR) return; // kapı her zaman kırılabilir - alternatif giriş yolu

        String konum = ArenaManager.konumYaz(event.getBlock().getLocation());
        for (VahsiKale kale : kaleler.values()) {
            if (kale.fethedildi) continue; // fethedilmiş kalede koruma yok, tüm bloklar kırılabilir
            if (kale.sandiklar.containsKey(konum)) return; // sandık - kendi kilit mantığı onSandikKir'de
            if (kale.sinirIcindeMi(event.getBlock().getLocation())) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(Mesaj.renk("&cBu kale henüz fethedilmedi! Sadece demir kapıyı kırabilirsin."));
                return;
            }
        }
    }

    /** Fethedilene kadar kale sınırları içine blok KOYMAK da yasak - aksi halde oyuncular merdiven/
     *  blok döşeyerek duvar/çatı korumasını by-pass edip içeri girebilirdi (ör. köprü kurup çatıya
     *  çıkmak, ya da demir kapıdan geçmeden duvarın üstünden atlamak için blok yığmak). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onYapiKorumasiYerlestirme(BlockPlaceEvent event) {
        for (VahsiKale kale : kaleler.values()) {
            if (kale.fethedildi) continue;
            if (kale.sinirIcindeMi(event.getBlock().getLocation())) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(Mesaj.renk("&cBu kale henüz fethedilmedi! Buraya blok koyamazsın."));
                return;
            }
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
        Long kilitBitis = tpKilitli.get(p.getUniqueId());
        if (kilitBitis != null) {
            if (kilitBitis > System.currentTimeMillis()) {
                event.setCancelled(true);
                long kalanSn = (kilitBitis - System.currentTimeMillis()) / 1000L;
                p.sendMessage(Mesaj.renk("&cVahşi Kale ışınlanma kilidin var, &e" + kalanSn + " saniye &csonra tekrar dene."));
                return;
            }
            tpKilitli.remove(p.getUniqueId());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEtkilesim(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        // Elçi NPC (ticaret/görev/barış) bu sürümde YOK - ileride eklenecek.
        // Sandık kilidi: kale tamamen fethedilmeden (tüm nöbetçi+lider ölmeden) sandığın İÇİ
        // açılamaz/görülemez - kırmak hâlâ mümkün olabilir ama onSandikKir zaten ayrıca bunu
        // engelliyor. Burada sadece GUI açmayı (right-click) engelliyoruz; kale fethedilince
        // (tumuOldu()) normal şekilde açılabilir, içindeki gerçek loot elle alınabilir.
        var block = event.getClickedBlock();
        if (block == null || block.getState() instanceof Chest == false) return;
        String konum = ArenaManager.konumYaz(block.getLocation());
        for (VahsiKale kale : kaleler.values()) {
            if (!kale.sandiklar.containsKey(konum)) continue;
            if (Boolean.TRUE.equals(kale.sandiklar.get(konum))) return; // zaten kırılmış/açılmış, serbest
            if (!kale.tumuOldu()) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(Mesaj.renk("&cBu sandığı açamazsın - önce bu kalenin tüm nöbetçilerini ve liderini yok etmelisin."));
            }
            return;
        }
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
        migrasyonKolonEkle("kaleler", "sinir_minx INTEGER");
        migrasyonKolonEkle("kaleler", "sinir_maxx INTEGER");
        migrasyonKolonEkle("kaleler", "sinir_miny INTEGER");
        migrasyonKolonEkle("kaleler", "sinir_maxy INTEGER");
        migrasyonKolonEkle("kaleler", "sinir_minz INTEGER");
        migrasyonKolonEkle("kaleler", "sinir_maxz INTEGER");
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
                    "INSERT INTO kaleler (id, isim, seviye, merkez, giris, tespit_yaricap, saldiri_yaricap, fethedildi, fetheden_klan, fetih_zamani, prestij_verildi, klan_turu, " +
                            "sinir_minx, sinir_maxx, sinir_miny, sinir_maxy, sinir_minz, sinir_maxz) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET isim=excluded.isim, seviye=excluded.seviye, " +
                            "merkez=excluded.merkez, giris=excluded.giris, tespit_yaricap=excluded.tespit_yaricap, saldiri_yaricap=excluded.saldiri_yaricap, " +
                            "fethedildi=excluded.fethedildi, fetheden_klan=excluded.fetheden_klan, fetih_zamani=excluded.fetih_zamani, prestij_verildi=excluded.prestij_verildi, " +
                            "klan_turu=excluded.klan_turu, sinir_minx=excluded.sinir_minx, sinir_maxx=excluded.sinir_maxx, sinir_miny=excluded.sinir_miny, " +
                            "sinir_maxy=excluded.sinir_maxy, sinir_minz=excluded.sinir_minz, sinir_maxz=excluded.sinir_maxz")) {
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
                ps.setInt(13, kale.sinirMinX);
                ps.setInt(14, kale.sinirMaxX);
                ps.setInt(15, kale.sinirMinY);
                ps.setInt(16, kale.sinirMaxY);
                ps.setInt(17, kale.sinirMinZ);
                ps.setInt(18, kale.sinirMaxZ);
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
                kale.sinirMinX = rs.getInt("sinir_minx");
                kale.sinirMaxX = rs.getInt("sinir_maxx");
                kale.sinirMinY = rs.getInt("sinir_miny");
                kale.sinirMaxY = rs.getInt("sinir_maxy");
                kale.sinirMinZ = rs.getInt("sinir_minz");
                kale.sinirMaxZ = rs.getInt("sinir_maxz");
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
        sinirlariGenislet();
        if (!kaleler.isEmpty()) {
            plugin.getLogger().info(kaleler.size() + " Vahşi Kale yüklendi.");
            Bukkit.getScheduler().runTaskLater(plugin, this::disguiseleriTazele, 100L);
            Bukkit.getScheduler().runTaskLater(plugin, this::sandikLootuGeriDoldur, 120L);
        }
    }

    /** Tek seferlik geriye dönük düzeltme: bu oturumdaki LootTablosu genişletmesinden ÖNCE üretilmiş
     *  sandıklar dünyada fiziksel olarak hâlâ TAMAMEN BOŞ duruyor - eski sürümde sandığa hiçbir item
     *  konulmuyordu, sadece kırılınca kasaya nakit aktarılıyordu (bkz. onSandikKir'deki not). Oyuncu
     *  "sandığı kırdım ama içinden sadece para çıktı, loot düşmedi" diye şikayet etti - sebep bu.
     *  Henüz AÇILMAMIŞ (acildi=false) her sandık için: fiziksel blok hâlâ bir sandıksa VE envanteri
     *  tamamen boşsa (idempotent kontrol - yeni üretilen, zaten dolu sandıklara dokunmaz) LootTablosu
     *  ile dolduruluyor. "acildi" durumu değişmediği için DB'ye ayrıca yazmaya gerek yok. */
    private void sandikLootuGeriDoldur() {
        Random rng = new Random();
        int dolduruldu = 0;
        for (VahsiKale kale : kaleler.values()) {
            for (Map.Entry<String, Boolean> giris : kale.sandiklar.entrySet()) {
                if (Boolean.TRUE.equals(giris.getValue())) continue; // zaten kırılmış/açılmış
                Location konum = ArenaManager.konumOku(giris.getKey());
                if (konum == null || konum.getWorld() == null) continue;
                // DİKKAT: getState() (snapshot=true) DEĞİL, getState(false) (CANLI/live state)
                // kullanılıyor. Snapshot'un getInventory()'si her çağrıldığında envanterin KOPYASINI
                // döndürebiliyor - doldur() o kopyayı doldursa da update(true,false) orijinal (hâlâ
                // boş) snapshot verisini dünyaya geri yazıyor, sonuç: sandık dünyada hep boş kalıyor.
                // Live state ile setItem DOĞRUDAN gerçek tile entity'ye yazılıyor, update() gerekmiyor
                // ama yine de çağrılıyor (zararsız, bazı sürümlerde ekstra güvence için).
                BlockState durum = konum.getBlock().getState(false);
                if (!(durum instanceof Chest sandik)) continue; // yapı bozulmuş/blok artık sandık değil
                boolean zatenDolu = false;
                for (ItemStack esya : sandik.getInventory().getContents()) {
                    if (esya != null) { zatenDolu = true; break; }
                }
                if (zatenDolu) continue; // zaten dolu (yeni üretim) - dokunma
                LootTablosu.doldur(sandik.getInventory(), kale.seviye, rng);
                sandik.update(true, false);
                int yeniItemSayisi = 0;
                for (ItemStack esya : sandik.getInventory().getContents()) if (esya != null) yeniItemSayisi++;
                if (yeniItemSayisi > 0) dolduruldu++;
                else plugin.getLogger().warning("[VahsiKale] Geriye dönük doldurma BAŞARISIZ (hâlâ boş): " + giris.getKey());
            }
        }
        if (dolduruldu > 0) {
            plugin.getLogger().info(dolduruldu + " eski (boş) Vahşi Kale sandığı geriye dönük loot ile dolduruldu.");
        }
    }

    /** Tek seferlik geriye dönük düzeltme: KaleInsaatYardimcisi#insaEt artık yapı etrafındaki
     *  düzleştirilmiş platformu da koruma sınırına dahil ediyor, ama bu kodu derlemeden ÖNCE
     *  üretilmiş kaleler DB'de hâlâ eski (daha dar) sınırlarla duruyor - o kaleler için platform
     *  korumasız kalırdı. DİKKAT: kale.merkez burada yapının GEOMETRİK merkezi DEĞİL - girisIci
     *  (kapının içi, bkz. otomatikUret: "kale.merkez = yapi.girisIci.clone()") olduğu için X/Z'yi
     *  merkez+seviyeden yeniden hesaplamak Z ekseninde kaymaya yol açar (kapı zMax kenarında).
     *  Bunun yerine var olan sinirMinX/MaxX/MinZ/MaxZ (eski kodun ürettiği TAM xMin/xMax/zMin/zMax
     *  değerleri) doğrudan kenarPay kadar genişletiliyor - konum bağımsız, güvenli. Genişlik
     *  karşılaştırmasıyla idempotent: eski (dar) genişlik hâlâ duruyorsa genişlet, zaten genişse
     *  (ya da yeni/çok-katlı formatla üretilmiş bir kaleyse - o format her zaman daha geniş çıkar)
     *  dokunma. Fethedilmiş kalelerde koruma zaten uygulanmadığı için onlara dokunmuyoruz. */
    private void sinirlariGenislet() {
        final int kenarPay = 3;
        for (VahsiKale kale : kaleler.values()) {
            if (kale.fethedildi) continue;
            int eskiGenislikFormulu = 20 + (kale.seviye - 1) * 4;
            int suankiGenislik = kale.sinirMaxX - kale.sinirMinX + 1;
            if (suankiGenislik >= eskiGenislikFormulu + 2 * kenarPay) continue; // zaten genişletilmiş/yeni format
            kale.sinirMinX -= kenarPay; kale.sinirMaxX += kenarPay;
            kale.sinirMinZ -= kenarPay; kale.sinirMaxZ += kenarPay;
            kale.sinirMaxY += 4; // eski +6 payından yeni +10 paya çıkar (adım 2 temizlik yüksekliğiyle tutarlı)
            kaydet(kale);
        }
    }

    public void kapat() {
        if (baglanti != null) {
            try { baglanti.close(); } catch (SQLException ignored) { }
        }
    }
}
