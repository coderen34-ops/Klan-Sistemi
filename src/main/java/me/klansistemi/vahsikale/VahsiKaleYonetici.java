package me.klansistemi.vahsikale;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
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
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Vindicator;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.data.BlockData;
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
    private final NamespacedKey elciKey; // elçi NPC'sini nöbetçi/liderden ayırt etmek için (byte=1)

    // oyuncu -> bekleyen "elçi görevi" (tek seferlik, aynı anda sadece bir görev tutulabilir)
    private final Map<UUID, ElciGorevi> elciGorevleri = new LinkedHashMap<>();

    private record ElciGorevi(UUID kaleId, Material esya, int miktar, double odul) { }

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
        this.elciKey = new NamespacedKey(plugin, "vahsikale-elci");
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
            case "yedekgeri" -> yedekGeriKomut(s, args);
            case "liste" -> liste(s);
            case "bilgi" -> bilgi(s);
            case "giris" -> girisAyarla(s);
            case "yardim" -> yardimKabulEt(s);
            case "git" -> git(s, args);
            default -> kullanim(s);
        }
    }

    private void kullanim(CommandSender s) {
        s.sendMessage(Mesaj.renk("&cKullanım: &e/vahsikale <uret [seviye 1-5]|sil <isim>|hepsinisil|yedekgeri <uuid>|liste|bilgi|giris|yardim|git <isim>>"));
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
     *  bellekten ve SQLite'tan (kaleler/nobetciler/sandiklar) temizler, VE (silKaleyi() ile
     *  aynı mantıkla) her kalenin arazi yedeğini varsa eski haline döndürür. Önceden bu komut
     *  arazi geri yüklemesini atlıyordu - bu da gerçek, fethedilmiş bir kalenin "hepsinisil" ile
     *  silinmesi durumunda arazisinin asla eski haline dönmemesine ve kalenin hem sistemden hem
     *  de 2 Minecraft günlük otomatik silme/undo mekanizmasının görüş alanından tamamen
     *  kaybolup fiziksel olarak dünyada "yetim" kalmasına yol açıyordu (bkz. kullanıcı raporu). */
    private void hepsiniSil(CommandSender s) {
        if (kaleler.isEmpty()) { s.sendMessage(Mesaj.renk("&7Silinecek Vahşi Kale yok.")); return; }
        int toplam = kaleler.size();
        List<VahsiKale> kopya = new ArrayList<>(kaleler.values());
        for (VahsiKale kale : kopya) {
            for (UUID npcId : kale.nobetciRolleri.keySet()) {
                org.bukkit.entity.Entity e = Bukkit.getEntity(npcId);
                if (e != null) e.remove();
            }
            elciSil(kale);
            araziYedekGeriYukle(kale.id);
            kaleler.remove(kale.id);
            silDb(kale.id);
        }
        s.sendMessage(Mesaj.renk("&a" + toplam + " Vahşi Kale (ve nöbetçileri/sandık kayıtları) silindi, arazileri eski haline döndürüldü."));
    }

    /** Admin aracı: eskiden (fix'ten önce) "hepsinisil" ile silinmiş ama arazisi geri
     *  yüklenmemiş "yetim" kaleler için - kale artık kaleler map'inde/DB'de olmasa bile,
     *  sadece elindeki UUID ile (plugins/KlanSistemi/kale-yedekleri/<uuid>.txt.gz dosyasından)
     *  arazisini manuel olarak eski haline döndürür. */
    private void yedekGeriKomut(CommandSender s, String[] args) {
        if (args.length < 2) { s.sendMessage(Mesaj.renk("&cKullanım: /vahsikale yedekgeri <kale-uuid>")); return; }
        UUID id;
        try {
            id = UUID.fromString(args[1]);
        } catch (IllegalArgumentException e) {
            s.sendMessage(Mesaj.renk("&cGeçersiz UUID: " + args[1]));
            return;
        }
        File dosya = araziYedekDosyasi(id);
        if (!dosya.exists()) { s.sendMessage(Mesaj.renk("&cBu UUID için yedek dosyası bulunamadı: " + dosya.getPath())); return; }
        araziYedekGeriYukle(id);
        s.sendMessage(Mesaj.renk("&aArazi yedeği uygulandı - detaylar için konsol/log'a bak."));
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
    public VahsiKale otomatikUret(int seviye) { return otomatikUret(seviye, null); }

    /**
     * merkezNoktasi verilirse (oyuncu yakınlığı garantisi, bkz. oyuncuYakinligiKontrolEt) konum o noktanın
     * yakın çevresinde aranır; null ise eski davranış (dünya spawn'ından min/max-spawn-uzakligi aralığı).
     */
    public VahsiKale otomatikUret(int seviye, Location merkezNoktasi) {
        int ustSinir = plugin.getConfig().getInt("vahsikale.max-aktif-kale-sayisi", 30);
        if (aktifKaleSayisi() >= ustSinir) return null; // uretim ust siniri (config, varsayilan 30)
        World dunya = Bukkit.getWorlds().get(0);
        Random rng = new Random();
        Location konum = rastgeleKonumBul(dunya, rng, merkezNoktasi);
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
        elciDogur(kale);
        araziYedekKaydet(kale.id, dunya.getName(), yapi);
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
     * Daha sık çağrılır (bkz. KlanSistemi onEnable, ~60 sn'de bir). otomatikKontrolEt() SADECE toplam
     * aktif kale sayısını asgarinin üstünde tutar - dünya spawn'ından rastgele dağıtıldığı için bir
     * oyuncu spawn'dan çok uzağa (binlerce blok) keşfe çıkarsa o bölgede HİÇ kale olmayabilir. Bu metot
     * her online oyuncunun konumuna en yakın kaleye olan uzaklığı kontrol eder; eşiği aşıyorsa (hiç kale
     * yok ya da çok uzaktaysa) oyuncunun biraz ötesine (yakinlik-spawn-uzakligi) yeni bir kale üretir -
     * böylece keşif her zaman erişilebilir içerik bulur. Sunucuyu yormamak/spam'lememek için bir
     * çalıştırmada en fazla 1 kale üretilir.
     */
    public void oyuncuYakinligiKontrolEt() {
        World dunya = Bukkit.getWorlds().get(0);
        double esik = plugin.getConfig().getDouble("vahsikale.yakinlik-esigi", 2800);
        double spawnUzakligi = plugin.getConfig().getDouble("vahsikale.yakinlik-spawn-uzakligi", 1000);
        Random rng = new Random();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Location loc = p.getLocation();
            if (!dunya.equals(loc.getWorld())) continue;
            double enYakin = Double.MAX_VALUE;
            for (VahsiKale k : kaleler.values()) {
                if (k.merkez == null || !dunya.equals(k.merkez.getWorld())) continue;
                double dx = k.merkez.getX() - loc.getX();
                double dz = k.merkez.getZ() - loc.getZ();
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d < enYakin) enYakin = d;
            }
            if (enYakin <= esik) continue;
            double aci = rng.nextDouble() * Math.PI * 2;
            Location merkezNoktasi = new Location(dunya, loc.getX() + Math.cos(aci) * spawnUzakligi, 0, loc.getZ() + Math.sin(aci) * spawnUzakligi);
            VahsiKale yeni = otomatikUret(1 + rng.nextInt(5), merkezNoktasi);
            if (yeni != null) {
                plugin.getLogger().info("[VahsiKale] Yakınlık kontrolü: " + p.getName() + " en yakın kaleden ~"
                        + Math.round(enYakin) + " blok uzaktaydı -> " + yeni.isim + " o bölgeye üretildi.");
            }
            return; // bir çalıştırmada en fazla 1 kale
        }
    }

    /**
     * Dünya spawn'ından (ya da merkezNoktasi verilmişse O noktadan) ve diğer Vahşi Kalelerden uzak,
     * kuru/düzgün zeminli rastgele bir konum arar. GriefPrevention yüklüyse (opsiyonel, yansıma ile -
     * eksik/uyumsuz olsa sessizce atlanır) arazi talebiyle çakışmayı da kontrol eder.
     *
     * merkezNoktasi != null ise (oyuncu yakınlığı garantisi): aranan uzaklık min/max-spawn-uzakligi
     * DEĞİL, o noktanın ~0-400 blok civarıdır - amaç oyuncunun "etrafına" bir kale koymak, dünya
     * spawn'ından olan mesafeyi değil.
     */
    private Location rastgeleKonumBul(World dunya, Random rng, Location merkezNoktasi) {
        double minUzaklik = plugin.getConfig().getDouble("vahsikale.min-spawn-uzakligi", 1200);
        double maxUzaklik = plugin.getConfig().getDouble("vahsikale.max-spawn-uzakligi", 6000);
        double kalelerArasiMin = plugin.getConfig().getDouble("vahsikale.kaleler-arasi-min-uzaklik", 300);
        boolean ozelMerkez = merkezNoktasi != null;
        Location spawn = ozelMerkez ? merkezNoktasi : dunya.getSpawnLocation();

        for (int deneme = 0; deneme < 60; deneme++) {
            double aci = rng.nextDouble() * Math.PI * 2;
            double uzaklik = ozelMerkez ? rng.nextDouble() * 400 : (minUzaklik + rng.nextDouble() * (maxUzaklik - minUzaklik));
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
            // Köy bölgesi ve tüm claim'ler (yapı yarıçapı + tampon) yasak: config vahsikale.yasak-alan-yaricap
            if (YasakAlan.engelliMi(dunya, x, z, plugin.getConfig().getInt("vahsikale.yasak-alan-yaricap", 80))) continue;
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
        elciSil(kale);
        araziYedekGeriYukle(kale.id);
        kaleler.remove(kale.id);
        silDb(kale.id);
    }

    private File araziYedekDosyasi(UUID kaleId) {
        return new File(plugin.getDataFolder(), "kale-yedekleri" + File.separator + kaleId + ".txt.gz");
    }

    /** Kale inşa edildikten SONRA çağrılır: inşaattan ÖNCEki orijinal arazinin (KaleInsaatYardimcisi'nin
     *  zaten topladığı) yedeğini diske (gzip, tek satır başlık + blok verisi satırları) yazar. Kale
     *  silinirken (bkz. araziYedekGeriYukle) bu dosya okunup arazi eski haline döndürülür, sonra silinir. */
    private void araziYedekKaydet(UUID kaleId, String dunyaAdi, KaleInsaatYardimcisi.Sonuc yapi) {
        File dosya = araziYedekDosyasi(kaleId);
        dosya.getParentFile().mkdirs();
        try (var fos = new java.io.FileOutputStream(dosya);
             var gzip = new java.util.zip.GZIPOutputStream(fos);
             var yazici = new java.io.BufferedWriter(new java.io.OutputStreamWriter(gzip, java.nio.charset.StandardCharsets.UTF_8))) {
            yazici.write(dunyaAdi + ";" + yapi.yedekMinX + ";" + yapi.yedekMaxX + ";" + yapi.yedekMinY + ";"
                    + yapi.yedekMaxY + ";" + yapi.yedekMinZ + ";" + yapi.yedekMaxZ);
            yazici.newLine();
            for (String blok : yapi.ozgunBloklar) {
                yazici.write(blok);
                yazici.newLine();
            }
        } catch (java.io.IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Vahşi Kale arazi yedeği yazılamadı (" + kaleId + ") - bu kale silinirse arazi eski haline DÖNDÜRÜLEMEYECEK!", e);
        }
    }

    /** silKaleyi() tarafından çağrılır: yedek dosyası varsa, inşaattan önceki orijinal bloklar aynı
     *  sırayla (y dış, x orta, z iç - bkz. KaleInsaatYardimcisi) okunup geri yazılır (arazi "undo"su),
     *  sonra yedek dosyası silinir. Dosya yoksa (çok eski kale / zaten geri yüklenmiş) sessizce çıkar. */
    private void araziYedekGeriYukle(UUID kaleId) {
        File dosya = araziYedekDosyasi(kaleId);
        if (!dosya.exists()) return;
        try (var fis = new java.io.FileInputStream(dosya);
             var gzip = new java.util.zip.GZIPInputStream(fis);
             var okuyucu = new java.io.BufferedReader(new java.io.InputStreamReader(gzip, java.nio.charset.StandardCharsets.UTF_8))) {
            String baslik = okuyucu.readLine();
            if (baslik == null) return;
            String[] p = baslik.split(";");
            String dunyaAdi = p[0];
            int minX = Integer.parseInt(p[1]), maxX = Integer.parseInt(p[2]);
            int minY = Integer.parseInt(p[3]), maxY = Integer.parseInt(p[4]);
            int minZ = Integer.parseInt(p[5]), maxZ = Integer.parseInt(p[6]);
            World dunya = Bukkit.getWorld(dunyaAdi);
            if (dunya == null) {
                plugin.getLogger().warning("Vahşi Kale arazi geri yükleme: dünya bulunamadı (" + dunyaAdi + "), atlanıyor.");
                return;
            }
            int geriYuklendi = 0;
            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        String satir = okuyucu.readLine();
                        if (satir == null) break;
                        try {
                            BlockData veri = Bukkit.createBlockData(satir);
                            dunya.getBlockAt(x, y, z).setBlockData(veri, false);
                            geriYuklendi++;
                        } catch (IllegalArgumentException ignore) {
                            // Tanınmayan/bozuk blok verisi - o tek bloğu atla, geri kalanına devam et
                        }
                    }
                }
            }
            plugin.getLogger().info("[VahsiKale] Kale (" + kaleId + ") silinirken arazi eski haline döndürüldü ("
                    + geriYuklendi + " blok).");
        } catch (Exception e) {
            // DİKKAT: sadece IOException değil, her türlü hata (bozuk/yarım yazılmış yedek dosyası
            // header'ından NumberFormatException/ArrayIndexOutOfBoundsException gibi) burada
            // yakalanıyor - yoksa tick() içinden fırlayan yakalanmamış bir exception, Bukkit'in
            // tüm periyodik görevi (fethedilenSuresiDolanlariSil dahil) sessizce ve KALICI olarak
            // iptal etmesine yol açabilir (restart'a kadar hiçbir kale bir daha otomatik silinmez).
            plugin.getLogger().log(Level.SEVERE, "Vahşi Kale arazi yedeği okunamadı/işlenemedi (" + kaleId + ") - arazi eski haline döndürülemedi.", e);
        } finally {
            dosya.delete();
        }
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

    // ------------------------------------------------------------------ ELÇİ NPC (savaşmayan: ticaret/görev/barış)
    /** Nöbetçi/liderlerden ayrı, savaşmayan özel bir Villager - giriş noktasına yakın doğurulur.
     *  PazarManager'daki stand NPC'leriyle AYNI kalıp: AI kapalı, vurulamaz, NITWIT (vanilla ticaret
     *  ekranı açılmasın), PDC ile etiketlenir. kale.elciId persist edilir (bkz. kaydet()/yukle()),
     *  entity kaybolursa elciBekcisi() (tick() içinde, 15 sn'de bir) fark edip yeniden doğurur. */
    private boolean elciAktifMi() { return plugin.getConfig().getBoolean("vahsikale.elci-aktif", false); }

    private void elciDogur(VahsiKale kale) {
        if (!elciAktifMi()) return; // kullanıcı isteğiyle şimdilik devre dışı - bkz. elciBekcisi() (var olanları da temizler)
        Location konum = (kale.giris != null && kale.giris.getWorld() != null) ? kale.giris : kale.merkez;
        if (konum == null || konum.getWorld() == null) return;
        Villager v = (Villager) konum.getWorld().spawnEntity(konum, EntityType.VILLAGER);
        v.setAI(false);
        v.setInvulnerable(true);
        v.setSilent(true);
        v.setPersistent(true);
        v.setRemoveWhenFarAway(false);
        v.setCollidable(false);
        v.setProfession(Villager.Profession.NITWIT);
        v.getPersistentDataContainer().set(elciKey, PersistentDataType.BYTE, (byte) 1);
        v.getPersistentDataContainer().set(kaleIdKey, PersistentDataType.STRING, kale.id.toString());
        v.setCustomName(ChatColor.GOLD + kale.isim + " Elçisi");
        v.setCustomNameVisible(true);
        kale.elciId = v.getUniqueId();
        kaydet(kale);
    }

    /** disguiseBekciSayaci ile aynı 15 sn'lik döngüde (tick() içinden) çağrılır: elçisi kaybolmuş
     *  (chunk unload sonrası despawn, manuel /kill, vs.) henüz fethedilmemiş kaleler için yeniden doğurur. */
    private void elciBekcisi() {
        if (!elciAktifMi()) {
            // Devre dışı bırakılınca daha önce doğmuş elçiler kalıcı olarak ortalıkta kalmasın diye temizlenir.
            for (VahsiKale kale : kaleler.values()) {
                if (kale.elciId == null) continue;
                elciSil(kale);
                kaydet(kale);
            }
            // Güvenlik ağı (kullanıcı raporu: "elçi temizlenmemiş" - bkz. elciSil() javadoc'undaki eski
            // hata): kale.elciId artık hiçbir kaleden takip edilmeyen "yetim" elçiler olabilir. Şu an
            // YÜKLÜ olan tüm dünyalardaki etiketli Villager'ları tarayıp bunları da temizler - bir
            // oyuncu o bölgeye her yaklaştığında (chunk yüklenince) bu tarama onu bulup kaldıracaktır.
            for (World dunya : Bukkit.getWorlds()) {
                for (org.bukkit.entity.Entity e : dunya.getEntitiesByClass(Villager.class)) {
                    if (e.getPersistentDataContainer().has(elciKey, PersistentDataType.BYTE)) {
                        e.remove();
                    }
                }
            }
            return;
        }
        for (VahsiKale kale : kaleler.values()) {
            if (kale.fethedildi) continue;
            if (kale.elciId != null && Bukkit.getEntity(kale.elciId) != null) continue;
            elciDogur(kale);
        }
    }

    /**
     * DİKKAT (bulunan gerçek hata - bkz. kullanıcı raporu "elçi temizlenmemiş"): kale.elciId'yi
     * entity GERÇEKTEN silinmeden null'a çekmek, chunk yüklü değilken Bukkit.getEntity() null
     * dönerse elçiyi sonsuza dek "yetim" (hiçbir kalenin elciId'si artık ona işaret etmiyor, ama
     * dünyada hâlâ duruyor) bırakırdı - bir daha asla bekçi tarafından bulunup silinemezdi. Artık
     * SADECE entity gerçekten bulunup silinirse elciId null'lanır; bulunamazsa (chunk yüklü değil)
     * elciId olduğu gibi kalır, bir sonraki bekçi turunda (chunk yüklenince) tekrar denenir.
     */
    private void elciSil(VahsiKale kale) {
        if (kale.elciId == null) return;
        org.bukkit.entity.Entity e = Bukkit.getEntity(kale.elciId);
        if (e != null) {
            e.remove();
            kale.elciId = null;
        }
    }

    private double ticaretFiyati(int seviye, double temel) { return Math.round(temel * (1 + (seviye - 1) * 0.35)); }

    private record TicaretKalemi(Material esya, int miktar, double temelFiyat, int minSeviye, java.util.function.Function<Integer, ItemStack> yapici) { }

    private static ItemStack basitEsya(Material m, int miktar) { return new ItemStack(m, miktar); }

    private List<TicaretKalemi> elciKatalogu(int seviye) {
        List<TicaretKalemi> hepsi = new ArrayList<>();
        hepsi.add(new TicaretKalemi(Material.GOLDEN_APPLE, 2, 150, 1, s -> basitEsya(Material.GOLDEN_APPLE, 2)));
        hepsi.add(new TicaretKalemi(Material.DIAMOND, 2, 220, 1, s -> basitEsya(Material.DIAMOND, 2)));
        hepsi.add(new TicaretKalemi(Material.ENCHANTED_BOOK, 1, 500, 2, s -> {
            ItemStack kitap = new ItemStack(Material.ENCHANTED_BOOK, 1);
            if (kitap.getItemMeta() instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta meta) {
                meta.addStoredEnchant(Enchantment.MENDING, 1, true);
                kitap.setItemMeta(meta);
            }
            return kitap;
        }));
        hepsi.add(new TicaretKalemi(Material.NAME_TAG, 1, 300, 1, s -> basitEsya(Material.NAME_TAG, 1)));
        hepsi.add(new TicaretKalemi(Material.TOTEM_OF_UNDYING, 1, 2500, 4, s -> basitEsya(Material.TOTEM_OF_UNDYING, 1)));
        hepsi.removeIf(k -> k.minSeviye() > seviye);
        return hepsi;
    }

    private void acElciMenusu(Player p, VahsiKale kale) {
        Inventory gui = Bukkit.createInventory(null, 9, ChatColor.GOLD + "Elçi - " + kale.isim);
        gui.setItem(2, menuEsyasi(Material.EMERALD, ChatColor.GREEN + "Ticaret",
                Arrays.asList(ChatColor.GRAY + "Nadir/özel ürünler satın al.", ChatColor.DARK_GRAY + "Klan kasasından ödenir.")));
        gui.setItem(4, menuEsyasi(Material.WRITABLE_BOOK, ChatColor.AQUA + "Görev Al",
                Arrays.asList(ChatColor.GRAY + "Bize eşya getir, ödül kazan.", ChatColor.DARK_GRAY + "Tek seferlik, küçük bir iş.")));
        gui.setItem(6, menuEsyasi(Material.WHITE_BANNER, ChatColor.WHITE + "Barış Teklif Et",
                Arrays.asList(ChatColor.GRAY + "Ücret öde, bir süre nöbetçiler", ChatColor.GRAY + "klanına saldırmasın.")));
        p.openInventory(gui);
        elciMenuBekleyenler.put(p.getUniqueId(), kale.id);
    }

    private ItemStack menuEsyasi(Material m, String ad, List<String> lore) {
        ItemStack item = new ItemStack(m);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) { meta.setDisplayName(ad); meta.setLore(lore); item.setItemMeta(meta); }
        return item;
    }

    // oyuncu -> şu an açık olan elçi menüsünün ait olduğu kale (hangi alt-menüde olduğunu anlamak için)
    private final Map<UUID, UUID> elciMenuBekleyenler = new LinkedHashMap<>();

    private void acTicaretMenusu(Player p, VahsiKale kale) {
        List<TicaretKalemi> katalog = elciKatalogu(kale.seviye);
        Inventory gui = Bukkit.createInventory(null, 9, ChatColor.GREEN + "Ticaret - " + kale.isim);
        int slot = 0;
        for (TicaretKalemi k : katalog) {
            if (slot > 8) break;
            double fiyat = ticaretFiyati(kale.seviye, k.temelFiyat());
            ItemStack gosterim = k.yapici().apply(kale.seviye);
            ItemMeta meta = gosterim.getItemMeta();
            List<String> lore = new ArrayList<>();
            if (meta != null && meta.hasLore()) lore.addAll(meta.getLore());
            lore.add(ChatColor.YELLOW + "Fiyat: " + ChatColor.GREEN + "$" + (long) fiyat + ChatColor.GRAY + " (klan kasasından)");
            lore.add(ChatColor.DARK_GRAY + "Satın almak için tıkla.");
            if (meta != null) { meta.setLore(lore); gosterim.setItemMeta(meta); }
            gui.setItem(slot++, gosterim);
        }
        p.openInventory(gui);
    }

    private void elciTicaretSatinAl(Player p, VahsiKale kale, int slot) {
        List<TicaretKalemi> katalog = elciKatalogu(kale.seviye);
        if (slot < 0 || slot >= katalog.size()) return;
        Klan klan = plugin.klanManager().oyuncununKlani(p.getUniqueId());
        if (klan == null) { p.sendMessage(Mesaj.renk("&cBir klanda değilsin, bu yüzden ticaret yapamazsın.")); return; }
        TicaretKalemi k = katalog.get(slot);
        double fiyat = ticaretFiyati(kale.seviye, k.temelFiyat());
        if (klan.kasa < fiyat) { p.sendMessage(Mesaj.renk("&cKlan kasasında yeterli para yok! Gerekli: &e$" + (long) fiyat)); return; }
        klan.kasa = Para.kurus(klan.kasa - fiyat);
        plugin.veri().kaydet();
        ItemStack esya = k.yapici().apply(kale.seviye);
        p.getInventory().addItem(esya);
        plugin.log().yaz(klan, p.getName(), "VAHSI_KALE_TICARET", kale.isim + " elçisinden " + esya.getType() + " -> " + Para.yaz(fiyat));
        p.sendMessage(Mesaj.renk("&aSatın alındı! &7Ödenen: &e" + Para.yaz(fiyat) + " &7(klan kasasından)"));
        p.closeInventory();
    }

    private void elciGorevVer(Player p, VahsiKale kale) {
        if (elciGorevleri.containsKey(p.getUniqueId())) {
            ElciGorevi mevcut = elciGorevleri.get(p.getUniqueId());
            p.sendMessage(Mesaj.renk("&eZaten bekleyen bir görevin var: &f" + mevcut.miktar() + " " + mevcut.esya()
                    + " &7getir, ödül: &a" + Para.yaz(mevcut.odul()) + "&7. Getirince elçiye sağ tık + shift yap."));
            p.closeInventory();
            return;
        }
        Klan klan = plugin.klanManager().oyuncununKlani(p.getUniqueId());
        if (klan == null) { p.sendMessage(Mesaj.renk("&cBir klanda değilsin, bu yüzden görev alamazsın.")); p.closeInventory(); return; }
        Material[] havuz = {Material.IRON_INGOT, Material.GOLD_INGOT, Material.EMERALD, Material.COAL, Material.STRING, Material.LEATHER};
        Random rng = new Random();
        Material esya = havuz[rng.nextInt(havuz.length)];
        int miktar = 5 + kale.seviye * 3 + rng.nextInt(6);
        double odul = ticaretFiyati(kale.seviye, miktar * (8 + rng.nextInt(6)));
        elciGorevleri.put(p.getUniqueId(), new ElciGorevi(kale.id, esya, miktar, odul));
        p.sendMessage(Mesaj.renk("&a" + kale.isim + " Elçisi: &f" + miktar + " " + esya + " &7getir, karşılığında &a"
                + Para.yaz(odul) + " &7(klan kasana) veririm. Getirince elçiye &eshift + sağ tık&7 yap."));
        p.closeInventory();
    }

    private void elciGorevTeslimEt(Player p, VahsiKale kale) {
        ElciGorevi gorev = elciGorevleri.get(p.getUniqueId());
        if (gorev == null || !gorev.kaleId().equals(kale.id)) {
            p.sendMessage(Mesaj.renk("&7Bu elçiden bekleyen bir görevin yok."));
            return;
        }
        int sahipOlunan = 0;
        for (ItemStack it : p.getInventory().getContents()) if (it != null && it.getType() == gorev.esya()) sahipOlunan += it.getAmount();
        if (sahipOlunan < gorev.miktar()) {
            p.sendMessage(Mesaj.renk("&cYeterli eşya yok! Gerekli: &e" + gorev.miktar() + " " + gorev.esya() + " &7(elinde: " + sahipOlunan + ")"));
            return;
        }
        Klan klan = plugin.klanManager().oyuncununKlani(p.getUniqueId());
        if (klan == null) { p.sendMessage(Mesaj.renk("&cBir klanda değilsin, ödül verilemiyor.")); return; }
        removeItemFromInventory(p.getInventory(), gorev.esya(), gorev.miktar());
        klan.kasa = Para.kurus(klan.kasa + gorev.odul());
        plugin.veri().kaydet();
        elciGorevleri.remove(p.getUniqueId());
        plugin.log().yaz(klan, p.getName(), "VAHSI_KALE_GOREV", kale.isim + " elçi görevi -> " + Para.yaz(gorev.odul()));
        p.sendMessage(Mesaj.renk("&aGörev tamamlandı! &7Klan kasana: &a" + Para.yaz(gorev.odul())));
    }

    private void removeItemFromInventory(Inventory env, Material mat, int miktar) {
        int kalan = miktar;
        for (ItemStack it : env.getContents()) {
            if (it == null || it.getType() != mat) continue;
            int cek = Math.min(kalan, it.getAmount());
            it.setAmount(it.getAmount() - cek);
            kalan -= cek;
            if (kalan <= 0) break;
        }
    }

    private int barisSuresiDakika() { return plugin.getConfig().getInt("vahsikale.baris-suresi-dakika", 5); }
    private double barisUcreti(int seviye) { return ticaretFiyati(seviye, 400); }

    private void elciBarisTeklifEt(Player p, VahsiKale kale) {
        Klan klan = plugin.klanManager().oyuncununKlani(p.getUniqueId());
        if (klan == null) { p.sendMessage(Mesaj.renk("&cBir klanda değilsin, barış teklif edemezsin.")); p.closeInventory(); return; }
        if (kale.barisVarMi(klan.id)) {
            p.sendMessage(Mesaj.renk("&eKlanın zaten bu kaleyle barış içinde."));
            p.closeInventory();
            return;
        }
        double ucret = barisUcreti(kale.seviye);
        if (klan.kasa < ucret) { p.sendMessage(Mesaj.renk("&cKlan kasasında yeterli para yok! Gerekli: &e$" + (long) ucret)); p.closeInventory(); return; }
        klan.kasa = Para.kurus(klan.kasa - ucret);
        plugin.veri().kaydet();
        int dakika = barisSuresiDakika();
        kale.barisAnlasmalari.put(klan.id, System.currentTimeMillis() + dakika * 60_000L);
        plugin.log().yaz(klan, p.getName(), "VAHSI_KALE_BARIS", kale.isim + " ile " + dakika + " dk barış -> " + Para.yaz(ucret));
        p.sendMessage(Mesaj.renk("&a" + kale.isim + " &7ile klanın için &e" + dakika + " dakika&7 barış sağlandı (nöbetçiler saldırmayacak)."));
        p.closeInventory();
    }

    @EventHandler
    public void onElciEtkilesim(PlayerInteractEntityEvent event) {
        if (!event.getRightClicked().getPersistentDataContainer().has(elciKey, PersistentDataType.BYTE)) return;
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return;
        String kaleIdStr = event.getRightClicked().getPersistentDataContainer().get(kaleIdKey, PersistentDataType.STRING);
        if (kaleIdStr == null) return;
        VahsiKale kale = kaleler.get(UUID.fromString(kaleIdStr));
        if (kale == null) return;
        Player p = event.getPlayer();
        if (kale.fethedildi) {
            p.sendMessage(Mesaj.renk("&7Bu kale fethedildi, elçi artık burada değil."));
            return;
        }
        if (p.isSneaking()) {
            elciGorevTeslimEt(p, kale);
            return;
        }
        acElciMenusu(p, kale);
    }

    @EventHandler(ignoreCancelled = true)
    public void onElciMenuTikla(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        String title = event.getView().getTitle();
        if (title.startsWith(ChatColor.GOLD + "Elçi - ")) {
            event.setCancelled(true);
            UUID kaleId = elciMenuBekleyenler.get(p.getUniqueId());
            if (kaleId == null) return;
            VahsiKale kale = kaleler.get(kaleId);
            if (kale == null) return;
            int slot = event.getRawSlot();
            if (slot == 2) { p.closeInventory(); acTicaretMenusu(p, kale); }
            else if (slot == 4) elciGorevVer(p, kale);
            else if (slot == 6) elciBarisTeklifEt(p, kale);
            return;
        }
        if (title.startsWith(ChatColor.GREEN + "Ticaret - ")) {
            event.setCancelled(true);
            UUID kaleId = elciMenuBekleyenler.get(p.getUniqueId());
            if (kaleId == null) return;
            VahsiKale kale = kaleler.get(kaleId);
            if (kale == null) return;
            if (event.getCurrentItem() == null) return;
            elciTicaretSatinAl(p, kale, event.getRawSlot());
        }
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
        plugin.npcDunya().saldiriBildir(klan, kale);
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
        // DİKKAT: runTaskTimer ile çağrılan bu metottan yakalanmamış bir exception fırlarsa,
        // Bukkit o tekrarlı görevi SESSİZCE ve KALICI olarak iptal eder (restart'a kadar bir
        // daha hiç çalışmaz) - yani tek bir hatalı kale/veri, TÜM Vahşi Kale sistemini (otomatik
        // silme, disguise bekçisi, saldırı tespiti...) durdurabilir. Bu yüzden ana adımlar ayrı
        // ayrı try/catch ile sarılıyor: biri patlarsa sadece o adım o tick'te atlanır, görev
        // bir sonraki saniye yine çalışır.
        try {
            if (++disguiseBekciSayaci >= 15) {
                disguiseBekciSayaci = 0;
                disguiseBekcisi();
                elciBekcisi();
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Vahşi Kale tick() - disguise/elçi bekçisi hatası (atlanıyor)", e);
        }
        try {
            fethedilenSuresiDolanlariSil();
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Vahşi Kale tick() - otomatik silme kontrolü hatası (atlanıyor)", e);
        }
        try {
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
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Vahşi Kale tick() - saldırı/tespit kontrolü hatası (atlanıyor)", e);
        }
    }

    private void saldirTetikle(VahsiKale kale, Player p) {
        if (!kale.barisAnlasmalari.isEmpty()) {
            Klan klan = plugin.klanManager().oyuncununKlani(p.getUniqueId());
            if (klan != null && kale.barisVarMi(klan.id)) return; // elçiyle barış yapılmış, nöbetçiler saldırmaz
        }
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
                me.klansistemi.BasarimKopru.sayac(event.getPlayer(), "klan_kale");
                plugin.veri().kaydet();
                elciSil(kale); // fethedilince elçiyle diplomasi/ticaret anlamsızlaşıyor
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
        migrasyonKolonEkle("kaleler", "elci_id TEXT");
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
                            "sinir_minx, sinir_maxx, sinir_miny, sinir_maxy, sinir_minz, sinir_maxz, elci_id) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET isim=excluded.isim, seviye=excluded.seviye, " +
                            "merkez=excluded.merkez, giris=excluded.giris, tespit_yaricap=excluded.tespit_yaricap, saldiri_yaricap=excluded.saldiri_yaricap, " +
                            "fethedildi=excluded.fethedildi, fetheden_klan=excluded.fetheden_klan, fetih_zamani=excluded.fetih_zamani, prestij_verildi=excluded.prestij_verildi, " +
                            "klan_turu=excluded.klan_turu, sinir_minx=excluded.sinir_minx, sinir_maxx=excluded.sinir_maxx, sinir_miny=excluded.sinir_miny, " +
                            "sinir_maxy=excluded.sinir_maxy, sinir_minz=excluded.sinir_minz, sinir_maxz=excluded.sinir_maxz, elci_id=excluded.elci_id")) {
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
                ps.setString(19, kale.elciId == null ? null : kale.elciId.toString());
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
                String elciIdStr = rs.getString("elci_id");
                kale.elciId = elciIdStr == null ? null : UUID.fromString(elciIdStr);
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

    public java.util.Collection<VahsiKale> tumKaleler() { return new java.util.ArrayList<>(kaleler.values()); }
    public VahsiKale kaleBul(UUID id) { return kaleler.get(id); }

    public void kapat() {
        if (baglanti != null) {
            try { baglanti.close(); } catch (SQLException ignored) { }
        }
    }
}
