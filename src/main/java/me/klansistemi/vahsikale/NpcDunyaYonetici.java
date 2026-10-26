package me.klansistemi.vahsikale;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import me.klansistemi.KlanSistemi;
import me.klansistemi.Para;
import me.klansistemi.model.Klan;
import me.klansistemi.model.KlanUyesi;
import me.klansistemi.model.Rol;

/**
 * Faz 5: NPC klanlarının (Vahşi Kaleler) arka plan dünyası. Hepsi PERİYODİK/SOYUT kurallarla işler (gerçek
 * zamanlı ağır simülasyon yok):
 *  - Otonom NPC-NPC diplomasi: savaş / ittifak / ticaret (güç + hazine soyut değerleri)
 *  - Soyut kervan: kale başına bir kervan, periyodik "yolda olay" rulosu; yok olursa ENKAZ kaydı
 *  - Enkaz: oyuncu 70 blok yaklaşınca tek seferlik sandık (lazy), yaklaştıkça süre SIFIRLANIR
 *  - Hancı dedikodusu: /npcklan dedikodu (bahşiş, cooldown) - koordinat verilmez, 8 yön cümlesi
 *  - Oyuncu klanı ↔ NPC klanı: tanışma, saygı, görev (haraç/eşya), ittifak (barış), ücretli takviye
 * Varsayımlar (kullanıcıya raporlandı): hazine/güç soyut sayıdır (gerçek eşya değil); patika ve köprü
 * (kalıcı dünya değişikliği) bu sürümde YOK.
 */
public class NpcDunyaYonetici implements Listener {

    private static final String[] YON = {"kuzey", "kuzeydoğu", "doğu", "güneydoğu", "güney", "güneybatı", "batı", "kuzeybatı"};
    private static final String[] YER = {"büyük vadiden", "taşlı patikadan", "sık ormandan", "nehir kıyısından", "eski harabelerin yanından", "çorak ovadan"};

    private final KlanSistemi plugin;
    private final Random rng = new Random();
    private final File dosya;
    private boolean veriYuklendi = false;

    private final Map<UUID, Integer> guc = new LinkedHashMap<>();
    private final Map<UUID, Double> hazine = new LinkedHashMap<>(); // soyut PARA (haraç/ticaret)
    private final Map<UUID, List<ItemStack>> hazineEsya = new LinkedHashMap<>(); // GERÇEK eşyalar (kervan yağması)
    private final Map<UUID, String[]> zindanGorev = new LinkedHashMap<>(); // klan -> {kaleId, zindanId, zindanIsim, dunya, x, y, z}
    private final Map<String, String> iliski = new LinkedHashMap<>(); // "a|b" (sıralı) -> ITTIFAK | SAVAS
    private final Map<UUID, Map<UUID, Integer>> saygi = new LinkedHashMap<>(); // klan -> kale -> saygı (anahtar var = tanışıldı)
    private final Map<UUID, long[]> takviye = new LinkedHashMap<>(); // klan -> {gunNo, sayi, sonZaman}
    private final Map<UUID, Map<UUID, Long>> ittifaklar = new LinkedHashMap<>(); // klan -> kale -> bitiş (KALICI)
    private final Map<UUID, Long> dedikoduSon = new LinkedHashMap<>();
    private final Map<UUID, Kervan> kervanlar = new LinkedHashMap<>();
    private final Map<UUID, Enkaz> enkazlar = new LinkedHashMap<>();

    private static class Kervan {
        double x, z; boolean enkaz; final List<ItemStack> esya = new ArrayList<>();
        String kaynakKey, hedefKey; double kx, kz; boolean koyGordu;
        final List<UUID> varliklar = new ArrayList<>(); // görünür kervan entity'leri (kalıcı DEĞİL)
        int muhafiz = -1;               // -1 = kale seviyesine göre varsayılan; yolda kayıp verebilir
        double sonX, sonZ; int takilma; // görünür kervan takılma sayacı (kalıcı değil)
    }

    private static final class DKoy { String dunya; double x, z; }
    private final List<DKoy> dogalKoyler = new ArrayList<>();            // Minecraft'ın doğal köyleri (keşfedildikçe)
    private final Map<UUID, Long> koyAramaSon = new LinkedHashMap<>();   // kale -> son köy arama zamanı
    private final Map<UUID, long[]> kervanIpucu = new LinkedHashMap<>(); // kale -> {adet, pencereBaşı}
    private final Map<UUID, Long> bilgiSon = new LinkedHashMap<>();      // oyuncu -> hancı bilgi cooldown

    private static final class Takviye { UUID kaleId, sahip; long sonUcret; }
    private final Map<UUID, Takviye> aktifTakviyeler = new LinkedHashMap<>(); // klan -> aktif takviye (kalıcı DEĞİL)

    private static class Enkaz {
        UUID kaleId; String dunya; double x, z; final List<ItemStack> esya = new ArrayList<>();
        long sonYakinlik; boolean sandikAcildi; boolean sandikYerlesti;
        int sx, sy, sz;
        final List<String> sahne = new ArrayList<>(); // "x;y;z;konulanMalzeme;ilkBlokVerisi"
        boolean sahneHazir;
    }

    public NpcDunyaYonetici(KlanSistemi plugin) {
        this.plugin = plugin;
        this.dosya = new File(plugin.getDataFolder(), "npc_dunya.yml");
        yukle();
    }

    // ------------------------------------------------------------------ AYAR
    private int dk(String yol, int v) { return plugin.getConfig().getInt("npc." + yol, v); }
    private boolean aktif() { return plugin.getConfig().getBoolean("npc.aktif", true); }

    private static String anahtar(UUID a, UUID b) {
        return a.toString().compareTo(b.toString()) < 0 ? a + "|" + b : b + "|" + a;
    }

    private int gucu(VahsiKale k) { return guc.computeIfAbsent(k.id, i -> k.seviye * 10); }
    private double hazinesi(VahsiKale k) { return hazine.computeIfAbsent(k.id, i -> k.seviye * 200.0); }

    private List<VahsiKale> aktifKaleler() {
        List<VahsiKale> l = new ArrayList<>();
        for (VahsiKale k : plugin.vahsiKale().tumKaleler()) if (!k.fethedildi && k.merkez != null && k.merkez.getWorld() != null) l.add(k);
        return l;
    }

    // ------------------------------------------------------------------ OTONOM DİPLOMASİ
    /** Periyodik (npc.otonom-dakika). Rastgele iki aktif kale arasında soyut olay. */
    public void otonomTick() {
        if (!aktif() || !veriYuklendi) return;
        List<VahsiKale> l = aktifKaleler();
        if (l.size() < 2) return;
        VahsiKale a = l.get(rng.nextInt(l.size()));
        VahsiKale b = l.get(rng.nextInt(l.size()));
        if (a == b) return;
        String key = anahtar(a.id, b.id);
        String mevcut = iliski.get(key);
        double zar = rng.nextDouble();
        if ("SAVAS".equals(mevcut)) {
            savasCoz(a, b, key);
        } else if ("ITTIFAK".equals(mevcut)) {
            if (zar < 0.10) { iliski.remove(key); plugin.getLogger().info("[NPC] İttifak bozuldu: " + a.isim + " x " + b.isim); }
        } else if (zar < 0.35) {
            iliski.put(key, "SAVAS");
            plugin.getLogger().info("[NPC] Savaş ilan etti: " + a.isim + " x " + b.isim);
        } else if (zar < 0.65) {
            iliski.put(key, "ITTIFAK");
            plugin.getLogger().info("[NPC] İttifak kurdu: " + a.isim + " + " + b.isim);
        } else {
            // ticaret: ikisinin de hazinesi seviyeyle orantılı artar (aynı sunucu ekonomisine soyut bağlı)
            hazine.put(a.id, hazinesi(a) + 40.0 * a.seviye);
            hazine.put(b.id, hazinesi(b) + 40.0 * b.seviye);
        }
        kaydet();
    }

    /** Savaş sonucu: güce göre olasılık. Kazanan kaybedenin hazinesinin %25'ini ATOMİK alır (tek blokta, çift sayım yok). */
    private void savasCoz(VahsiKale a, VahsiKale b, String key) {
        synchronized (this) {
            double pa = gucu(a) / (double) (gucu(a) + gucu(b));
            VahsiKale kazanan = rng.nextDouble() < pa ? a : b;
            VahsiKale kaybeden = kazanan == a ? b : a;
            double ganimet = Math.floor(hazinesi(kaybeden) * 0.25);
            hazine.put(kaybeden.id, hazinesi(kaybeden) - ganimet);
            hazine.put(kazanan.id, hazinesi(kazanan) + ganimet);
            List<ItemStack> alinan = hazineAl(kaybeden.id, 0.25); // gerçek eşya ganimeti: önce kaybedenden ÇIKAR, sonra kazanana EKLE
            hazineyeEkle(kazanan.id, alinan);
            guc.put(kazanan.id, gucu(kazanan) + 3);
            guc.put(kaybeden.id, Math.max(1, gucu(kaybeden) - 2));
            iliski.remove(key);
            plugin.getLogger().info("[NPC] Savaş bitti: " + kazanan.isim + " kazandı, " + kaybeden.isim + "'dan " + Math.round(ganimet) + " ganimet aldı.");
        }
    }

    private static int deger(Material m) {
        return switch (m) {
            case IRON_INGOT -> 1; case GOLD_INGOT -> 2; case EMERALD -> 4; case DIAMOND -> 5; case NETHERITE_SCRAP -> 8; case NETHERITE_INGOT -> 30;
            default -> 0;
        };
    }

    /** Hazinedeki GERÇEK değerli eşyalar tüketilerek güç artar (silah/zırh yapımı soyutlaması). Üst sınır yok (karar). */
    private void gelisim(VahsiKale k) {
        List<ItemStack> l = hazineEsya.get(k.id);
        if (l == null) return;
        int esik = 20 * k.seviye, toplam = 0;
        for (ItemStack it : l) toplam += deger(it.getType()) * it.getAmount();
        if (toplam < esik) return;
        int harca = esik;
        for (java.util.Iterator<ItemStack> i = l.iterator(); i.hasNext() && harca > 0; ) {
            ItemStack it = i.next();
            int d = deger(it.getType());
            if (d == 0) continue;
            int adet = Math.min(it.getAmount(), (int) Math.ceil(harca / (double) d));
            harca -= adet * d;
            if (adet >= it.getAmount()) i.remove(); else it.setAmount(it.getAmount() - adet);
        }
        guc.put(k.id, gucu(k) + 1);
    }

    private static final int HAZINE_STACK_SINIRI = 300;

    private synchronized void hazineyeEkle(UUID kale, List<ItemStack> eklenecek) {
        List<ItemStack> l = hazineEsya.computeIfAbsent(kale, i -> new ArrayList<>());
        for (ItemStack e : eklenecek) {
            if (e == null || e.getType().isAir()) continue;
            ItemStack kalan = e.clone();
            for (ItemStack mevcut : l) {
                if (kalan.getAmount() <= 0) break;
                if (mevcut.isSimilar(kalan) && mevcut.getAmount() < mevcut.getMaxStackSize()) {
                    int tasi = Math.min(kalan.getAmount(), mevcut.getMaxStackSize() - mevcut.getAmount());
                    mevcut.setAmount(mevcut.getAmount() + tasi);
                    kalan.setAmount(kalan.getAmount() - tasi);
                }
            }
            if (kalan.getAmount() > 0 && l.size() < HAZINE_STACK_SINIRI) l.add(kalan);
        }
    }

    /** Hazineden yaklaşık oran kadar stack'i ÇIKARIR ve döndürür (çıkarma tek senkron blokta - çift kullanım olmaz). */
    private synchronized List<ItemStack> hazineAl(UUID kale, double oran) {
        List<ItemStack> l = hazineEsya.get(kale);
        List<ItemStack> cikan = new ArrayList<>();
        if (l == null || l.isEmpty()) return cikan;
        int n = (int) Math.max(1, Math.floor(l.size() * oran));
        for (int i = 0; i < n && !l.isEmpty(); i++) cikan.add(l.remove(rng.nextInt(l.size())));
        return cikan;
    }

    private List<ItemStack> lootUret(boolean hayvan, int seviye) {
        List<ItemStack> l = new ArrayList<>();
        if (hayvan) {
            Material[] h = {Material.LEATHER, Material.BEEF, Material.PORKCHOP, Material.BONE, Material.STRING, Material.FEATHER};
            for (int i = 0; i < 2 + rng.nextInt(3); i++) l.add(new ItemStack(h[rng.nextInt(h.length)], 2 + rng.nextInt(5)));
        } else {
            Material[] t = {Material.WHEAT, Material.BREAD, Material.COAL, Material.ARROW, Material.PAPER, Material.IRON_INGOT, Material.EMERALD};
            for (int i = 0; i < 2 + rng.nextInt(3); i++) l.add(new ItemStack(t[rng.nextInt(t.length)], 1 + rng.nextInt(5)));
            if (seviye >= 2 && rng.nextDouble() < 0.4) l.add(new ItemStack(Material.GOLD_INGOT, 1 + rng.nextInt(3)));
            if (seviye >= 3 && rng.nextDouble() < 0.25) l.add(new ItemStack(Material.DIAMOND, 1 + rng.nextInt(2)));
            if (seviye >= 5 && rng.nextDouble() < 0.10) l.add(new ItemStack(Material.NETHERITE_SCRAP, 1));
        }
        return l;
    }

    /** Fetihte: kalenin hazine eşyaları kalenin yanındaki sandık(lar)a konur - oyuncu yağmalar. Önce hazineden ÇIKARILIR ve kaydedilir. */
    public void fetihDagit(VahsiKale kale) {
        if (!veriYuklendi) return;
        List<ItemStack> l;
        synchronized (this) { l = hazineEsya.remove(kale.id); }
        if (l == null || l.isEmpty() || kale.merkez == null || kale.merkez.getWorld() == null) return;
        kaydet();
        World w = kale.merkez.getWorld();
        int[][] ofs = {{3, 0}, {3, 2}, {3, -2}, {5, 0}, {-3, 0}, {-3, 2}};
        int idx = 0, yerlesen = 0;
        List<ItemStack> kalan = new ArrayList<>(l);
        for (int[] o : ofs) {
            if (kalan.isEmpty()) break;
            int x = kale.merkez.getBlockX() + o[0], z = kale.merkez.getBlockZ() + o[1];
            Block b = w.getBlockAt(x, w.getHighestBlockYAt(x, z) + 1, z);
            if (!b.getType().isAir()) continue;
            b.setType(Material.CHEST);
            if (b.getState(false) instanceof Chest c) {
                java.util.Iterator<ItemStack> it = kalan.iterator();
                int n = 0;
                while (it.hasNext() && n < 27) { c.getInventory().addItem(it.next()); it.remove(); n++; }
                c.update(true, false);
                yerlesen++;
            }
        }
        for (ItemStack k : kalan) w.dropItemNaturally(kale.merkez, k); // yer bulunamadıysa yere dök (kayıp yok)
        plugin.getLogger().info("[NPC] " + kale.isim + " hazine odası yağmaya açıldı: " + l.size() + " stack, " + yerlesen + " sandık.");
    }

    /** Nöbetçi ölünce hazineden 1 stack yere düşer (loot yere düşer, otomatik kimseye gitmez - karar). */
    public void nobetciOlduLoot(VahsiKale kale, Location konum) {
        if (!veriYuklendi || konum.getWorld() == null || rng.nextDouble() > 0.35) return;
        List<ItemStack> al = hazineAl(kale.id, 0.02);
        for (ItemStack it : al) konum.getWorld().dropItemNaturally(konum, it);
        if (!al.isEmpty()) kaydet();
    }

    // ------------------------------------------------------------------ KERVAN
    private record Dugum(String key, double x, double z, boolean koy) { }

    private List<Dugum> dugumler(World w) {
        List<Dugum> l = new ArrayList<>();
        for (VahsiKale k : aktifKaleler()) {
            if (k.merkez.getWorld().equals(w)) l.add(new Dugum("kale:" + k.id, k.merkez.getX(), k.merkez.getZ(), false));
        }
        // Kervanlar bizim köyümüze/claim'lerimize DEĞİL, Minecraft'ın doğal köylerine gider
        for (DKoy d : dogalKoyler) if (d.dunya.equals(w.getName())) l.add(new Dugum("v:" + (int) d.x + ":" + (int) d.z, d.x, d.z, true));
        return l;
    }

    /** Rota boyunca köy/claim/klan bölgesi var mı (kervan oralara uğramasın). */
    private boolean rotaTemiz(World w, double x1, double z1, double x2, double z2) {
        double uz = Math.hypot(x2 - x1, z2 - z1);
        int adim = (int) Math.max(1, uz / 48);
        for (int i = 0; i <= adim; i++) {
            int px = (int) (x1 + (x2 - x1) * i / adim), pz = (int) (z1 + (z2 - z1) * i / adim);
            if (YasakAlan.koyaDegiyor(w, px, pz, 100)) return false;
            if (YasakAlan.claimeDegiyor(w, px, pz, 32)) return false;
        }
        return true;
    }

    /** Her çağrıda en fazla bir doğal köy araması (locate ağır olabilir); kale başına 6 saatte bir. */
    private void koyKesfet() {
        long simdi = System.currentTimeMillis();
        for (VahsiKale k : aktifKaleler()) {
            World w = k.merkez.getWorld();
            Long son = koyAramaSon.get(k.id);
            if (son != null && simdi - son < 6L * 3_600_000L) continue;
            koyAramaSon.put(k.id, simdi);
            int yakin = 0;
            for (DKoy d : dogalKoyler) if (d.dunya.equals(w.getName()) && Math.hypot(d.x - k.merkez.getX(), d.z - k.merkez.getZ()) < 2500) yakin++;
            Location kaynak = k.merkez.clone();
            if (yakin > 0) { // ilk köy biliniyorsa ağı genişletmek için kaleden uzak bir noktadan ara
                double aci = rng.nextDouble() * Math.PI * 2, d = 1200 + rng.nextDouble() * 800;
                kaynak.add(Math.cos(aci) * d, 0, Math.sin(aci) * d);
            }
            try {
                var r = w.locateNearestStructure(kaynak, org.bukkit.generator.structure.StructureType.VILLAGE, 64, false);
                if (r == null) return;
                Location l = r.getLocation();
                int x = l.getBlockX(), z = l.getBlockZ();
                if (YasakAlan.engelliMi(w, x, z, 80)) return; // bizim köy / claim / klan bölgesi: kervan oraya gitmesin
                for (DKoy d : dogalKoyler) if (d.dunya.equals(w.getName()) && Math.hypot(d.x - x, d.z - z) < 200) return;
                DKoy n = new DKoy(); n.dunya = w.getName(); n.x = x; n.z = z;
                dogalKoyler.add(n);
                plugin.getLogger().info("[NPC] doğal köy keşfedildi: " + w.getName() + " " + x + "," + z);
                kaydet();
            } catch (Throwable t) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "[NPC] köy arama hatası (atlandı)", t);
            }
            return;
        }
    }

    private static Dugum dugumBul(List<Dugum> l, String key) {
        if (key == null) return null;
        for (Dugum d : l) if (d.key().equals(key)) return d;
        return null;
    }

    private static String rotaAnahtari(String a, String b) { return a.compareTo(b) < 0 ? a + "~" + b : b + "~" + a; }

    /** Rotalar: en yakın düğüm (doğal köy / kale); köy/claim/klan bölgesinden geçen rotalar elenir. */
    private void hedefSec(Kervan kv, VahsiKale k, List<Dugum> dugumler) {
        World w = k.merkez.getWorld();
        String home = "kale:" + k.id;
        Dugum ev = dugumBul(dugumler, home);
        if (kv.esya.size() >= 8 && !home.equals(kv.kaynakKey) && ev != null && rotaTemiz(w, kv.x, kv.z, ev.x(), ev.z())) { kv.hedefKey = home; return; }
        final double cx = kv.x, cz = kv.z;
        List<Dugum> aday = new ArrayList<>();
        for (Dugum d : dugumler) {
            if (d.key().equals(kv.kaynakKey) || Math.hypot(d.x() - cx, d.z() - cz) > 4500) continue;
            if (rotaTemiz(w, cx, cz, d.x(), d.z())) aday.add(d);
        }
        if (aday.isEmpty()) { kv.hedefKey = (ev != null && !home.equals(kv.kaynakKey) && rotaTemiz(w, cx, cz, ev.x(), ev.z())) ? home : null; return; }
        aday.sort(java.util.Comparator.comparingDouble(d -> Math.hypot(d.x() - cx, d.z() - cz)));
        List<Dugum> koyler = new ArrayList<>();
        for (Dugum d : aday) if (d.koy()) koyler.add(d);
        List<Dugum> havuz = (!kv.koyGordu && !koyler.isEmpty()) ? koyler : aday;
        kv.hedefKey = havuz.get(rng.nextInt(Math.min(3, havuz.size()))).key();
    }

    private int muhafizSayisi(VahsiKale k, Kervan kv) { return kv.muhafiz >= 0 ? kv.muhafiz : Math.min(6, 2 + k.seviye / 2); }

    private void ekonomiLog(String kat, String kim, String detay) {
        try {
            org.bukkit.plugin.Plugin m = Bukkit.getPluginManager().getPlugin("MeslekSistemi");
            if (m != null) m.getClass().getMethod("ekonomiLog", String.class, String.class, String.class).invoke(m, kat, kim, detay);
        } catch (Throwable t) { plugin.getLogger().info("[NPC-EKONOMI] " + kat + " " + kim + " " + detay); }
    }

    /** Kervan bir düğüme vardı: rota sayacı (patika/köprü), yük teslimi / ticaret / han. */
    private void varis(VahsiKale k, Kervan kv, World w, Dugum hedef) {
        String home = "kale:" + k.id;
        kv.x = hedef.x(); kv.z = hedef.z();
        plugin.patika().rotaEkle(w, rotaAnahtari(kv.kaynakKey, hedef.key()), (int) kv.kx, (int) kv.kz, (int) hedef.x(), (int) hedef.z());
        kv.kaynakKey = hedef.key(); kv.kx = hedef.x(); kv.kz = hedef.z(); kv.hedefKey = null;
        if (hedef.key().equals(home)) {
            List<ItemStack> getirilen;
            synchronized (this) { getirilen = new ArrayList<>(kv.esya); kv.esya.clear(); }
            hazineyeEkle(k.id, getirilen); // yük önce kervandan ÇIKAR, sonra hazineye eklenir
            kv.muhafiz = -1;               // kayıplar evde telafi edilir
        } else {
            kv.esya.addAll(lootUret(false, k.seviye)); // köy/kale ticareti
            if (hedef.koy()) {
                kv.koyGordu = true;
                plugin.hanci().koyaHanciKur(hedef.key(), w, hedef.x(), hedef.z()); // doğal köye ulaştı: han yoksa kurulur
            }
        }
    }

    /** Pusu/yenilgi: kervan yok olur, yükün bir kısmı ENKAZ olarak kalır; kervan 30 dk sonra yeniden kurulur. */
    private Enkaz enkazOlustur(VahsiKale k, Kervan kv, double x, double z, boolean yarisi) {
        World w = k.merkez.getWorld();
        kv.enkaz = true;
        Enkaz e = new Enkaz();
        e.kaleId = k.id; e.dunya = w.getName(); e.x = x; e.z = z; e.sonYakinlik = System.currentTimeMillis();
        for (int i = 0; i < kv.esya.size(); i++) if (!yarisi || i % 2 == 0) e.esya.add(kv.esya.get(i));
        if (e.esya.size() < 3) e.esya.addAll(lootUret(false, k.seviye)); // yük azsa bile sandık boş kalmasın
        enkazlar.put(UUID.randomUUID(), e);
        kv.esya.clear();
        String home = "kale:" + k.id;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            kv.enkaz = false; kv.x = k.merkez.getX(); kv.z = k.merkez.getZ(); kv.muhafiz = -1;
            kv.kaynakKey = home; kv.kx = kv.x; kv.kz = kv.z; kv.hedefKey = null;
        }, 20L * 60 * 30);
        return e;
    }

    /** Periyodik (npc.kervan-dakika): her aktif kale için soyut yolculuk + olay rulosu. */
    public void kervanTick() {
        if (!aktif() || !veriYuklendi) return;
        koyKesfet();
        for (VahsiKale k : aktifKaleler()) {
            gelisim(k);
            World w = k.merkez.getWorld();
            Kervan kv = kervanlar.computeIfAbsent(k.id, i -> {
                Kervan n = new Kervan(); n.x = k.merkez.getX(); n.z = k.merkez.getZ(); return n;
            });
            if (kv.enkaz) continue;
            if (gorselAktif(kv)) continue; // görünür kervan kendi yürüyor
            String home = "kale:" + k.id;
            if (kv.kaynakKey == null) { kv.kaynakKey = home; kv.kx = k.merkez.getX(); kv.kz = k.merkez.getZ(); }
            List<Dugum> dugumler = dugumler(w);
            if (kv.hedefKey == null) hedefSec(kv, k, dugumler);
            Dugum hedef = dugumBul(dugumler, kv.hedefKey);
            if (hedef == null) { kv.hedefKey = null; continue; }
            double kalan = Math.hypot(hedef.x() - kv.x, hedef.z() - kv.z);
            double adim = dk("kervan-adim", 350);
            if (kalan <= adim) { varis(k, kv, w, hedef); continue; }
            // yolda ilerle (dogrusal rota)
            kv.x += (hedef.x() - kv.x) / kalan * adim;
            kv.z += (hedef.z() - kv.z) / kalan * adim;
            double olay = rng.nextDouble();
            if (olay < 0.30) {          // hayvan/yaratık saldırısı: nöbetçi gücüne ve sayısına göre
                double sans = Math.min(0.92, 0.45 + gucu(k) / 200.0 + muhafizSayisi(k, kv) * 0.03);
                if (rng.nextDouble() < sans) kv.esya.addAll(lootUret(true, k.seviye));
                else { // savunma başarısız: nöbetçi kaybı + yükün bir kısmı gider
                    kv.muhafiz = Math.max(1, muhafizSayisi(k, kv) - 1);
                    if (!kv.esya.isEmpty()) kv.esya.remove(rng.nextInt(kv.esya.size()));
                }
            } else if (olay < 0.33) {   // pusu: kervan yok oldu -> ENKAZ
                Enkaz e = enkazOlustur(k, kv, kv.x, kv.z, true);
                plugin.getLogger().info("[NPC] " + k.isim + " kervanı pusuya düştü -> ENKAZ (" + Math.round(e.x) + "," + Math.round(e.z) + ")");
            } else if (olay < 0.45) {   // başka klanın kervanıyla karşılaşma
                kervanKarsilasma(k, kv, w);
            }
        }
        kaydet();
    }

    /** 600 blok içindeki başka klanın kervanıyla karşılaşma: ittifak/ilişkisiz -> ticaret, savaş -> çatışma. */
    private void kervanKarsilasma(VahsiKale k, Kervan kv, World w) {
        VahsiKale diger = null; Kervan dv = null; double en = 600;
        for (VahsiKale o : aktifKaleler()) {
            if (o == k || !o.merkez.getWorld().equals(w)) continue;
            Kervan ov = kervanlar.get(o.id);
            if (ov == null || ov.enkaz || gorselAktif(ov)) continue;
            double d = Math.hypot(ov.x - kv.x, ov.z - kv.z);
            if (d < en) { en = d; diger = o; dv = ov; }
        }
        if (diger == null) return;
        String durum = iliski.get(anahtar(k.id, diger.id));
        if ("SAVAS".equals(durum)) {
            double pa = (gucu(k) + muhafizSayisi(k, kv) * 2.0) / (double) (gucu(k) + muhafizSayisi(k, kv) * 2.0 + gucu(diger) + muhafizSayisi(diger, dv) * 2.0);
            boolean aKazandi = rng.nextDouble() < pa;
            VahsiKale kazanan = aKazandi ? k : diger, kaybeden = aKazandi ? diger : k;
            Kervan kk = aKazandi ? kv : dv, kb = aKazandi ? dv : kv;
            if (rng.nextDouble() < 0.5) { // kaybeden kervan yok olur, yükü enkaza kalır
                enkazOlustur(kaybeden, kb, kb.x, kb.z, false);
                plugin.getLogger().info("[NPC] " + kazanan.isim + " kervanı " + kaybeden.isim + " kervanını bozguna uğrattı -> ENKAZ");
            } else {                      // kaybeden zayıflar, yükünün yarısını verir
                kb.muhafiz = Math.max(1, muhafizSayisi(kaybeden, kb) - 1);
                List<ItemStack> alinan = new ArrayList<>();
                for (int i = kb.esya.size() - 1; i >= 0; i -= 2) alinan.add(kb.esya.remove(i));
                kk.esya.addAll(alinan);
            }
        } else if ("ITTIFAK".equals(durum) || rng.nextDouble() < 0.5) { // karşılıklı ticaret
            kv.esya.addAll(lootUret(false, k.seviye));
            dv.esya.addAll(lootUret(false, diger.seviye));
        }
    }

    /** ~3 sn'de bir: oyuncu yakınlığı (lazy spawn + süre sıfırlama + süre dolumu). */
    public void enkazKontrol() {
        if (!aktif() || !veriYuklendi) return;
        try { takviyeKontrol(); } catch (Exception ex) { plugin.getLogger().log(java.util.logging.Level.WARNING, "[NPC] takviye kontrol hatası", ex); }
        try { kervanGorsel(); } catch (Exception ex) { plugin.getLogger().log(java.util.logging.Level.WARNING, "[NPC] görünür kervan hatası", ex); }
        if (enkazlar.isEmpty()) return;
        long omur = dk("enkaz-omur-dakika", 40) * 60_000L;
        long simdi = System.currentTimeMillis();
        List<UUID> silinecek = new ArrayList<>();
        for (Map.Entry<UUID, Enkaz> en : enkazlar.entrySet()) {
            Enkaz e = en.getValue();
            World w = Bukkit.getWorld(e.dunya);
            if (w == null) continue;
            boolean yakin = false;
            for (Player p : w.getPlayers()) {
                double dx = p.getLocation().getX() - e.x, dz = p.getLocation().getZ() - e.z;
                if (dx * dx + dz * dz <= 70.0 * 70.0) { yakin = true; break; }
            }
            if (yakin) {
                e.sonYakinlik = simdi; // oyuncu yaklaştıkça süre SIFIRLANIR (karar)
                if (!e.sandikYerlesti && !e.sandikAcildi) sandikYerlestir(e, w);
            }
            if (simdi - e.sonYakinlik > omur || e.sandikAcildi && !yakin) silinecek.add(en.getKey());
        }
        for (UUID id : silinecek) { sandikTemizle(enkazlar.get(id)); enkazlar.remove(id); }
        if (!silinecek.isEmpty()) kaydet();
    }

    private void sandikYerlestir(Enkaz e, World w) {
        int x = (int) Math.floor(e.x), z = (int) Math.floor(e.z);
        if (YasakAlan.engelliMi(w, x, z, plugin.getConfig().getInt("vahsikale.yasak-alan-yaricap", 80))) { e.sandikAcildi = true; return; } // korumalı bölgede yerleşme
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return;
        int y = w.getHighestBlockYAt(x, z) + 1;
        Block b = w.getBlockAt(x, y, z);
        if (!b.getType().isAir()) return;
        if (e.esya.isEmpty()) { // eski kayıtlardan kalan boş enkaz: yine de ganimet olsun
            VahsiKale kk = plugin.vahsiKale().kaleBul(e.kaleId);
            e.esya.addAll(lootUret(false, kk != null ? kk.seviye : 1));
        }
        b.setType(Material.CHEST);
        if (b.getState(false) instanceof Chest c) {
            for (ItemStack it : e.esya) c.getInventory().addItem(it); // GERÇEK kervan yükü, tek seferlik
            e.esya.clear(); // artık sandıkta; tekrar yerleşirse boş
            c.update(true, false);
        }
        e.sx = x; e.sy = y; e.sz = z; e.sandikYerlesti = true;
        try { sahneKur(e, w, x, y, z); } catch (Exception ex) { plugin.getLogger().log(java.util.logging.Level.WARNING, "[NPC] enkaz sahnesi kurulamadı", ex); }
        kaydet();
    }

    private final org.bukkit.NamespacedKey enkazHayvanKey() { return new org.bukkit.NamespacedKey(plugin, "npc-enkaz-hayvan"); }

    /** "Savaş döndü" görünümü: etrafa kırık çitler, devrilmiş yük parçaları, yırtık bez ve yaralı yük hayvanları.
     *  Konan her bloğun önceki hali kaydedilir; enkaz silinince geri yüklenir. */
    private void sahneKur(Enkaz e, World w, int cx, int cy, int cz) {
        if (e.sahneHazir) return;
        e.sahneHazir = true;
        Material[] parca = {Material.OAK_FENCE, Material.OAK_FENCE, Material.OAK_FENCE, Material.OAK_FENCE, Material.OAK_FENCE,
                Material.OAK_SLAB, Material.OAK_SLAB, Material.OAK_TRAPDOOR, Material.BROWN_CARPET, Material.WHITE_CARPET, Material.COBWEB};
        int hedef = 8 + rng.nextInt(4), konan = 0, deneme = 0;
        while (konan < hedef && deneme++ < 60) {
            int x = cx + rng.nextInt(11) - 5, z = cz + rng.nextInt(11) - 5;
            if ((x == cx && z == cz) || !w.isChunkLoaded(x >> 4, z >> 4)) continue;
            int y = w.getHighestBlockYAt(x, z);
            Block zemin = w.getBlockAt(x, y, z);
            if (!zemin.getType().isSolid() || zemin.isLiquid()) continue;
            Block ust = w.getBlockAt(x, y + 1, z);
            if (!(ust.getType().isAir() || ust.getType() == Material.SHORT_GRASS)) continue;
            Material m = parca[rng.nextInt(parca.length)];
            String ilk = ust.getBlockData().getAsString();
            ust.setType(m, false);
            if (m == Material.OAK_SLAB || m == Material.OAK_TRAPDOOR) { /* varsayılan veri yeterli: devrilmiş parça */ }
            e.sahne.add(x + ";" + (y + 1) + ";" + z + ";" + m.name() + ";" + ilk);
            konan++;
        }
        // yaralı yük hayvanları (ceset yok; sağ ve ayakta ama zayıf)
        org.bukkit.entity.EntityType[] hayvan = {org.bukkit.entity.EntityType.DONKEY, org.bukkit.entity.EntityType.MULE, org.bukkit.entity.EntityType.LLAMA};
        int adet = 1 + rng.nextInt(2);
        for (int i = 0; i < adet; i++) {
            Location l = new Location(w, cx + 0.5 + rng.nextInt(5) - 2, cy, cz + 0.5 + rng.nextInt(5) - 2);
            if (!w.getBlockAt(l).getType().isAir()) l = new Location(w, cx + 0.5, cy + 1, cz + 3.5);
            org.bukkit.entity.Entity h = w.spawnEntity(l, hayvan[rng.nextInt(hayvan.length)]);
            h.setPersistent(false); // chunk boşalınca kendiliğinden gider
            h.getPersistentDataContainer().set(enkazHayvanKey(), org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
            if (h instanceof org.bukkit.entity.LivingEntity le) {
                var mx = le.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
                double max = mx != null ? mx.getValue() : 20;
                le.setHealth(Math.max(1, max * 0.35));
                le.setCustomName(ChatColor.GRAY + "Yaralı yük hayvanı");
            }
        }
        // ikinci (yarı dolu) sandık
        for (int d = 0; d < 20; d++) {
            int x = cx + rng.nextInt(7) - 3, z = cz + rng.nextInt(7) - 3;
            if ((x == cx && z == cz) || !w.isChunkLoaded(x >> 4, z >> 4)) continue;
            int y = w.getHighestBlockYAt(x, z);
            Block zemin = w.getBlockAt(x, y, z), ust = w.getBlockAt(x, y + 1, z);
            if (!zemin.getType().isSolid() || zemin.isLiquid() || !ust.getType().isAir()) continue;
            String ilk = ust.getBlockData().getAsString();
            ust.setType(Material.CHEST);
            if (ust.getState(false) instanceof Chest c2) { for (ItemStack it : lootUret(false, 1)) c2.getInventory().addItem(it); c2.update(true, false); }
            e.sahne.add(x + ";" + (y + 1) + ";" + z + ";CHEST;" + ilk);
            break;
        }
        // 2-3 düşman (sabit sayı, yeniden doğmaz; enkaz silinince kalkar)
        org.bukkit.entity.EntityType[] dusman = {org.bukkit.entity.EntityType.PILLAGER, org.bukkit.entity.EntityType.VINDICATOR, org.bukkit.entity.EntityType.PILLAGER};
        int mob = 2 + rng.nextInt(2);
        for (int i = 0; i < mob; i++) {
            Location l = new Location(w, cx + 0.5 + rng.nextInt(9) - 4, cy, cz + 0.5 + rng.nextInt(9) - 4);
            if (!w.getBlockAt(l).getType().isAir()) l = new Location(w, cx + 0.5, cy + 1, cz - 2.5);
            org.bukkit.entity.Entity m = w.spawnEntity(l, dusman[rng.nextInt(dusman.length)]);
            if (m instanceof org.bukkit.entity.Mob mm) mm.setRemoveWhenFarAway(true);
            m.setPersistent(false);
            m.getPersistentDataContainer().set(enkazHayvanKey(), org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        }
    }

    private void sahneTemizle(Enkaz e, World w) {
        for (String kayit : e.sahne) {
            try {
                String[] a = kayit.split(";", 5);
                int x = Integer.parseInt(a[0]), y = Integer.parseInt(a[1]), z = Integer.parseInt(a[2]);
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue; // yüklü değil: kalıntı zararsız, atlanır
                Block b = w.getBlockAt(x, y, z);
                if (b.getType().name().equals(a[3])) b.setBlockData(Bukkit.createBlockData(a[4]), false);
            } catch (Exception ignored) { }
        }
        e.sahne.clear();
        for (org.bukkit.entity.Entity en : w.getNearbyEntities(new Location(w, e.x, e.sy, e.z), 20, 20, 20))
            if (en.getPersistentDataContainer().has(enkazHayvanKey(), org.bukkit.persistence.PersistentDataType.BYTE)) en.remove();
    }

    private void sandikTemizle(Enkaz e) {
        if (e == null || !e.sandikYerlesti) return;
        World w = Bukkit.getWorld(e.dunya);
        if (w == null) return;
        sahneTemizle(e, w);
        Block b = w.getBlockAt(e.sx, e.sy, e.sz);
        if (b.getType() == Material.CHEST) {
            if (b.getState(false) instanceof Chest c) c.getInventory().clear(); // eşya yere dökülmez - dupe/çöp yok
            b.setType(Material.AIR); // yerleştirmeden önce hava olduğu doğrulanmıştı -> geri alma = hava
        }
    }

    @EventHandler
    public void onSandikAc(org.bukkit.event.inventory.InventoryCloseEvent ev) {
        if (!(ev.getInventory().getHolder() instanceof Chest c)) return;
        Location l = c.getLocation();
        for (Enkaz e : enkazlar.values()) {
            if (e.sandikYerlesti && l.getBlockX() == e.sx && l.getBlockY() == e.sy && l.getBlockZ() == e.sz && l.getWorld().getName().equals(e.dunya)) {
                boolean bos = true;
                for (ItemStack it : ev.getInventory().getContents()) if (it != null && !it.getType().isAir()) { bos = false; break; }
                if (bos) e.sandikAcildi = true; // boşalınca bir sonraki kontrolde temizlenir
            }
        }
    }

    // ------------------------------------------------------------------ GÖRÜNÜR KERVAN (oyuncu yakınken)
    private boolean gorselAktif(Kervan kv) {
        for (UUID id : kv.varliklar) { org.bukkit.entity.Entity e = Bukkit.getEntity(id); if (e != null && e.isValid()) return true; }
        return false;
    }

    private void gorselKaldir(Kervan kv) {
        for (UUID id : kv.varliklar) { org.bukkit.entity.Entity e = Bukkit.getEntity(id); if (e != null) e.remove(); }
        kv.varliklar.clear();
    }

    private boolean oyuncuYakin(World w, double x, double z, double r) {
        for (Player p : w.getPlayers()) {
            double dx = p.getLocation().getX() - x, dz = p.getLocation().getZ() - z;
            if (dx * dx + dz * dz <= r * r) return true;
        }
        return false;
    }

    private boolean kirilabilir(World w, Block b) {
        Material m = b.getType();
        if (m.isAir() || b.isLiquid() || b.getState(false) instanceof org.bukkit.block.TileState) return false;
        float sert = m.getHardness();
        if (sert < 0 || sert > 4) return false;
        if (YasakAlan.engelliMi(w, b.getX(), b.getZ(), 0)) return false; // köy / claim / klan bölgesi dokunulmaz
        Location l = b.getLocation();
        for (VahsiKale k : plugin.vahsiKale().tumKaleler()) if (k.sinirUzakligi(l) < 8) return false;
        return true;
    }

    /** ~3 sn'de bir: oyuncu 80 blok yaklaşınca soyut kervan gerçek (hayvan + insan muhafız) olarak belirir,
     *  rotası boyunca yürür (varışı da görünür halde işler), takılırsa blok kırar / ilerler; oyuncu 130 bloktan
     *  uzaklaşırsa yeniden soyuta döner. */
    public void kervanGorsel() {
        for (Map.Entry<UUID, Kervan> en : new ArrayList<>(kervanlar.entrySet())) {
            Kervan kv = en.getValue();
            VahsiKale k = plugin.vahsiKale().kaleBul(en.getKey());
            if (k == null || k.fethedildi || k.merkez == null || k.merkez.getWorld() == null || kv.enkaz) {
                if (!kv.varliklar.isEmpty()) gorselKaldir(kv);
                continue;
            }
            World w = k.merkez.getWorld();
            if (!kv.varliklar.isEmpty() && !gorselAktif(kv)) kv.varliklar.clear(); // chunk boşaldı / hepsi öldü
            if (kv.varliklar.isEmpty()) {
                if (!oyuncuYakin(w, kv.x, kv.z, 80)) continue;
                if (!w.isChunkLoaded(((int) kv.x) >> 4, ((int) kv.z) >> 4)) continue;
                if (kv.hedefKey == null) hedefSec(kv, k, dugumler(w)); // evde bekleyen kervan da yola çıksın
                if (kv.hedefKey == null) continue;
                Dugum h = dugumBul(dugumler(w), kv.hedefKey);
                if (h == null) { kv.hedefKey = null; continue; }
                if (Math.hypot(h.x() - kv.x, h.z() - kv.z) <= 15) { varis(k, kv, w, h); continue; }
                gorselKur(k, kv, w);
                continue;
            }
            org.bukkit.entity.Entity lider = null;
            for (UUID id : kv.varliklar) { org.bukkit.entity.Entity e = Bukkit.getEntity(id); if (e instanceof org.bukkit.entity.Vindicator && e.isValid()) { lider = e; break; } }
            if (lider == null) for (UUID id : kv.varliklar) { org.bukkit.entity.Entity e = Bukkit.getEntity(id); if (e != null && e.isValid()) { lider = e; break; } }
            if (lider == null) { kv.varliklar.clear(); continue; }
            Location ll = lider.getLocation();
            kv.x = ll.getX(); kv.z = ll.getZ();
            if (!oyuncuYakin(w, kv.x, kv.z, 130)) { gorselKaldir(kv); continue; }
            List<Dugum> dl = dugumler(w);
            if (kv.hedefKey == null) hedefSec(kv, k, dl);
            Dugum hedef = dugumBul(dl, kv.hedefKey);
            if (hedef == null) { kv.hedefKey = null; continue; } // gidecek temiz rota yok: olduğu yerde bekler
            double kalan = Math.hypot(hedef.x() - kv.x, hedef.z() - kv.z);
            if (kalan <= 15) { // VARIŞ: görünür kalmaya devam eder, yeni hedef seçer
                varis(k, kv, w, hedef);
                hedefSec(kv, k, dl);
                continue;
            }
            double adim = Math.min(24, kalan);
            double ax = (hedef.x() - kv.x) / kalan, az = (hedef.z() - kv.z) / kalan;
            int wx = (int) (kv.x + ax * adim), wz = (int) (kv.z + az * adim);
            if (!w.isChunkLoaded(wx >> 4, wz >> 4)) continue;
            Location wp = new Location(w, wx + 0.5, w.getHighestBlockYAt(wx, wz) + 1, wz + 0.5);
            // takılma: 3 sn'de 1.5 bloktan az ilerlediyse önce blok kır, sonra (15 sn) ileri ışınla
            if (Math.hypot(kv.x - kv.sonX, kv.z - kv.sonZ) < 1.5) kv.takilma++; else kv.takilma = 0;
            kv.sonX = kv.x; kv.sonZ = kv.z;
            if (kv.takilma >= 2) {
                int bx = ll.getBlockX(), by = ll.getBlockY(), bz = ll.getBlockZ();
                for (int i = 1; i <= 2; i++) {
                    int px = (int) Math.round(bx + ax * i), pz = (int) Math.round(bz + az * i);
                    for (int dy = 0; dy <= 2; dy++) {
                        Block b = w.getBlockAt(px, by + dy, pz);
                        if (kirilabilir(w, b)) b.setType(Material.AIR, true);
                    }
                }
            }
            if (kv.takilma >= 5) {
                for (UUID id : kv.varliklar) { org.bukkit.entity.Entity e = Bukkit.getEntity(id); if (e != null && e.isValid()) e.teleport(wp); }
                kv.takilma = 0;
                continue;
            }
            for (UUID id : kv.varliklar) {
                org.bukkit.entity.Entity e = Bukkit.getEntity(id);
                if (e instanceof org.bukkit.entity.Mob m && m.isValid() && m.getTarget() == null) {
                    try { m.getPathfinder().moveTo(wp, m instanceof org.bukkit.entity.Vindicator ? 0.9 : 1.0); } catch (Exception ignored) { }
                }
            }
        }
    }

    private void gorselKur(VahsiKale k, Kervan kv, World w) {
        int x = (int) kv.x, z = (int) kv.z, y = w.getHighestBlockYAt(x, z) + 1;
        if (w.getBlockAt(x, y - 1, z).isLiquid()) return;
        kv.sonX = kv.x; kv.sonZ = kv.z; kv.takilma = 0;
        Location b = new Location(w, x + 0.5, y, z + 0.5);
        int n = Math.max(1, Math.min(6, muhafizSayisi(k, kv)));
        for (int i = 0; i < n; i++) {
            org.bukkit.entity.Vindicator g = plugin.vahsiKale().kervanMuhafiziUret(k, b.clone().add(rng.nextInt(5) - 2, 0, rng.nextInt(5) - 2));
            kv.varliklar.add(g.getUniqueId());
        }
        org.bukkit.entity.EntityType[] t = {org.bukkit.entity.EntityType.MULE, org.bukkit.entity.EntityType.DONKEY, org.bukkit.entity.EntityType.LLAMA};
        for (int i = 0; i < 2; i++) {
            org.bukkit.entity.Entity h = w.spawnEntity(b.clone().add(rng.nextInt(5) - 2, 0, rng.nextInt(5) - 2), t[rng.nextInt(t.length)]);
            h.setPersistent(false);
            h.getPersistentDataContainer().set(plugin.vahsiKale().kervanKey, org.bukkit.persistence.PersistentDataType.STRING, k.id.toString());
            h.setCustomName(ChatColor.GOLD + k.isim + " Kervanı");
            if (h instanceof org.bukkit.entity.ChestedHorse ch) ch.setCarryingChest(true);
            kv.varliklar.add(h.getUniqueId());
        }
    }

    private org.bukkit.entity.Player saldirgan(org.bukkit.event.entity.EntityDamageByEntityEvent ev) {
        org.bukkit.entity.Entity d = ev.getDamager();
        if (d instanceof org.bukkit.entity.Projectile pr && pr.getShooter() instanceof Player sp) return sp;
        return d instanceof Player pl ? pl : null;
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onKervanVurus(org.bukkit.event.entity.EntityDamageByEntityEvent ev) {
        String kid = ev.getEntity().getPersistentDataContainer().get(plugin.vahsiKale().kervanKey, org.bukkit.persistence.PersistentDataType.STRING);
        if (kid == null) return;
        Player p = saldirgan(ev);
        Kervan kv = kervanlar.get(UUID.fromString(kid));
        if (p == null || kv == null) return;
        for (UUID id : kv.varliklar) {
            org.bukkit.entity.Entity e = Bukkit.getEntity(id);
            if (e instanceof org.bukkit.entity.Vindicator v && v.isValid()) {
                v.getPersistentDataContainer().set(plugin.vahsiKale().kizginKey, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
                v.setTarget(p);
            }
        }
    }

    @EventHandler
    public void onKervanOlum(org.bukkit.event.entity.EntityDeathEvent ev) {
        org.bukkit.entity.LivingEntity ent = ev.getEntity();
        String kid = ent.getPersistentDataContainer().get(plugin.vahsiKale().kervanKey, org.bukkit.persistence.PersistentDataType.STRING);
        if (kid == null) return;
        ev.getDrops().clear(); ev.setDroppedExp(0);
        Kervan kv = kervanlar.get(UUID.fromString(kid));
        if (kv == null) return;
        kv.varliklar.remove(ent.getUniqueId());
        if (!(ent instanceof org.bukkit.entity.Vindicator)) return;
        for (UUID id : kv.varliklar) {
            org.bukkit.entity.Entity e = Bukkit.getEntity(id);
            if (e instanceof org.bukkit.entity.Vindicator && e.isValid() && !e.isDead()) return; // muhafız kaldı
        }
        VahsiKale k = plugin.vahsiKale().kaleBul(UUID.fromString(kid));
        if (k == null) return;
        Location l = ent.getLocation();
        gorselKaldir(kv);
        Enkaz e = new Enkaz();
        e.kaleId = k.id; e.dunya = l.getWorld().getName(); e.x = l.getX(); e.z = l.getZ(); e.sonYakinlik = System.currentTimeMillis();
        synchronized (this) { e.esya.addAll(kv.esya); kv.esya.clear(); }
        if (e.esya.size() < 3) e.esya.addAll(lootUret(false, k.seviye));
        enkazlar.put(UUID.randomUUID(), e);
        kv.enkaz = true;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            kv.enkaz = false; kv.x = k.merkez.getX(); kv.z = k.merkez.getZ();
            kv.kaynakKey = "kale:" + k.id; kv.kx = kv.x; kv.kz = kv.z; kv.hedefKey = null;
        }, 20L * 60 * 30);
        Player katil = ent.getKiller();
        if (katil != null) {
            Klan kk = plugin.klanManager().oyuncununKlani(katil.getUniqueId());
            if (kk != null) saldiriBildir(kk, k);
            katil.sendMessage(ChatColor.GOLD + k.isim + " kervanı yağmalandı! Enkaz sandığı birazdan belirir.");
        }
        kaydet();
    }

    /** Admin: /npcklan kervan [git] - kervan konumlarını listeler / en yakınına ışınlar. */
    private void kervanKomut(Player p, String[] a) {
        if (!p.hasPermission("klan.admin")) { p.sendMessage(ChatColor.RED + "Yetkin yok."); return; }
        boolean git = a.length > 1 && a[1].equalsIgnoreCase("git");
        double en = Double.MAX_VALUE; Location hedef = null; int n = 0;
        for (Map.Entry<UUID, Kervan> e : kervanlar.entrySet()) {
            VahsiKale k = plugin.vahsiKale().kaleBul(e.getKey());
            if (k == null || k.merkez == null || k.merkez.getWorld() == null) continue;
            Kervan kv = e.getValue();
            if (!k.merkez.getWorld().equals(p.getWorld())) continue;
            double d = Math.hypot(kv.x - p.getLocation().getX(), kv.z - p.getLocation().getZ());
            if (n++ < 12) p.sendMessage(ChatColor.GRAY + k.isim + ": " + Math.round(kv.x) + ", " + Math.round(kv.z) + " (" + Math.round(d) + " blok) "
                    + (kv.enkaz ? ChatColor.RED + "enkaz" : gorselAktif(kv) ? ChatColor.GREEN + "görünür" : ChatColor.YELLOW + "soyut"));
            if (!kv.enkaz && d < en) { en = d; hedef = new Location(k.merkez.getWorld(), kv.x, k.merkez.getWorld().getHighestBlockYAt((int) kv.x, (int) kv.z) + 2, kv.z); }
        }
        if (git && hedef != null) { p.teleport(hedef); p.sendMessage(ChatColor.GREEN + "En yakın kervana ışınlandın (yaklaşınca belirir)."); }
        else if (!git) p.sendMessage(ChatColor.YELLOW + "Işınlanmak için: /npcklan kervan git");
    }

    // ------------------------------------------------------------------ HANCI DEDİKODUSU
    public void dedikodu(Player p) {
        long cd = dk("dedikodu-cooldown-dakika", 12) * 60_000L;
        long simdi = System.currentTimeMillis();
        Long son = dedikoduSon.get(p.getUniqueId());
        if (son != null && simdi - son < cd) {
            p.sendMessage(ChatColor.RED + "Hancı şimdilik yeni bir şey duymadı. " + ((cd - (simdi - son)) / 60000 + 1) + " dk sonra tekrar sor.");
            return;
        }
        double bahsis = dk("dedikodu-bahsis", 200);
        // hedef: dünyadaki canlı bir kervan
        Kervan hedef = null; VahsiKale hk = null; double enYakin = Double.MAX_VALUE;
        for (VahsiKale k : aktifKaleler()) {
            Kervan kv = kervanlar.get(k.id);
            if (kv == null || kv.enkaz || !k.merkez.getWorld().equals(p.getWorld())) continue;
            double d = Math.hypot(kv.x - p.getLocation().getX(), kv.z - p.getLocation().getZ());
            if (d < enYakin) { enYakin = d; hedef = kv; hk = k; }
        }
        if (hedef == null) { p.sendMessage(ChatColor.GRAY + "Hancı omuz silkti: \"Bu aralar yoldan kervan geçmiyor.\""); return; }
        long[] kota = kervanIpucu.computeIfAbsent(hk.id, i -> new long[]{0, simdi});
        if (simdi - kota[1] > 30 * 60_000L) { kota[0] = 0; kota[1] = simdi; }
        if (kota[0] >= dk("dedikodu-kervan-kotasi", 4)) { p.sendMessage(ChatColor.GRAY + "Hancı: \"Bu kervan hakkında bu aralar çok konuştum, biraz sonra gel.\""); return; }
        if (!plugin.meslek().bankadanCek(p.getUniqueId(), bahsis)) {
            p.sendMessage(ChatColor.RED + "Bahşiş için bankada yeterli bakiye yok (" + Para.yaz(bahsis) + ")."); return;
        }
        kota[0]++;
        dedikoduSon.put(p.getUniqueId(), simdi);
        ekonomiLog("npc-dedikodu", p.getName(), "Hancı bahşişi " + Para.yaz(bahsis) + " (" + hk.isim + " kervanı)");
        double dx = hedef.x - p.getLocation().getX(), dz = hedef.z - p.getLocation().getZ();
        double aci = Math.toDegrees(Math.atan2(dx, -dz)); // kuzey = -Z
        int idx = (int) Math.round(((aci % 360) + 360) % 360 / 45.0) % 8;
        p.sendMessage(ChatColor.GOLD + "Hancı fısıldadı: " + ChatColor.YELLOW + "\"" + hk.isim + " Klanı'nın kervanı " + YON[idx] + "daki "
                + YER[rng.nextInt(YER.length)] + " geçiyorlarmış...\"");
        // koordinat/mesafe KESİNLİKLE verilmez
    }

    // ------------------------------------------------------------------ OYUNCU KLANI ↔ NPC KLANI
    private Map<UUID, Integer> saygilar(Klan k) { return saygi.computeIfAbsent(k.id, i -> new LinkedHashMap<>()); }

    /** Kuşatma/elçi/barış gibi ilk temasta tanışma kaydı. */
    public void tanis(Klan klan, VahsiKale kale) {
        if (klan == null || kale == null || !veriYuklendi) return;
        saygilar(klan).putIfAbsent(kale.id, 0);
    }

    /** Oyuncu klanı bir kaleye saldırınca: kalenin müttefiklerinin saygısı düşer, düşmanlarınınki artar (zincir etki). */
    public void saldiriBildir(Klan klan, VahsiKale kale) {
        if (klan == null || kale == null || !veriYuklendi) return;
        tanis(klan, kale);
        saygilar(klan).merge(kale.id, -20, Integer::sum);
        for (VahsiKale d : aktifKaleler()) {
            if (d == kale) continue;
            String durum = iliski.get(anahtar(kale.id, d.id));
            if (durum == null) continue;
            if (saygilar(klan).containsKey(d.id)) {
                saygilar(klan).merge(d.id, "ITTIFAK".equals(durum) ? -15 : 5, Integer::sum);
            }
        }
        kaydet();
    }

    // ------------------------------------------------------------------ İTTİFAK (kalıcı)
    public boolean ittifakVarMi(UUID klanId, UUID kaleId) {
        Map<UUID, Long> m;
        synchronized (this) { m = ittifaklar.get(klanId); }
        if (m == null) return false;
        Long b = m.get(kaleId);
        return b != null && b > System.currentTimeMillis();
    }

    private int seviyeOf(int saygiPuan) { return Math.max(0, saygiPuan / 50); }

    private static class Sahip implements InventoryHolder {
        final UUID klanId; UUID kaleId; // kaleId null = üst menü (klan listesi)
        final Map<Integer, UUID> slotKale = new LinkedHashMap<>();
        final Map<Integer, String> slotEylem = new LinkedHashMap<>();
        Inventory env;
        Sahip(UUID k) { klanId = k; }
        @Override public Inventory getInventory() { return env; }
    }

    private ItemStack oge(Material m, String ad, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(ad);
        meta.setLore(lore);
        it.setItemMeta(meta);
        return it;
    }

    private void cerceve(Inventory env) {
        ItemStack cam = oge(Material.BLACK_STAINED_GLASS_PANE, " ", new ArrayList<>());
        for (int i = 0; i < env.getSize(); i++) if (env.getItem(i) == null) env.setItem(i, cam);
    }

    private boolean dostMu(VahsiKale k, UUID klanId) { return plugin.vahsiKale().dostMu(k, klanId); }

    /** ÜST MENÜ: tanışılan NPC klanları (kılıç simgesi + isim). Tıklayınca o klanın alt menüsü açılır. */
    public void panelAc(Player p) {
        Klan klan = plugin.klanManager().oyuncununKlani(p.getUniqueId());
        if (klan == null) { p.sendMessage(ChatColor.RED + "Bir klanda değilsiniz."); return; }
        Sahip s = new Sahip(klan.id);
        Inventory env = Bukkit.createInventory(s, 54, ChatColor.DARK_RED + "NPC Diplomasi Paneli");
        s.env = env;
        int slot = 0;
        for (Map.Entry<UUID, Integer> en : saygilar(klan).entrySet()) {
            VahsiKale k = plugin.vahsiKale().kaleBul(en.getKey());
            if (k == null || k.fethedildi || slot >= 45) continue;
            int sp = en.getValue();
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Seviye: " + ChatColor.WHITE + k.seviye + ChatColor.GRAY + "  Güç: " + ChatColor.WHITE + gucu(k));
            lore.add(ChatColor.GRAY + "Saygı: " + ChatColor.YELLOW + sp + ChatColor.GRAY + " (seviye " + seviyeOf(sp) + ")");
            lore.add(ChatColor.GRAY + "Durum: " + (dostMu(k, klan.id) ? ChatColor.GREEN + "İttifak" : ChatColor.RED + "Gergin"));
            lore.add("");
            lore.add(ChatColor.YELLOW + "► Görev, haraç, takviye ve ittifak için tıkla");
            env.setItem(slot, oge(Material.IRON_SWORD, ChatColor.GOLD + k.isim + ChatColor.GRAY + " (Sv " + k.seviye + ")", lore));
            s.slotKale.put(slot, k.id);
            slot++;
        }
        if (slot == 0) env.setItem(22, oge(Material.BARRIER, ChatColor.GRAY + "Henüz tanıştığınız NPC klanı yok",
                List.of(ChatColor.GRAY + "Bir Vahşi Kale'ye yaklaşınca", ChatColor.GRAY + "o klanla tanışırsınız.")));
        env.setItem(49, oge(Material.ARROW, ChatColor.YELLOW + "Klan Menüsüne Dön", List.of()));
        s.slotEylem.put(49, "ana");
        cerceve(env);
        p.openInventory(env);
    }

    /** ALT MENÜ: seçilen NPC klanıyla yapılabilecekler (hepsi fareyle). */
    private void altMenuAc(Player p, Klan klan, VahsiKale k) {
        Sahip s = new Sahip(klan.id);
        s.kaleId = k.id;
        Inventory env = Bukkit.createInventory(s, 27, ChatColor.DARK_RED + k.isim + " ► NPC Klanı");
        s.env = env;
        int sp = saygilar(klan).getOrDefault(k.id, 0);
        boolean dost = dostMu(k, klan.id);
        env.setItem(4, oge(Material.IRON_SWORD, ChatColor.GOLD + "" + ChatColor.BOLD + k.isim,
                List.of(ChatColor.GRAY + "Saygı: " + ChatColor.YELLOW + sp + ChatColor.GRAY + " (seviye " + seviyeOf(sp) + ")",
                        ChatColor.GRAY + "Güç: " + ChatColor.WHITE + gucu(k) + ChatColor.GRAY + "  Seviye: " + ChatColor.WHITE + k.seviye,
                        ChatColor.GRAY + "Durum: " + (dost ? ChatColor.GREEN + "İttifak" : ChatColor.RED + "Gergin"))));
        env.setItem(10, oge(Material.GOLD_INGOT, ChatColor.GOLD + "" + ChatColor.BOLD + "Haraç Öde",
                List.of(ChatColor.GRAY + "Klan kasasından " + ChatColor.GREEN + Para.yaz(haracUcreti(k)) + ChatColor.GRAY + " ödenir.",
                        ChatColor.GRAY + "Kazanç: " + ChatColor.YELLOW + "+15 saygı", ChatColor.DARK_GRAY + "(Lider/yardımcı)",
                        "", ChatColor.YELLOW + "► Tıkla")));
        s.slotEylem.put(10, "harac");
        env.setItem(11, oge(gorevEsya(k), ChatColor.AQUA + "" + ChatColor.BOLD + "Eşya Görevi",
                List.of(ChatColor.GRAY + "Teslim: " + ChatColor.WHITE + gorevMiktar(k) + " " + gorevEsya(k).name(),
                        ChatColor.GRAY + "Kazanç: " + ChatColor.YELLOW + "+25 saygı", "", ChatColor.YELLOW + "► Envanterinden teslim et")));
        s.slotEylem.put(11, "esya");
        String[] g = zindanGorev.get(klan.id);
        List<String> zl = new ArrayList<>();
        zl.add(ChatColor.GRAY + "Bir zindanın boss'unu düşürün.");
        zl.add(ChatColor.GRAY + "Kazanç: " + ChatColor.YELLOW + "+40 saygı");
        if (g != null) zl.add(ChatColor.GREEN + "Aktif: " + g[2] + " @ " + g[4] + ", " + g[5] + ", " + g[6]);
        zl.add(""); zl.add(ChatColor.YELLOW + "► " + (g != null ? "Görevi göster" : "Görev al"));
        env.setItem(12, oge(Material.WITHER_SKELETON_SKULL, ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "Zindan Görevi", zl));
        s.slotEylem.put(12, "zindan");
        boolean tak = sp >= 50;
        int pr = prestijOf(klan);
        int minTak = dk("takviye-min-prestij", 5), minItf = dk("ittifak-min-prestij", 10);
        boolean aktifT = aktifTakviyeler.containsKey(klan.id);
        env.setItem(14, oge(Material.IRON_HELMET, ChatColor.GREEN + "" + ChatColor.BOLD + "Takviye Çağır",
                List.of(ChatColor.GRAY + "" + (dk("takviye-muhafiz-sayisi", 5) + Math.min(3, pr / 50)) + " muhafız yanında savaşır, sen geri gönderene kadar kalır.",
                        ChatColor.GRAY + "Ücret: " + ChatColor.GREEN + Para.yaz(takviyeUcretiKlan(k, klan)) + ChatColor.GRAY + " (ilk çağrıda ve her 10 dk'da bir, klan kasasından)",
                        ChatColor.GRAY + "Prestij indirimi: " + ChatColor.AQUA + "%" + (int) (Math.min(0.40, pr * 0.005) * 100) + ChatColor.GRAY + " (prestij " + pr + ")",
                        ChatColor.GRAY + "Gerek: saygı ≥ 50 " + (tak ? ChatColor.GREEN + "✔" : ChatColor.RED + "✘") + ChatColor.GRAY + ", prestij ≥ " + minTak + " " + (pr >= minTak ? ChatColor.GREEN + "✔" : ChatColor.RED + "✘"),
                        ChatColor.DARK_GRAY + "(Lider/yardımcı)", aktifT ? ChatColor.YELLOW + "Şu an aktif bir takviyeniz var." : "", ChatColor.YELLOW + "► Tıkla")));
        s.slotEylem.put(14, "takviye");
        env.setItem(16, oge(Material.BARRIER, ChatColor.RED + "" + ChatColor.BOLD + "Desteği Geri Gönder",
                List.of(ChatColor.GRAY + "Aktif takviyeyi gönderir, ücret kesintisi durur.", aktifT ? ChatColor.GREEN + "Aktif takviye var." : ChatColor.DARK_GRAY + "Aktif takviye yok.",
                        ChatColor.DARK_GRAY + "(Lider/yardımcı)", "", ChatColor.YELLOW + "► Tıkla")));
        s.slotEylem.put(16, "geri-gonder");
        boolean itf = sp >= 100;
        long gunI = 7L + Math.min(7, pr / 5);
        env.setItem(15, oge(dost ? Material.LIME_BANNER : Material.WHITE_BANNER, ChatColor.WHITE + "" + ChatColor.BOLD + "İttifak Kur",
                List.of(ChatColor.GRAY + gunI + " gün: nöbetçiler saldırmaz, uyarı gelmez.", ChatColor.GRAY + "Prestij süreyi uzatır (her 5 prestij +1 gün, en çok 14).",
                        ChatColor.GRAY + "Gerek: saygı ≥ 100 " + (itf ? ChatColor.GREEN + "✔" : ChatColor.RED + "✘") + ChatColor.GRAY + ", prestij ≥ " + minItf + " " + (pr >= minItf ? ChatColor.GREEN + "✔" : ChatColor.RED + "✘"),
                        dost ? ChatColor.GREEN + "Şu an ittifak var (yenilemek için tıkla)." : "",
                        ChatColor.DARK_GRAY + "(Lider/yardımcı)", "", ChatColor.YELLOW + "► Tıkla")));
        s.slotEylem.put(15, "ittifak");
        env.setItem(22, oge(Material.ARROW, ChatColor.YELLOW + "Geri", List.of()));
        s.slotEylem.put(22, "geri");
        cerceve(env);
        p.openInventory(env);
    }

    private double haracUcreti(VahsiKale k) { return 500.0 * k.seviye; }
    private double takviyeUcreti(VahsiKale k) { return 1500.0 * k.seviye; }
    private Material gorevEsya(VahsiKale k) { return switch (k.seviye) { case 1 -> Material.IRON_INGOT; case 2 -> Material.GOLD_INGOT; case 3 -> Material.DIAMOND; case 4 -> Material.EMERALD; default -> Material.NETHERITE_SCRAP; }; }
    private int gorevMiktar(VahsiKale k) { return k.seviye >= 5 ? 4 : 8 - k.seviye; }

    @EventHandler
    public void onPanelTikla(InventoryClickEvent ev) {
        if (!(ev.getInventory().getHolder() instanceof Sahip s)) return;
        ev.setCancelled(true);
        if (!(ev.getWhoClicked() instanceof Player p)) return;
        if (ev.getClickedInventory() == null || ev.getClickedInventory() != ev.getInventory()) return;
        if (ev.getClick() != org.bukkit.event.inventory.ClickType.LEFT && ev.getClick() != org.bukkit.event.inventory.ClickType.RIGHT) return;
        Klan klan = plugin.klanManager().oyuncununKlani(p.getUniqueId());
        if (klan == null || !klan.id.equals(s.klanId)) { p.closeInventory(); return; }
        int slot = ev.getRawSlot();
        if (s.kaleId == null) { // üst menü
            if ("ana".equals(s.slotEylem.get(slot))) { plugin.menu().anaMenu(p); return; }
            UUID kaleId = s.slotKale.get(slot);
            VahsiKale k = kaleId == null ? null : plugin.vahsiKale().kaleBul(kaleId);
            if (k != null && !k.fethedildi) altMenuAc(p, klan, k);
            return;
        }
        String eylem = s.slotEylem.get(slot);
        if (eylem == null) return;
        if ("geri".equals(eylem)) { panelAc(p); return; }
        VahsiKale k = plugin.vahsiKale().kaleBul(s.kaleId);
        if (k == null || k.fethedildi) { panelAc(p); return; }
        KlanUyesi ben = klan.uyeler.get(p.getUniqueId());
        boolean yetkili = ben != null && ben.rol != Rol.UYE;
        switch (eylem) {
            case "harac" -> { if (!yetkili) p.sendMessage(ChatColor.RED + "Haraç için lider/yardımcı olmalısın (kasadan ödenir)."); else harac(p, klan, k); }
            case "esya" -> esyaGorevi(p, klan, k);
            case "zindan" -> zindanGorevi(p, klan, k);
            case "takviye" -> { if (!yetkili) p.sendMessage(ChatColor.RED + "Bu işlem için lider/yardımcı olmalısın."); else takviyeCagir(p, klan, k); }
            case "geri-gonder" -> { if (!yetkili) p.sendMessage(ChatColor.RED + "Bu işlem için lider/yardımcı olmalısın."); else takviyeGeriGonder(p, klan); }
            case "ittifak" -> { if (!yetkili) p.sendMessage(ChatColor.RED + "Bu işlem için lider/yardımcı olmalısın."); else ittifakKur(p, klan, k); }
            default -> { }
        }
        if (p.getOpenInventory().getTopInventory().getHolder() == s) altMenuAc(p, klan, k); // güncel değerlerle yenile
    }

    // ------------------------------------------------------------------ ZİNDAN GÖREVİ (Arena-Ligi bağlantısı)
    /** Arena-Ligi'nin zindanlar.yml'sini (salt-okunur) okuyup temizlenmemiş zindanlardan birini görev verir. */
    private List<String[]> aktifZindanlar() {
        List<String[]> l = new ArrayList<>(); // {id, isim, dunya, x, y, z}
        org.bukkit.plugin.Plugin al = Bukkit.getPluginManager().getPlugin("ArenaLigi");
        if (al == null) return l;
        File f = new File(al.getDataFolder(), "zindanlar.yml");
        if (!f.exists()) return l;
        ConfigurationSection zs = YamlConfiguration.loadConfiguration(f).getConfigurationSection("zindanlar");
        if (zs == null) return l;
        for (String id : zs.getKeys(false)) {
            ConfigurationSection c = zs.getConfigurationSection(id);
            if (c == null || c.getBoolean("temizlendi", false)) continue;
            String[] m = String.valueOf(c.getString("merkez", "")).split(";");
            if (m.length < 4) continue;
            try { l.add(new String[]{id, c.getString("isim", "Zindan"), m[0], String.valueOf(Math.round(Double.parseDouble(m[1]))), String.valueOf(Math.round(Double.parseDouble(m[2]))), String.valueOf(Math.round(Double.parseDouble(m[3])))}); }
            catch (NumberFormatException ignored) { }
        }
        return l;
    }

    private void zindanGorevi(Player p, Klan klan, VahsiKale k) {
        if (saygilar(klan).getOrDefault(k.id, 0) < 0) { p.sendMessage(ChatColor.RED + k.isim + " seninle görev paylaşmıyor (saygı negatif)."); return; }
        List<String[]> aktif = aktifZindanlar();
        String[] mevcut = zindanGorev.get(klan.id);
        if (mevcut != null) {
            boolean hala = false;
            for (String[] z : aktif) if (z[0].equals(mevcut[1])) { hala = true; break; }
            if (hala) {
                p.sendMessage(ChatColor.GOLD + "Aktif zindan göreviniz: " + ChatColor.YELLOW + mevcut[2] + ChatColor.GRAY + " @ " + mevcut[3] + " " + mevcut[4] + ", " + mevcut[5] + ", " + mevcut[6]
                        + ChatColor.GRAY + " - boss'u düşürün.");
                return;
            }
            zindanGorev.remove(klan.id); // temizlendi/silindi: görev iptal
        }
        if (aktif.isEmpty()) { p.sendMessage(ChatColor.RED + "Şu an temizlenmemiş zindan yok, sonra tekrar dene."); return; }
        String[] z = aktif.get(rng.nextInt(aktif.size()));
        zindanGorev.put(klan.id, new String[]{k.id.toString(), z[0], z[1], z[2], z[3], z[4], z[5]});
        kaydet();
        p.sendMessage(ChatColor.GOLD + k.isim + " senden " + ChatColor.YELLOW + z[1] + ChatColor.GOLD + " zindanının temizlenmesini istiyor: "
                + ChatColor.WHITE + z[2] + " " + z[3] + ", " + z[4] + ", " + z[5] + ChatColor.GRAY + " (klanından biri boss'a hasar verip düşürürse görev tamamlanır).");
    }

    /** npczindan konsol komutu (Arena-Ligi boss ölünce gönderir): oyuncunun klanının görevi bu zindansa tamamlanır. */
    public void zindanTamamlandi(String oyuncuAdi, String zindanId) {
        if (!veriYuklendi) return;
        Player p = Bukkit.getPlayerExact(oyuncuAdi);
        if (p == null) return;
        Klan klan = plugin.klanManager().oyuncununKlani(p.getUniqueId());
        if (klan == null) return;
        String[] g;
        synchronized (this) {
            g = zindanGorev.get(klan.id);
            if (g == null || !g[1].equals(zindanId)) return;
            zindanGorev.remove(klan.id); // önce kaldır -> aynı görev ikinci kez ödül vermez (birden çok katılımcı komutu)
        }
        VahsiKale k = plugin.vahsiKale().kaleBul(UUID.fromString(g[0]));
        if (k != null) { saygilar(klan).merge(k.id, 40, Integer::sum); guc.put(k.id, gucu(k) + 1); }
        kaydet();
        plugin.klanManager().klanaGonder(klan, "npc-zindan-gorev", "&a{kale} &7zindan görevi tamamlandı (&e{zindan}&7): &e+40 saygı&7.",
                "kale", k != null ? k.isim : "NPC klanı", "zindan", g[2]);
    }

    private void harac(Player p, Klan klan, VahsiKale k) {
        double u = haracUcreti(k);
        if (plugin.kasaKilitliMi(klan)) { p.sendMessage(ChatColor.RED + "Klan kasası şu an kilitli (savaşta)."); return; }
        if (klan.kasa < u) { p.sendMessage(ChatColor.RED + "Klan kasasında yeterli para yok (" + Para.yaz(u) + ")."); return; }
        klan.kasa = Para.kurus(klan.kasa - u);
        plugin.veri().kaydet();
        plugin.log().yaz(klan, p.getName(), "NPC HARAC", Para.yaz(u) + " -> " + k.isim + " (kasa: " + Para.yaz(klan.kasa) + ")");
        hazine.put(k.id, hazinesi(k) + u);
        saygilar(klan).merge(k.id, 15, Integer::sum);
        kaydet();
        p.sendMessage(ChatColor.GREEN + k.isim + " haracı kabul etti. +15 saygı.");
    }

    private void esyaGorevi(Player p, Klan klan, VahsiKale k) {
        Material m = gorevEsya(k); int n = gorevMiktar(k);
        if (!p.getInventory().containsAtLeast(new ItemStack(m), n)) { p.sendMessage(ChatColor.RED + "Envanterinde " + n + " " + m.name() + " yok."); return; }
        p.getInventory().removeItem(new ItemStack(m, n)); // önce al, sonra ödül (dupe yok)
        saygilar(klan).merge(k.id, 25, Integer::sum);
        guc.put(k.id, gucu(k) + 1);
        kaydet();
        p.sendMessage(ChatColor.GREEN + "Görev tamamlandı! +25 saygı.");
    }

    private void ittifakKur(Player p, Klan klan, VahsiKale k) {
        if (saygilar(klan).getOrDefault(k.id, 0) < 100) { p.sendMessage(ChatColor.RED + "İttifak için en az 100 saygı gerekir."); return; }
        int minP = dk("ittifak-min-prestij", 10);
        if (prestijOf(klan) < minP) { p.sendMessage(ChatColor.RED + "İttifak için klan prestiji en az " + minP + " olmalı (şu an " + prestijOf(klan) + ")."); return; }
        long gun = 7L + Math.min(7, prestijOf(klan) / 5); // her 5 prestij +1 gün, en fazla 14 gün
        long bitis = System.currentTimeMillis() + gun * 24 * 3_600_000L;
        k.barisAnlasmalari.put(klan.id, bitis);
        synchronized (this) { ittifaklar.computeIfAbsent(klan.id, i -> new LinkedHashMap<>()).put(k.id, bitis); }
        kaydet(); // yeniden başlatmada kaybolmasın
        p.sendMessage(ChatColor.GREEN + k.isim + " ile " + gun + " günlük ittifak kuruldu (nöbetçiler saldırmaz, uyarı gelmez).");
    }

    private int prestijOf(Klan klan) { return Math.max(0, klan.prestij); }
    private double takviyeUcretiKlan(VahsiKale k, Klan klan) {
        double indirim = Math.min(0.40, prestijOf(klan) * 0.005); // her 2 prestij %1, en fazla %40
        return Math.round(takviyeUcreti(k) * (1 - indirim));
    }

    private void takviyeCagir(Player p, Klan klan, VahsiKale k) {
        if (aktifTakviyeler.containsKey(klan.id)) { p.sendMessage(ChatColor.RED + "Zaten aktif bir takviyeniz var. Önce 'Desteği Geri Gönder' ile gönderin."); return; }
        if (saygilar(klan).getOrDefault(k.id, 0) < 50) { p.sendMessage(ChatColor.RED + "Takviye için en az 50 saygı gerekir."); return; }
        int minP = dk("takviye-min-prestij", 5);
        if (prestijOf(klan) < minP) { p.sendMessage(ChatColor.RED + "Takviye için klan prestiji en az " + minP + " olmalı (şu an " + prestijOf(klan) + ")."); return; }
        long gun = System.currentTimeMillis() / 86_400_000L, simdi = System.currentTimeMillis();
        long[] t = takviye.computeIfAbsent(klan.id, i -> new long[]{gun, 0, 0});
        if (t[0] != gun) { t[0] = gun; t[1] = 0; }
        if (t[1] >= dk("takviye-gunluk-limit", 2)) { p.sendMessage(ChatColor.RED + "Bugünkü takviye hakkın doldu."); return; }
        long cd = dk("takviye-cooldown-dakika", 30) * 60_000L;
        if (simdi - t[2] < cd) { p.sendMessage(ChatColor.RED + "Takviye için " + ((cd - (simdi - t[2])) / 60000 + 1) + " dk beklemelisin."); return; }
        double u = takviyeUcretiKlan(k, klan);
        if (plugin.kasaKilitliMi(klan) || klan.kasa < u) { p.sendMessage(ChatColor.RED + "Klan kasası yetersiz/kilitli (" + Para.yaz(u) + ")."); return; }
        klan.kasa = Para.kurus(klan.kasa - u);
        plugin.veri().kaydet();
        plugin.log().yaz(klan, p.getName(), "NPC TAKVIYE", Para.yaz(u) + " -> " + k.isim + " (kasa: " + Para.yaz(klan.kasa) + ")");
        t[1]++; t[2] = simdi;
        Takviye tk = new Takviye();
        tk.kaleId = k.id; tk.sahip = p.getUniqueId(); tk.sonUcret = simdi;
        aktifTakviyeler.put(klan.id, tk);
        int adet = dk("takviye-muhafiz-sayisi", 5) + Math.min(3, prestijOf(klan) / 50);
        int gelen = plugin.vahsiKale().takviyeMuhafizlariCagir(k, p, adet, () -> aktifTakviyeler.get(klan.id) == tk);
        hazine.put(k.id, hazinesi(k) + u);
        kaydet();
        p.sendMessage(ChatColor.GREEN + k.isim + " " + gelen + " muhafız gönderdi. " + ChatColor.GRAY + "Her 10 dakikada " + Para.yaz(u)
                + " kesilir; geri göndermek için NPC panelinde klanı seçip 'Desteği Geri Gönder'e basın.");
        p.playSound(p.getLocation(), Sound.EVENT_RAID_HORN, 1f, 1f);
    }

    private void takviyeGeriGonder(Player p, Klan klan) {
        Takviye tk = aktifTakviyeler.remove(klan.id);
        if (tk == null) { p.sendMessage(ChatColor.GRAY + "Aktif takviye yok."); return; }
        p.sendMessage(ChatColor.YELLOW + "Takviye geri gönderildi (ücret kesintisi durdu).");
    }

    /** Aktif takviyelerin 10 dakikalık ücret kesimi ve sahibi çevrimdışı kalırsa sonlandırma (~3 sn'de bir çağrılır). */
    public void takviyeKontrol() {
        if (aktifTakviyeler.isEmpty()) return;
        long simdi = System.currentTimeMillis();
        for (Map.Entry<UUID, Takviye> en : new ArrayList<>(aktifTakviyeler.entrySet())) {
            Takviye tk = en.getValue();
            Klan klan = plugin.klanManager().klanGetir(en.getKey());
            VahsiKale k = plugin.vahsiKale().kaleBul(tk.kaleId);
            Player sahip = Bukkit.getPlayer(tk.sahip);
            if (klan == null || k == null || k.fethedildi || sahip == null || !sahip.isOnline()) { aktifTakviyeler.remove(en.getKey()); continue; }
            if (simdi - tk.sonUcret < 10 * 60_000L) continue;
            double u = takviyeUcretiKlan(k, klan);
            if (plugin.kasaKilitliMi(klan) || klan.kasa < u) {
                aktifTakviyeler.remove(en.getKey());
                plugin.klanManager().klanaGonder(klan, "npc-takviye-bitti", "&c{kale} takviyesi kasa yetersiz/kilitli olduğu için geri çekildi.", "kale", k.isim);
                continue;
            }
            klan.kasa = Para.kurus(klan.kasa - u);
            plugin.veri().kaydet();
            plugin.log().yaz(klan, sahip.getName(), "NPC TAKVIYE", "10 dk ücreti " + Para.yaz(u) + " -> " + k.isim + " (kasa: " + Para.yaz(klan.kasa) + ")");
            hazine.put(k.id, hazinesi(k) + u);
            tk.sonUcret = simdi;
            kaydet();
            plugin.klanManager().klanaGonder(klan, "npc-takviye-ucret", "&e{kale} takviyesi için 10 dk ücreti kesildi: &a{ucret}", "kale", k.isim, "ucret", Para.yaz(u));
        }
    }

    private int yonIdx(double dx, double dz) {
        double aci = Math.toDegrees(Math.atan2(dx, -dz)); // kuzey = -Z
        return (int) Math.round(((aci % 360) + 360) % 360 / 45.0) % 8;
    }

    private boolean bilgiBekle(Player p) {
        long simdi = System.currentTimeMillis();
        Long son = bilgiSon.get(p.getUniqueId());
        if (son != null && simdi - son < 15_000L) { p.sendMessage(ChatColor.GRAY + "Hancı: \"Bir dakika, nefes alayım.\""); return false; }
        bilgiSon.put(p.getUniqueId(), simdi);
        return true;
    }

    /** Hancı menüsü: en yakın NPC klanı (yön + yuvarlak uzaklık, koordinat yok). */
    public void hanciKaleBilgi(Player p) {
        if (!bilgiBekle(p)) return;
        VahsiKale en = null; double mes = Double.MAX_VALUE;
        for (VahsiKale k : aktifKaleler()) {
            if (!k.merkez.getWorld().equals(p.getWorld())) continue;
            double d = Math.hypot(k.merkez.getX() - p.getLocation().getX(), k.merkez.getZ() - p.getLocation().getZ());
            if (d < mes) { mes = d; en = k; }
        }
        if (en == null) { p.sendMessage(ChatColor.GRAY + "Hancı omuz silkti: \"Bu civarda bilinen bir klan kalesi yok.\""); return; }
        Klan klan = plugin.klanManager().oyuncununKlani(p.getUniqueId());
        String iliskiS = klan == null ? "" : (dostMu(en, klan.id) ? " İttifakınız var." : saygilar(klan).containsKey(en.id) ? " Onları tanıyorsunuz." : " Henüz tanışmadınız.");
        int idx = yonIdx(en.merkez.getX() - p.getLocation().getX(), en.merkez.getZ() - p.getLocation().getZ());
        p.sendMessage(ChatColor.GOLD + "Hancı: " + ChatColor.YELLOW + "\"" + en.isim + " Klanı " + YON[idx] + " tarafında, yaklaşık " + (Math.round(mes / 250.0) * 250) + " blok ötede. (Seviye " + en.seviye + ")" + iliskiS + "\"");
    }

    /** Hancı menüsü: en yakın temizlenmemiş zindan (yön + yuvarlak uzaklık). */
    public void hanciZindanBilgi(Player p) {
        if (!bilgiBekle(p)) return;
        String[] en = null; double mes = Double.MAX_VALUE;
        for (String[] z : aktifZindanlar()) {
            if (!z[2].equals(p.getWorld().getName())) continue;
            double d = Math.hypot(Double.parseDouble(z[3]) - p.getLocation().getX(), Double.parseDouble(z[5]) - p.getLocation().getZ());
            if (d < mes) { mes = d; en = z; }
        }
        if (en == null) { p.sendMessage(ChatColor.GRAY + "Hancı: \"Şu an bilinen temizlenmemiş bir zindan yok.\""); return; }
        int idx = yonIdx(Double.parseDouble(en[3]) - p.getLocation().getX(), Double.parseDouble(en[5]) - p.getLocation().getZ());
        p.sendMessage(ChatColor.GOLD + "Hancı: " + ChatColor.YELLOW + "\"" + en[1] + " zindanı " + YON[idx] + " tarafında, yaklaşık " + (Math.round(mes / 100.0) * 100) + " blok ötede.\"");
    }

    // ------------------------------------------------------------------ BİLGİ / KOMUT
    public void komut(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Sadece oyuncular."); return; }
        String alt = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "panel";
        switch (alt) {
            case "dedikodu" -> dedikodu(p);
            case "kervan" -> kervanKomut(p, a);
            case "patika" -> {
                if (!p.hasPermission("klan.admin")) { p.sendMessage(ChatColor.RED + "Yetkin yok."); return; }
                String a2 = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
                if (a2.equals("sifirla")) plugin.patika().hepsiniGeriAl(p); else plugin.patika().bilgi(p);
            }
            case "hanci" -> {
                if (!p.hasPermission("klan.admin")) { p.sendMessage(ChatColor.RED + "Yetkin yok."); return; }
                String a2 = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
                if (a2.equals("kur")) plugin.hanci().kur(p);
                else if (a2.equals("sil")) plugin.hanci().sil(p);
                else p.sendMessage(ChatColor.YELLOW + "/npcklan hanci <kur|sil>");
            }
            default -> panelAc(p);
        }
    }

    // ------------------------------------------------------------------ KALICILIK
    private void yukle() {
        try {
            if (dosya.exists()) {
                YamlConfiguration y = YamlConfiguration.loadConfiguration(dosya);
                ConfigurationSection g = y.getConfigurationSection("guc");
                if (g != null) for (String k : g.getKeys(false)) guc.put(UUID.fromString(k), g.getInt(k));
                ConfigurationSection h = y.getConfigurationSection("hazine");
                if (h != null) for (String k : h.getKeys(false)) hazine.put(UUID.fromString(k), h.getDouble(k));
                ConfigurationSection he = y.getConfigurationSection("hazine-esya");
                if (he != null) for (String k : he.getKeys(false)) {
                    List<ItemStack> li = new ArrayList<>();
                    for (Object o : he.getList(k, new ArrayList<>())) if (o instanceof ItemStack is) li.add(is);
                    hazineEsya.put(UUID.fromString(k), li);
                }
                ConfigurationSection zg = y.getConfigurationSection("zindan-gorev");
                if (zg != null) for (String k : zg.getKeys(false)) {
                    List<String> v = zg.getStringList(k);
                    if (v.size() == 7) zindanGorev.put(UUID.fromString(k), v.toArray(new String[0]));
                }
                for (String sv : y.getStringList("dogal-koyler")) {
                    String[] a = sv.split(";");
                    if (a.length == 3) { DKoy d = new DKoy(); d.dunya = a[0]; d.x = Double.parseDouble(a[1]); d.z = Double.parseDouble(a[2]); dogalKoyler.add(d); }
                }
                ConfigurationSection it = y.getConfigurationSection("ittifak");
                if (it != null) for (String kl : it.getKeys(false)) {
                    ConfigurationSection m = it.getConfigurationSection(kl);
                    if (m == null) continue;
                    Map<UUID, Long> mm = new LinkedHashMap<>();
                    for (String ka : m.getKeys(false)) mm.put(UUID.fromString(ka), m.getLong(ka));
                    ittifaklar.put(UUID.fromString(kl), mm);
                }
                ConfigurationSection i = y.getConfigurationSection("iliski");
                if (i != null) for (String k : i.getKeys(false)) iliski.put(k.replace('_', '|'), i.getString(k));
                ConfigurationSection sg = y.getConfigurationSection("saygi");
                if (sg != null) for (String kl : sg.getKeys(false)) {
                    ConfigurationSection ks = sg.getConfigurationSection(kl);
                    if (ks == null) continue;
                    Map<UUID, Integer> m = new LinkedHashMap<>();
                    for (String ka : ks.getKeys(false)) m.put(UUID.fromString(ka), ks.getInt(ka));
                    saygi.put(UUID.fromString(kl), m);
                }
                ConfigurationSection kv = y.getConfigurationSection("kervan");
                if (kv != null) for (String k : kv.getKeys(false)) {
                    Kervan n = new Kervan();
                    for (Object o : kv.getList(k + ".esya", new ArrayList<>())) if (o instanceof ItemStack is) n.esya.add(is); n.x = kv.getDouble(k + ".x"); n.z = kv.getDouble(k + ".z"); n.enkaz = kv.getBoolean(k + ".enkaz");
                    n.kaynakKey = kv.getString(k + ".kaynak"); n.hedefKey = kv.getString(k + ".hedef"); n.kx = kv.getDouble(k + ".kx"); n.kz = kv.getDouble(k + ".kz"); n.koyGordu = kv.getBoolean(k + ".koygordu"); n.muhafiz = kv.getInt(k + ".muhafiz", -1);
                    kervanlar.put(UUID.fromString(k), n);
                }
                ConfigurationSection en = y.getConfigurationSection("enkaz");
                if (en != null) for (String k : en.getKeys(false)) {
                    Enkaz e = new Enkaz();
                    e.kaleId = UUID.fromString(en.getString(k + ".kale"));
                    e.dunya = en.getString(k + ".dunya"); e.x = en.getDouble(k + ".x"); e.z = en.getDouble(k + ".z");
                    for (Object o : en.getList(k + ".esya", new ArrayList<>())) if (o instanceof ItemStack is) e.esya.add(is);
                    e.sonYakinlik = en.getLong(k + ".son"); e.sandikAcildi = en.getBoolean(k + ".acildi"); e.sandikYerlesti = en.getBoolean(k + ".yerlesti");
                    e.sx = en.getInt(k + ".sx"); e.sy = en.getInt(k + ".sy"); e.sz = en.getInt(k + ".sz");
                    e.sahne.addAll(en.getStringList(k + ".sahne")); e.sahneHazir = en.getBoolean(k + ".sahnehazir");
                    enkazlar.put(UUID.fromString(k), e);
                }
            }
            veriYuklendi = true; // dosya yok = ilk çalışma (boş başlangıç meşru); okuma hatası veriYuklendi'yi açmaz
        } catch (Exception ex) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "npc_dunya.yml okunamadı - kayıt KAPALI (diskteki veri ezilmeyecek)", ex);
        }
    }

    public synchronized void kaydet() {
        if (!veriYuklendi) return;
        try {
            YamlConfiguration y = new YamlConfiguration();
            guc.forEach((k, v) -> y.set("guc." + k, v));
            hazine.forEach((k, v) -> y.set("hazine." + k, v));
            hazineEsya.forEach((k, v) -> y.set("hazine-esya." + k, v));
            zindanGorev.forEach((k, v) -> y.set("zindan-gorev." + k, java.util.Arrays.asList(v)));
            List<String> kl2 = new ArrayList<>();
            for (DKoy d : dogalKoyler) kl2.add(d.dunya + ";" + d.x + ";" + d.z);
            y.set("dogal-koyler", kl2);
            ittifaklar.forEach((kl, m) -> m.forEach((ka, v) -> y.set("ittifak." + kl + "." + ka, v)));
            iliski.forEach((k, v) -> y.set("iliski." + k.replace('|', '_'), v));
            saygi.forEach((kl, m) -> m.forEach((ka, v) -> y.set("saygi." + kl + "." + ka, v)));
            kervanlar.forEach((k, v) -> { y.set("kervan." + k + ".x", v.x); y.set("kervan." + k + ".z", v.z); y.set("kervan." + k + ".esya", v.esya); y.set("kervan." + k + ".enkaz", v.enkaz); y.set("kervan." + k + ".kaynak", v.kaynakKey); y.set("kervan." + k + ".hedef", v.hedefKey); y.set("kervan." + k + ".kx", v.kx); y.set("kervan." + k + ".kz", v.kz); y.set("kervan." + k + ".koygordu", v.koyGordu); y.set("kervan." + k + ".muhafiz", v.muhafiz); });
            enkazlar.forEach((k, e) -> {
                String p = "enkaz." + k;
                y.set(p + ".kale", e.kaleId.toString()); y.set(p + ".dunya", e.dunya); y.set(p + ".x", e.x); y.set(p + ".z", e.z); y.set(p + ".esya", e.esya);
                y.set(p + ".son", e.sonYakinlik); y.set(p + ".acildi", e.sandikAcildi); y.set(p + ".yerlesti", e.sandikYerlesti);
                y.set(p + ".sahne", e.sahne); y.set(p + ".sahnehazir", e.sahneHazir);
                y.set(p + ".sx", e.sx); y.set(p + ".sy", e.sy); y.set(p + ".sz", e.sz);
            });
            File gecici = new File(plugin.getDataFolder(), "npc_dunya.yml.tmp");
            y.save(gecici);
            java.nio.file.Files.move(gecici.toPath(), dosya.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ex) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "npc_dunya.yml yazılamadı", ex);
        }
    }

    public void kapat() { for (Kervan kv : kervanlar.values()) gorselKaldir(kv); kaydet(); }
}
